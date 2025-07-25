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
package org.apache.accumulo.test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.accumulo.core.util.LazySingletons.RANDOM;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.admin.servers.ServerId;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.manager.thrift.TabletServerStatus;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.rpc.clients.ThriftClientTypes;
import org.apache.accumulo.core.tabletserver.thrift.TabletServerClientService;
import org.apache.accumulo.minicluster.ServerType;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloConfigImpl;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.test.functional.ConfigurableMacBase;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;

import com.google.common.net.HostAndPort;

public class TotalQueuedIT extends ConfigurableMacBase {

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(4);
  }

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.getClusterServerConfiguration().setNumDefaultTabletServers(1);
    // use mini DFS so walogs sync and flush will work
    cfg.useMiniDFS(true);
    // property is a fixed property (requires restart to be picked up), so set here in initial cfg
    cfg.setProperty(Property.TSERV_TOTAL_MUTATION_QUEUE_MAX.getKey(), "" + SMALL_QUEUE_SIZE);
  }

  private int SMALL_QUEUE_SIZE = 100000;
  private int LARGE_QUEUE_SIZE = SMALL_QUEUE_SIZE * 10;
  private static final long N = 1000000;

  @Test
  public void test() throws Exception {
    try (AccumuloClient c = Accumulo.newClient().from(getClientProperties()).build()) {
      String tableName = getUniqueNames(1)[0];
      c.tableOperations().create(tableName);
      c.tableOperations().setProperty(tableName, Property.TABLE_MAJC_RATIO.getKey(), "9999");
      c.tableOperations().setProperty(tableName, Property.TABLE_FILE_MAX.getKey(), "999");
      Thread.sleep(SECONDS.toMillis(1));
      // get an idea of how fast the syncs occur
      byte[] row = new byte[250];
      BatchWriterConfig cfg = new BatchWriterConfig();
      cfg.setMaxWriteThreads(10);
      cfg.setMaxLatency(1, SECONDS);
      cfg.setMaxMemory(1024 * 1024);
      long realSyncs = getSyncs(c);
      long now = System.currentTimeMillis();
      long bytesSent = 0;
      try (BatchWriter bw = c.createBatchWriter(tableName, cfg)) {
        for (int i = 0; i < N; i++) {
          RANDOM.get().nextBytes(row);
          Mutation m = new Mutation(row);
          m.put("", "", "");
          bw.addMutation(m);
          bytesSent += m.estimatedMemoryUsed();
        }
      }
      long diff = System.currentTimeMillis() - now;
      double secs = diff / 1000.;
      double syncs = bytesSent / SMALL_QUEUE_SIZE;
      double syncsPerSec = syncs / secs;
      System.out.printf("Sent %d bytes in %f secs approximately %d syncs (%f syncs per sec)%n",
          bytesSent, secs, ((long) syncs), syncsPerSec);
      long update = getSyncs(c);
      System.out.println("Syncs " + (update - realSyncs));
      realSyncs = update;

      // Now with a much bigger total queue
      c.instanceOperations().setProperty(Property.TSERV_TOTAL_MUTATION_QUEUE_MAX.getKey(),
          "" + LARGE_QUEUE_SIZE);
      c.tableOperations().flush(tableName, null, null, true);
      Thread.sleep(SECONDS.toMillis(1));

      // property changed is a fixed property (requires restart to be picked up), restart TServers
      c.tableOperations().offline(tableName, true);
      getCluster().getClusterControl().stopAllServers(ServerType.TABLET_SERVER);
      getCluster().getClusterControl().startAllServers(ServerType.TABLET_SERVER);
      c.tableOperations().online(tableName, true);

      try (BatchWriter bw = c.createBatchWriter(tableName, cfg)) {
        now = System.currentTimeMillis();
        bytesSent = 0;
        for (int i = 0; i < N; i++) {
          RANDOM.get().nextBytes(row);
          Mutation m = new Mutation(row);
          m.put("", "", "");
          bw.addMutation(m);
          bytesSent += m.estimatedMemoryUsed();
        }
      }
      diff = System.currentTimeMillis() - now;
      secs = diff / 1000.;
      syncs = bytesSent / LARGE_QUEUE_SIZE;
      syncsPerSec = syncs / secs;
      System.out.printf("Sent %d bytes in %f secs approximately %d syncs (%f syncs per sec)%n",
          bytesSent, secs, ((long) syncs), syncsPerSec);
      update = getSyncs(c);
      System.out.println("Syncs " + (update - realSyncs));
      assertTrue(update - realSyncs < realSyncs);
    }
  }

  private long getSyncs(AccumuloClient c) throws Exception {
    ServerContext context = getServerContext();
    for (ServerId tserver : c.instanceOperations().getServers(ServerId.Type.TABLET_SERVER)) {
      TabletServerClientService.Client client =
          ThriftUtil.getClient(ThriftClientTypes.TABLET_SERVER,
              HostAndPort.fromParts(tserver.getHost(), tserver.getPort()), context);
      TabletServerStatus status = client.getTabletServerStatus(null, context.rpcCreds());
      return status.syncs;
    }
    return 0;
  }
}
