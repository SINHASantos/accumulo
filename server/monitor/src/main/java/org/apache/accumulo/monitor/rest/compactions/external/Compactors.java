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
package org.apache.accumulo.monitor.rest.compactions.external;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON Object for displaying External Compactions. Variable names become JSON Keys.
 */
public class Compactors {

  // Variable names become JSON keys
  public final int numCompactors;
  public final List<CompactorInfo> compactors = new ArrayList<>();

  public Compactors(ExternalCompactionInfo ecInfo) {
    ecInfo.getCompactors().forEach(csi -> {
      var fetchedTime = ecInfo.getFetchedTimeMillis();
      compactors.add(new CompactorInfo(fetchedTime, csi.getResourceGroup().canonical(),
          csi.toHostPortString()));
    });
    numCompactors = compactors.size();
  }
}
