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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.accumulo.core.Constants.ZGC_LOCK;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.fate.zookeeper.ZooReader;
import org.apache.accumulo.core.lock.ServiceLockPaths;
import org.apache.accumulo.core.zookeeper.ZcStat;
import org.apache.accumulo.core.zookeeper.ZooCache;
import org.apache.accumulo.core.zookeeper.ZooSession;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.util.serviceStatus.ServiceStatusReport;
import org.apache.accumulo.server.util.serviceStatus.StatusSummary;
import org.apache.zookeeper.KeeperException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceStatusCmdTest {

  private static final Logger LOG = LoggerFactory.getLogger(ServiceStatusCmdTest.class);

  private ServerContext context;
  private ZooSession zooReader;
  private ZooCache zooCache;

  @BeforeEach
  public void populateContext() {
    context = createMock(ServerContext.class);
    zooReader = createMock(ZooSession.class);
    zooCache = createMock(ZooCache.class);
    expect(zooReader.asReader()).andReturn(new ZooReader(zooReader)).anyTimes();
    expect(context.getZooCache()).andReturn(zooCache).anyTimes();
    expect(context.getZooSession()).andReturn(zooReader).anyTimes();
    expect(context.getServerPaths()).andReturn(new ServiceLockPaths(zooCache)).anyTimes();
    replay(context);
  }

  @AfterEach
  public void validateMocks() {
    verify(context, zooReader, zooCache);
  }

  @Test
  public void testManagerHosts() throws Exception {
    replay(zooCache);
    String lock1Name = "zlock#" + UUID.randomUUID() + "#0000000001";
    String lock2Name = "zlock#" + UUID.randomUUID() + "#0000000002";
    String lock3Name = "zlock#" + UUID.randomUUID() + "#0000000003";

    String lock1data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"MANAGER\",\"address\":\"localhost:9991\",\"group\":\"default\"}]}";
    String lock2Data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"MANAGER\",\"address\":\"localhost:9992\",\"group\":\"default\"}]}";
    String lock3Data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"MANAGER\",\"address\":\"hostA:9999\",\"group\":\"manager1\"}]}";

    String lockPath = Constants.ZMANAGER_LOCK;
    expect(zooReader.getChildren(lockPath, null))
        .andReturn(List.of(lock1Name, lock2Name, lock3Name));
    expect(zooReader.getData(lockPath + "/" + lock1Name, null, null))
        .andReturn(lock1data.getBytes(UTF_8));
    expect(zooReader.getData(lockPath + "/" + lock2Name, null, null))
        .andReturn(lock2Data.getBytes(UTF_8));
    expect(zooReader.getData(lockPath + "/" + lock3Name, null, null))
        .andReturn(lock3Data.getBytes(UTF_8));
    replay(zooReader);

    ServiceStatusCmd cmd = new ServiceStatusCmd();
    StatusSummary status = cmd.getManagerStatus(context);
    LOG.info("manager status data: {}", status);

    assertEquals(3, status.getServiceCount());

    // expect sorted by name
    Map<String,Set<String>> hostByGroup = new TreeMap<>();
    hostByGroup.put("default", new TreeSet<>(List.of("localhost:9991", "localhost:9992")));
    hostByGroup.put("manager1", new TreeSet<>(List.of("hostA:9999")));

    StatusSummary expected = new StatusSummary(ServiceStatusReport.ReportKey.MANAGER,
        new TreeSet<>(List.of("default", "manager1")), hostByGroup, 0);

    assertEquals(expected.hashCode(), status.hashCode());
    assertEquals(expected.getDisplayName(), status.getDisplayName());
    assertEquals(expected.getResourceGroups(), status.getResourceGroups());
    assertEquals(expected.getServiceByGroups(), status.getServiceByGroups());
    assertEquals(expected.getServiceCount(), status.getServiceCount());
    assertEquals(expected.getErrorCount(), status.getErrorCount());
    assertEquals(expected, status);
  }

  @Test
  public void testMonitorHosts() throws Exception {
    replay(zooCache);
    String lock1Name = "zlock#" + UUID.randomUUID() + "#0000000001";
    String lock2Name = "zlock#" + UUID.randomUUID() + "#0000000002";

    String host1 =
        "{\"descriptors\":[{\"uuid\":\"87465459-9c8f-4f95-b4c6-ef3029030d05\",\"service\":\"NONE\",\"address\":\"hostA\",\"group\":\"default\"}]}";
    String host2 =
        "{\"descriptors\":[{\"uuid\":\"87465459-9c8f-4f95-b4c6-ef3029030d05\",\"service\":\"NONE\",\"address\":\"hostB\",\"group\":\"default\"}]}";

    String lockPath = Constants.ZMONITOR_LOCK;
    expect(zooReader.getChildren(lockPath, null)).andReturn(List.of(lock1Name, lock2Name));
    expect(zooReader.getData(lockPath + "/" + lock1Name, null, null))
        .andReturn(host1.getBytes(UTF_8));
    expect(zooReader.getData(lockPath + "/" + lock2Name, null, null))
        .andReturn(host2.getBytes(UTF_8));

    replay(zooReader);

    ServiceStatusCmd cmd = new ServiceStatusCmd();
    StatusSummary status = cmd.getMonitorStatus(context);
    LOG.info("monitor status data: {}", status);

    assertEquals(2, status.getServiceCount());

    // expect sorted by name
    Map<String,Set<String>> hostByGroup = new TreeMap<>();
    hostByGroup.put("default", new TreeSet<>(List.of("hostA", "hostB")));

    StatusSummary expected = new StatusSummary(ServiceStatusReport.ReportKey.MONITOR,
        new TreeSet<>(List.of("default")), hostByGroup, 0);

    assertEquals(expected.hashCode(), status.hashCode());
    assertEquals(expected.getDisplayName(), status.getDisplayName());
    assertEquals(expected.getResourceGroups(), status.getResourceGroups());
    assertEquals(expected.getServiceByGroups(), status.getServiceByGroups());
    assertEquals(expected.getServiceCount(), status.getServiceCount());
    assertEquals(expected.getErrorCount(), status.getErrorCount());
    assertEquals(expected, status);
  }

  @Test
  public void testTServerHosts() throws Exception {
    replay(zooReader);
    String lock1Name = "zlock#" + UUID.randomUUID() + "#0000000001";
    String lock2Name = "zlock#" + UUID.randomUUID() + "#0000000002";
    String lock3Name = "zlock#" + UUID.randomUUID() + "#0000000003";

    String host1 = "localhost:9997";
    String host2 = "localhost:10000";
    String host3 = "hostZ:9999";

    String lockData1 =
        "{\"descriptors\":[{\"uuid\":\"e0a717f2-43a1-466c-aa91-8b33e20e17e5\",\"service\":\"TABLET_SCAN\",\"address\":\""
            + host1
            + "\",\"group\":\"default\"},{\"uuid\":\"e0a717f2-43a1-466c-aa91-8b33e20e17e5\",\"service\":\"CLIENT\",\"address\":\""
            + host1
            + "\",\"group\":\"default\"},{\"uuid\":\"e0a717f2-43a1-466c-aa91-8b33e20e17e5\",\"service\":\"TABLET_INGEST\",\"address\":\""
            + host1
            + "\",\"group\":\"default\"},{\"uuid\":\"e0a717f2-43a1-466c-aa91-8b33e20e17e5\",\"service\":\"TABLET_MANAGEMENT\",\"address\":\""
            + host1
            + "\",\"group\":\"default\"},{\"uuid\":\"e0a717f2-43a1-466c-aa91-8b33e20e17e5\",\"service\":\"TSERV\",\"address\":\""
            + host1 + "\",\"group\":\"default\"}]}\n";
    String lockData2 =
        "{\"descriptors\":[{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TABLET_SCAN\",\"address\":\""
            + host2
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TABLET_MANAGEMENT\",\"address\":\""
            + host2
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"CLIENT\",\"address\":\""
            + host2
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TSERV\",\"address\":\""
            + host2
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TABLET_INGEST\",\"address\":\""
            + host2 + "\",\"group\":\"default\"}]}";
    String lockData3 =
        "{\"descriptors\":[{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TABLET_SCAN\",\"address\":\""
            + host3
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TABLET_MANAGEMENT\",\"address\":\""
            + host3
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"CLIENT\",\"address\":\""
            + host3
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TSERV\",\"address\":\""
            + host3
            + "\",\"group\":\"default\"},{\"uuid\":\"d0e29f70-1eb5-4dc5-9ad6-2466ab56ea32\",\"service\":\"TABLET_INGEST\",\"address\":\""
            + host3 + "\",\"group\":\"default\"}]}";

    String basePath = Constants.ZTSERVERS;
    expect(zooCache.getChildren(basePath))
        .andReturn(List.of(Constants.DEFAULT_RESOURCE_GROUP_NAME));
    expect(zooCache.getChildren(basePath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME))
        .andReturn(List.of(host1, host2, host3));

    expect(
        zooCache.getChildren(basePath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host1))
        .andReturn(List.of(lock1Name));
    expect(zooCache.get(
        eq(basePath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host1 + "/" + lock1Name),
        isA(ZcStat.class))).andReturn(lockData1.getBytes(UTF_8));

    expect(
        zooCache.getChildren(basePath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host2))
        .andReturn(List.of(lock2Name));
    expect(zooCache.get(
        eq(basePath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host2 + "/" + lock2Name),
        isA(ZcStat.class))).andReturn(lockData2.getBytes(UTF_8));

    expect(
        zooCache.getChildren(basePath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host3))
        .andReturn(List.of(lock3Name));
    expect(zooCache.get(
        eq(basePath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host3 + "/" + lock3Name),
        isA(ZcStat.class))).andReturn(lockData3.getBytes(UTF_8));

    replay(zooCache);

    ServiceStatusCmd cmd = new ServiceStatusCmd();
    StatusSummary status = cmd.getTServerStatus(context);
    LOG.info("tserver status data: {}", status);

    assertEquals(3, status.getServiceCount());

    // expect sorted by name
    Map<String,Set<String>> hostByGroup = new TreeMap<>();
    hostByGroup.put("default", new TreeSet<>(List.of(host1, host2, host3)));

    StatusSummary expected = new StatusSummary(ServiceStatusReport.ReportKey.T_SERVER,
        Set.of("default"), hostByGroup, 0);

    LOG.info("read: {}", status);
    LOG.info("need: {}", expected);

    assertEquals(expected.hashCode(), status.hashCode());
    assertEquals(expected.getDisplayName(), status.getDisplayName());
    assertEquals(expected.getResourceGroups(), status.getResourceGroups());
    assertEquals(expected.getServiceByGroups(), status.getServiceByGroups());
    assertEquals(expected.getServiceCount(), status.getServiceCount());
    assertEquals(expected.getErrorCount(), status.getErrorCount());
    assertEquals(expected, status);
  }

  @Test
  public void testScanServerHosts() throws Exception {
    replay(zooReader);
    UUID uuid1 = UUID.randomUUID();
    String lock1Name = "zlock#" + uuid1 + "#0000000001";
    UUID uuid2 = UUID.randomUUID();
    String lock2Name = "zlock#" + uuid2 + "#0000000022";
    UUID uuid3 = UUID.randomUUID();
    String lock3Name = "zlock#" + uuid3 + "#0000000033";
    String lock4Name = "zlock#" + uuid3 + "#0000000044";

    String host1 = "host1:8080";
    String host2 = "host2:9090";
    String host3 = "host3:9091";
    String host4 = "host4:9091";

    String lockData1 =
        "{\"descriptors\":[{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"TABLET_SCAN\",\"address\":\""
            + host1
            + "\",\"group\":\"sg1\"},{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"CLIENT\",\"address\":\""
            + host1 + "\",\"group\":\"sg1\"}]}";
    String lockData2 =
        "{\"descriptors\":[{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"TABLET_SCAN\",\"address\":\""
            + host2
            + "\",\"group\":\"default\"},{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"CLIENT\",\"address\":\""
            + host2 + "\",\"group\":\"default\"}]}";
    String lockData3 =
        "{\"descriptors\":[{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"TABLET_SCAN\",\"address\":\""
            + host3
            + "\",\"group\":\"sg1\"},{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"CLIENT\",\"address\":\""
            + host3 + "\",\"group\":\"sg1\"}]}";
    String lockData4 =
        "{\"descriptors\":[{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"TABLET_SCAN\",\"address\":\""
            + host4
            + "\",\"group\":\"default\"},{\"uuid\":\"f408fed7-ce93-40d2-8e60-63e8a3daf416\",\"service\":\"CLIENT\",\"address\":\""
            + host4 + "\",\"group\":\"default\"}]}";

    String lockPath = Constants.ZSSERVERS;
    expect(zooCache.getChildren(lockPath))
        .andReturn(List.of(Constants.DEFAULT_RESOURCE_GROUP_NAME, "sg1"));
    expect(zooCache.getChildren(lockPath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME))
        .andReturn(List.of(host2, host4));
    expect(zooCache.getChildren(lockPath + "/sg1")).andReturn(List.of(host1, host3));

    expect(zooCache.getChildren(lockPath + "/sg1/" + host1)).andReturn(List.of(lock1Name));
    expect(zooCache.get(eq(lockPath + "/sg1/" + host1 + "/" + lock1Name), isA(ZcStat.class)))
        .andReturn(lockData1.getBytes(UTF_8));

    expect(
        zooCache.getChildren(lockPath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host2))
        .andReturn(List.of(lock2Name));
    expect(zooCache.get(
        eq(lockPath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host2 + "/" + lock2Name),
        isA(ZcStat.class))).andReturn(lockData2.getBytes(UTF_8));

    expect(zooCache.getChildren(lockPath + "/sg1/" + host3)).andReturn(List.of(lock3Name));
    expect(zooCache.get(eq(lockPath + "/sg1/" + host3 + "/" + lock3Name), isA(ZcStat.class)))
        .andReturn(lockData3.getBytes(UTF_8));

    expect(
        zooCache.getChildren(lockPath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host4))
        .andReturn(List.of(lock4Name));
    expect(zooCache.get(
        eq(lockPath + "/" + Constants.DEFAULT_RESOURCE_GROUP_NAME + "/" + host4 + "/" + lock4Name),
        isA(ZcStat.class))).andReturn(lockData4.getBytes(UTF_8));

    replay(zooCache);

    ServiceStatusCmd cmd = new ServiceStatusCmd();
    StatusSummary status = cmd.getScanServerStatus(context);
    assertEquals(4, status.getServiceCount());

    Map<String,Set<String>> hostByGroup = new TreeMap<>();
    hostByGroup.put("default", new TreeSet<>(List.of("host2:9090", "host4:9091")));
    hostByGroup.put("sg1", new TreeSet<>(List.of("host1:8080", "host3:9091")));

    StatusSummary expected = new StatusSummary(ServiceStatusReport.ReportKey.S_SERVER,
        Set.of("default", "sg1"), hostByGroup, 0);

    assertEquals(expected, status);

  }

  @Test
  public void testCompactorStatus() throws Exception {
    replay(zooReader);

    UUID uuid1 = UUID.randomUUID();
    String lock1Name = "zlock#" + uuid1 + "#0000000001";
    UUID uuid2 = UUID.randomUUID();
    String lock2Name = "zlock#" + uuid2 + "#0000000022";
    UUID uuid3 = UUID.randomUUID();
    String lock3Name = "zlock#" + uuid3 + "#0000000033";
    UUID uuid4 = UUID.randomUUID();
    String lock4Name = "zlock#" + uuid4 + "#0000000044";

    String lock1data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"COMPACTOR\",\"address\":\"hostA:8080\",\"group\":\"q1\"}]}";
    String lock2data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"COMPACTOR\",\"address\":\"hostC:8081\",\"group\":\"q1\"}]}";
    String lock3data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"COMPACTOR\",\"address\":\"hostB:9090\",\"group\":\"q2\"}]}";
    String lock4data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"COMPACTOR\",\"address\":\"hostD:9091\",\"group\":\"q2\"}]}";

    String lockPath = Constants.ZCOMPACTORS;
    expect(zooCache.getChildren(lockPath)).andReturn(List.of("q1", "q2", "q3"));
    expect(zooCache.getChildren(lockPath + "/q1")).andReturn(List.of("hostA:8080", "hostC:8081"));
    expect(zooCache.getChildren(lockPath + "/q2")).andReturn(List.of("hostB:9090", "hostD:9091"));
    // Create compactor group with dead compactor
    expect(zooCache.getChildren(lockPath + "/q3")).andReturn(List.of("deadHost:8080"));

    expect(zooCache.getChildren(lockPath + "/q1/hostA:8080")).andReturn(List.of(lock1Name));
    expect(zooCache.get(eq(lockPath + "/q1/hostA:8080/" + lock1Name), anyObject(ZcStat.class)))
        .andReturn(lock1data.getBytes(UTF_8));
    expect(zooCache.getChildren(lockPath + "/q1/hostC:8081")).andReturn(List.of(lock2Name));
    expect(zooCache.get(eq(lockPath + "/q1/hostC:8081/" + lock2Name), anyObject(ZcStat.class)))
        .andReturn(lock2data.getBytes(UTF_8));
    expect(zooCache.getChildren(lockPath + "/q2/hostB:9090")).andReturn(List.of(lock3Name));
    expect(zooCache.get(eq(lockPath + "/q2/hostB:9090/" + lock3Name), anyObject(ZcStat.class)))
        .andReturn(lock3data.getBytes(UTF_8));
    expect(zooCache.getChildren(lockPath + "/q2/hostD:9091")).andReturn(List.of(lock4Name));
    expect(zooCache.get(eq(lockPath + "/q2/hostD:9091/" + lock4Name), anyObject(ZcStat.class)))
        .andReturn(lock4data.getBytes(UTF_8));
    expect(zooCache.getChildren(lockPath + "/q3/deadHost:8080")).andReturn(List.of());

    replay(zooCache);

    ServiceStatusCmd cmd = new ServiceStatusCmd();
    StatusSummary status = cmd.getCompactorStatus(context);

    LOG.info("compactor group counts: {}", status);
    assertEquals(2, status.getResourceGroups().size());

    LOG.info("Live compactor counts: {}", status.getServiceCount());
    assertEquals(4, status.getServiceCount());
  }

  @Test
  public void testGcHosts() throws Exception {
    replay(zooCache);

    String lockPath = ZGC_LOCK;
    UUID uuid1 = UUID.randomUUID();
    String lock1Name = "zlock#" + uuid1 + "#0000000001";
    UUID uuid2 = UUID.randomUUID();
    String lock2Name = "zlock#" + uuid2 + "#0000000022";

    String host1 = "host1:8080";
    String host2 = "host2:9090";

    String lockData1 =
        "{\"descriptors\":[{\"uuid\":\"5c901352-b027-4f78-8ee1-05ae163fbb0e\",\"service\":\"GC\",\"address\":\""
            + host2 + "\",\"group\":\"default\"}]}";
    String lockData2 =
        "{\"descriptors\":[{\"uuid\":\"5c901352-b027-4f78-8ee1-05ae163fbb0e\",\"service\":\"GC\",\"address\":\""
            + host1 + "\",\"group\":\"gc1\"}]}";

    expect(zooReader.getChildren(lockPath, null)).andReturn(List.of(lock1Name, lock2Name));
    expect(zooReader.getData(lockPath + "/" + lock1Name, null, null))
        .andReturn(lockData1.getBytes(UTF_8));
    expect(zooReader.getData(lockPath + "/" + lock2Name, null, null))
        .andReturn(lockData2.getBytes(UTF_8));

    replay(zooReader);

    ServiceStatusCmd cmd = new ServiceStatusCmd();
    StatusSummary status = cmd.getGcStatus(context);
    LOG.info("gc server counts: {}", status);
    assertEquals(2, status.getResourceGroups().size());
    assertEquals(2, status.getServiceCount());
    assertEquals(0, status.getErrorCount());
    assertEquals(2, status.getServiceByGroups().size());
    assertEquals(1, status.getServiceByGroups().get("default").size());
    assertEquals(1, status.getServiceByGroups().get("gc1").size());
    assertEquals(new TreeSet<>(List.of("default", "gc1")), status.getResourceGroups());
    assertEquals(new TreeSet<>(List.of(host1)), status.getServiceByGroups().get("gc1"));
    assertEquals(new TreeSet<>(List.of(host2)), status.getServiceByGroups().get("default"));
  }

  /**
   * Simulates node being deleted after lock list is read from ZooKeeper. Expect that the no node
   * error is skipped and available hosts are returned.
   */
  @Test
  public void zkNodeDeletedTest() throws Exception {
    replay(zooCache);
    String lock1Name = "zlock#" + UUID.randomUUID() + "#0000000001";
    String lock2Name = "zlock#" + UUID.randomUUID() + "#0000000002";
    String lock3Name = "zlock#" + UUID.randomUUID() + "#0000000003";

    String host2 = "localhost:9992";
    String host3 = "hostA:9999";

    String lock2Data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"MANAGER\",\"address\":\""
            + host2 + "\",\"group\":\"default\"}]}";
    String lock3Data =
        "{\"descriptors\":[{\"uuid\":\"6effb690-c29c-4e0b-92ff-f6b308385a42\",\"service\":\"MANAGER\",\"address\":\""
            + host3 + "\",\"group\":\"manager1\"}]}";

    String lockPath = Constants.ZMANAGER_LOCK;
    expect(zooReader.getChildren(lockPath, null))
        .andReturn(List.of(lock1Name, lock2Name, lock3Name));
    expect(zooReader.getData(lockPath + "/" + lock1Name, null, null))
        .andThrow(new KeeperException.NoNodeException("no node forced exception"));
    expect(zooReader.getData(lockPath + "/" + lock2Name, null, null))
        .andReturn(lock2Data.getBytes(UTF_8));
    expect(zooReader.getData(lockPath + "/" + lock3Name, null, null))
        .andReturn(lock3Data.getBytes(UTF_8));

    replay(zooReader);

    ServiceStatusCmd cmd = new ServiceStatusCmd();
    StatusSummary status = cmd.getManagerStatus(context);
    LOG.info("manager status data: {}", status);

    assertEquals(2, status.getServiceByGroups().size());
    assertEquals(1, status.getServiceByGroups().get("default").size());
    assertEquals(1, status.getServiceByGroups().get("manager1").size());
    assertEquals(1, status.getErrorCount());

    // host 1 missing - no node exception
    assertEquals(new TreeSet<>(List.of(host2)), status.getServiceByGroups().get("default"));
    assertEquals(new TreeSet<>(List.of(host3)), status.getServiceByGroups().get("manager1"));
  }

  @Test
  public void testServiceStatusCommandOpts() {
    replay(zooReader, zooCache);
    Admin.ServiceStatusCmdOpts opts = new Admin.ServiceStatusCmdOpts();
    assertFalse(opts.json);
    assertFalse(opts.showHosts);
  }

}
