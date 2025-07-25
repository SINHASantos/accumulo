/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.util;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TabletAvailability;
import org.apache.accumulo.core.manager.state.tables.TableState;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.metadata.TabletState;
import org.apache.accumulo.core.metadata.schema.Ample.DataLevel;
import org.apache.accumulo.core.metadata.schema.TabletMetadata;
import org.apache.accumulo.core.metadata.schema.TabletsMetadata;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.cli.ServerUtilOpts;
import org.apache.accumulo.server.manager.LiveTServerSet;
import org.apache.accumulo.server.manager.LiveTServerSet.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

public class FindOfflineTablets {
  private static final Logger log = LoggerFactory.getLogger(FindOfflineTablets.class);

  public static void main(String[] args) throws Exception {
    ServerUtilOpts opts = new ServerUtilOpts();
    opts.parseArgs(FindOfflineTablets.class.getName(), args);
    Span span = TraceUtil.startSpan(FindOfflineTablets.class, "main");
    try (Scope scope = span.makeCurrent()) {
      ServerContext context = opts.getServerContext();
      findOffline(context, null, false, false, System.out::println, System.out::println);
    } finally {
      span.end();
    }
  }

  public static int findOffline(ServerContext context, String tableName, boolean skipZkScan,
      boolean skipRootScan, Consumer<String> printInfoMethod, Consumer<String> printProblemMethod)
      throws TableNotFoundException {

    final AtomicBoolean scanning = new AtomicBoolean(false);

    LiveTServerSet tservers = new LiveTServerSet(context);

    tservers.startListeningForTabletServerChanges(new Listener() {
      @Override
      public void update(LiveTServerSet current, Set<TServerInstance> deleted,
          Set<TServerInstance> added) {
        if (!deleted.isEmpty() && scanning.get()) {
          log.warn("Tablet servers deleted while scanning: {}", deleted);
        }
        if (!added.isEmpty() && scanning.get()) {
          log.warn("Tablet servers added while scanning: {}", added);
        }
      }
    });
    scanning.set(true);

    int offline = 0;

    if (!skipZkScan) {
      try (TabletsMetadata tabletsMetadata =
          context.getAmple().readTablets().forLevel(DataLevel.ROOT).build()) {
        printInfoMethod.accept("Scanning zookeeper");
        if ((offline =
            checkTablets(context, tabletsMetadata.iterator(), tservers, printProblemMethod)) > 0) {
          return offline;
        }
      }
    }

    if (SystemTables.ROOT.tableName().equals(tableName)) {
      return 0;
    }

    if (!skipRootScan) {
      printInfoMethod.accept("Scanning " + SystemTables.ROOT.tableName());
      try (TabletsMetadata tabletsMetadata =
          context.getAmple().readTablets().forLevel(DataLevel.METADATA).build()) {
        if ((offline =
            checkTablets(context, tabletsMetadata.iterator(), tservers, printProblemMethod)) > 0) {
          return offline;
        }
      }
    }

    if (SystemTables.METADATA.tableName().equals(tableName)) {
      return 0;
    }

    printInfoMethod.accept("Scanning " + SystemTables.METADATA.tableName());

    try (var metaScanner = context.getAmple().readTablets().forLevel(DataLevel.USER).build()) {
      return checkTablets(context, metaScanner.iterator(), tservers, printProblemMethod);
    }
  }

  private static int checkTablets(ServerContext context, Iterator<TabletMetadata> scanner,
      LiveTServerSet tservers, Consumer<String> printProblemMethod) {
    int offline = 0;

    while (scanner.hasNext() && !System.out.checkError()) {
      TabletMetadata tabletMetadata = scanner.next();
      Set<TServerInstance> liveTServers = tservers.getCurrentServers();
      TabletState state = TabletState.compute(tabletMetadata, liveTServers);
      if (state != null && state != TabletState.HOSTED
          && tabletMetadata.getTabletAvailability() == TabletAvailability.HOSTED
          && context.getTableManager().getTableState(tabletMetadata.getTableId())
              == TableState.ONLINE) {
        printProblemMethod.accept(tabletMetadata.getExtent() + " is " + state + "  #walogs:"
            + tabletMetadata.getLogs().size());
        offline++;
      }
    }

    return offline;
  }
}
