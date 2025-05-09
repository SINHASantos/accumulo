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
package org.apache.accumulo.server.constraints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.admin.TabletAvailability;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.dataImpl.KeyExtent;
import org.apache.accumulo.core.fate.FateId;
import org.apache.accumulo.core.fate.FateInstanceType;
import org.apache.accumulo.core.metadata.ReferencedTabletFile;
import org.apache.accumulo.core.metadata.StoredTabletFile;
import org.apache.accumulo.core.metadata.SuspendingTServer;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.metadata.TServerInstance;
import org.apache.accumulo.core.metadata.schema.DataFileValue;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.BulkFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.CompactedColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.CurrentLocationColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.DataFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.ScanFileColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.ServerColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.SplitColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.SuspendLocationColumn;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.TabletColumnFamily;
import org.apache.accumulo.core.metadata.schema.MetadataSchema.TabletsSection.UserCompactionRequestedColumnFamily;
import org.apache.accumulo.core.metadata.schema.SelectedFiles;
import org.apache.accumulo.core.metadata.schema.TabletMergeabilityMetadata;
import org.apache.accumulo.core.metadata.schema.UnSplittableMetadata;
import org.apache.accumulo.core.util.time.SteadyTime;
import org.apache.accumulo.server.ServerContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.easymock.EasyMock;
import org.junit.jupiter.api.Test;

import com.google.common.net.HostAndPort;

public class MetadataConstraintsTest {

  private SystemEnvironment createEnv() {
    SystemEnvironment env = EasyMock.createMock(SystemEnvironment.class);
    ServerContext context = EasyMock.createMock(ServerContext.class);
    EasyMock.expect(env.getServerContext()).andReturn(context);
    EasyMock.replay(env);
    return env;
  }

  @Test
  public void testCheck() {
    Mutation m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.PREV_ROW_COLUMN.put(m, new Value("1foo"));

    MetadataConstraints mc = new MetadataConstraints();

    List<Short> violations = mc.check(createEnv(), m);

    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 3), violations.get(0));

    m = new Mutation(new Text("0:foo"));
    TabletColumnFamily.PREV_ROW_COLUMN.put(m, new Value("1poo"));

    violations = mc.check(createEnv(), m);

    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 4), violations.get(0));

    m = new Mutation(new Text("0;foo"));
    m.put("bad_column_name", "", "e");

    violations = mc.check(createEnv(), m);

    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 2), violations.get(0));

    m = new Mutation(new Text("!!<"));
    TabletColumnFamily.PREV_ROW_COLUMN.put(m, new Value("1poo"));

    violations = mc.check(createEnv(), m);

    assertFalse(violations.isEmpty());
    assertEquals(2, violations.size());
    assertEquals(Short.valueOf((short) 4), violations.get(0));
    assertEquals(Short.valueOf((short) 5), violations.get(1));

    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.PREV_ROW_COLUMN.put(m, new Value(""));

    violations = mc.check(createEnv(), m);

    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 6), violations.get(0));

    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.PREV_ROW_COLUMN.put(m, new Value("bar"));

    violations = mc.check(createEnv(), m);

    assertTrue(violations.isEmpty());

    m = new Mutation(new Text(SystemTables.METADATA.tableId().canonical() + "<"));
    TabletColumnFamily.PREV_ROW_COLUMN.put(m, new Value("bar"));

    violations = mc.check(createEnv(), m);

    assertTrue(violations.isEmpty());

    m = new Mutation(new Text("!1<"));
    TabletColumnFamily.PREV_ROW_COLUMN.put(m, new Value("bar"));

    violations = mc.check(createEnv(), m);

    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 4), violations.get(0));

  }

  @Test
  public void testSuspensionCheck() {
    Mutation m = new Mutation(new Text("0;foo"));
    MetadataConstraints mc = new MetadataConstraints();
    TServerInstance ser1 = new TServerInstance(HostAndPort.fromParts("server1", 8555), "s001");

    SuspendLocationColumn.SUSPEND_COLUMN.put(m, SuspendingTServer.toValue(ser1,
        SteadyTime.from(System.currentTimeMillis(), TimeUnit.MILLISECONDS)));
    List<Short> violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    m = new Mutation(new Text("0;foo"));
    SuspendLocationColumn.SUSPEND_COLUMN.put(m,
        SuspendingTServer.toValue(ser1, SteadyTime.from(0, TimeUnit.MILLISECONDS)));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    m = new Mutation(new Text("0;foo"));
    // We must encode manually since SteadyTime won't allow a negative
    SuspendLocationColumn.SUSPEND_COLUMN.put(m, new Value(ser1.getHostPort() + "|" + -1L));
    violations = mc.check(createEnv(), m);
    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 3101), violations.get(0));
  }

  @Test
  public void testBulkFileCheck() {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;
    FateId fateId1 = FateId.from(FateInstanceType.META, UUID.randomUUID());
    FateId fateId2 = FateId.from(FateInstanceType.META, UUID.randomUUID());

    // loaded marker w/ file
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    m.put(
        DataFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    // loaded marker w/o file
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 8);

    // two files w/ same txid
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    m.put(
        DataFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile2")).getMetadataText(),
        new Value(fateId1.canonical()));
    m.put(
        DataFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile2")).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    // two files w/ different txid
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    m.put(
        DataFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile2")).getMetadataText(),
        new Value(fateId2.canonical()));
    m.put(
        DataFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile2")).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    assertViolation(mc, m, (short) 8);

    // two loaded markers but only one file.
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    m.put(
        DataFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile2")).getMetadataText(),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 8);

    // mutation that looks like split
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    ServerColumnFamily.DIRECTORY_COLUMN.put(m, new Value("t-000009x"));
    // Missing split column, should fail
    assertViolation(mc, m, (short) 8);

    // mutation that looks like split
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    ServerColumnFamily.DIRECTORY_COLUMN.put(m, new Value("t-000009x"));
    // Add opid column
    ServerColumnFamily.OPID_COLUMN.put(m,
        new Value("SPLITTING:FATE:META:12345678-9abc-def1-2345-6789abcdef12"));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    // mutation that looks like a load
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    m.put(CurrentLocationColumnFamily.NAME, new Text("789"), new Value("127.0.0.1:9997"));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    // deleting a load flag
    m = new Mutation(new Text("0;foo"));
    m.putDelete(BulkFileColumnFamily.NAME, StoredTabletFile
        .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText());
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    // Missing beginning of path
    m = new Mutation(new Text("0;foo"));
    m.put(BulkFileColumnFamily.NAME,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"))
            .getMetadata()
            .replace("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile", "/someFile")),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    // Missing tables directory in path
    m = new Mutation(new Text("0;foo"));
    m.put(BulkFileColumnFamily.NAME,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"))
            .getMetadata().replace("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile",
                "hdfs://1.2.3.4/accumulo/2a/t-0003/someFile")),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 8);

    // Bad Json - only path (old format) so should fail parsing
    m = new Mutation(new Text("0;foo"));
    m.put(BulkFileColumnFamily.NAME, new Text("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test startRow key is missing so validation should fail
    // {"path":"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(BulkFileColumnFamily.NAME,
        new Text(
            "{\"path\":\"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile\",\"endRow\":\"\"}"),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test path key replaced with empty string so validation should fail
    // {"":"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile","startRow":"","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, new Text(StoredTabletFile
            .serialize("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile").replace("path", "")),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test path value missing
    // {"path":"","startRow":"","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(BulkFileColumnFamily.NAME,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"))
            .getMetadata().replaceFirst("\"path\":\".*\",\"startRow", "\"path\":\"\",\"startRow")),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test startRow key replaced with empty string so validation should fail
    // {"path":"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile","":"","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(BulkFileColumnFamily.NAME, new Text(StoredTabletFile
        .serialize("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile").replace("startRow", "")),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test endRow key missing so validation should fail
    m = new Mutation(new Text("0;foo"));
    m.put(
        BulkFileColumnFamily.NAME, new Text(StoredTabletFile
            .serialize("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile").replace("endRow", "")),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

    // Bad Json - endRow will be replaced with encoded row without the exclusive byte 0x00 which is
    // required for an endRow so will fail validation
    m = new Mutation(new Text("0;foo"));
    m.put(BulkFileColumnFamily.NAME,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"),
            new Range("a", false, "b", true)).getMetadata().replaceFirst("\"endRow\":\".*\"",
                "\"endRow\":\"" + encodeRowForMetadata("bad") + "\"")),
        new Value(fateId1.canonical()));
    assertViolation(mc, m, (short) 3100);

  }

  @Test
  public void testDataFileCheck() {
    testFileMetadataValidation(DataFileColumnFamily.NAME, new DataFileValue(1, 1).encodeAsValue());
  }

  @Test
  public void testScanFileCheck() {
    testFileMetadataValidation(ScanFileColumnFamily.NAME, new Value());
  }

  private void testFileMetadataValidation(Text columnFamily, Value value) {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;

    // Missing beginning of path
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"))
            .getMetadata()
            .replace("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile", "/someFile")),
        value);
    assertViolation(mc, m, (short) 3100);

    // Bad Json - only path (old format) so should fail parsing
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily, new Text("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"), value);
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test path key replaced with empty string so validation should fail
    // {"":"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile","startRow":"","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(
        columnFamily, new Text(StoredTabletFile
            .serialize("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile").replace("path", "")),
        value);
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test path value missing
    // {"path":"","startRow":"","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"))
            .getMetadata().replaceFirst("\"path\":\".*\",\"startRow", "\"path\":\"\",\"startRow")),
        value);
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test startRow key replaced with empty string so validation should fail
    // {"path":"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile","":"","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily, new Text(StoredTabletFile
        .serialize("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile").replace("startRow", "")),
        value);
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test startRow key is missing so validation should fail
    // {"path":"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily,
        new Text(
            "{\"path\":\"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile\",\"endRow\":\"\"}"),
        value);
    assertViolation(mc, m, (short) 3100);

    // Bad Json - test endRow key replaced with empty string so validation should fail
    // {"path":"hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile","":"","endRow":""}
    m = new Mutation(new Text("0;foo"));
    m.put(
        columnFamily, new Text(StoredTabletFile
            .serialize("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile").replace("endRow", "")),
        value);
    assertViolation(mc, m, (short) 3100);

    // Bad Json - endRow will be replaced with encoded row without the exclusive byte 0x00 which is
    // required for an endRow so this will fail validation
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"),
            new Range("a", false, "b", true)).getMetadata()
            .replaceFirst("\"endRow\":\".*\"", "\"endRow\":\"" + encodeRowForMetadata("b") + "\"")),
        value);
    assertViolation(mc, m, (short) 3100);

    // Missing tables directory in path
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily,
        new Text(StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"))
            .getMetadata().replace("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile",
                "hdfs://1.2.3.4/accumulo/2a/t-0003/someFile")),
        new DataFileValue(1, 1).encodeAsValue());
    assertViolation(mc, m, (short) 3100);

    // Should pass validation (inf range)
    m = new Mutation(new Text("0;foo"));
    m.put(
        columnFamily, StoredTabletFile
            .of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile")).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    // Should pass validation with range set
    m = new Mutation(new Text("0;foo"));
    m.put(columnFamily,
        StoredTabletFile.of(new Path("hdfs://1.2.3.4/accumulo/tables/2a/t-0003/someFile"),
            new Range("a", false, "b", true)).getMetadataText(),
        new DataFileValue(1, 1).encodeAsValue());
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    assertNotNull(mc.getViolationDescription((short) 3100));
  }

  @Test
  public void testOperationId() {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;

    m = new Mutation(new Text("0;foo"));
    ServerColumnFamily.OPID_COLUMN.put(m, new Value("bad id"));
    assertViolation(mc, m, (short) 4000);

    m = new Mutation(new Text("0;foo"));
    ServerColumnFamily.OPID_COLUMN.put(m,
        new Value("MERGING:FATE:META:12345678-9abc-def1-2345-6789abcdef12"));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());
  }

  @Test
  public void testSelectedFiles() {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;
    FateId fateId = FateId.from(FateInstanceType.META, UUID.randomUUID());

    m = new Mutation(new Text("0;foo"));
    ServerColumnFamily.SELECTED_COLUMN.put(m, new Value("bad id"));
    violations = mc.check(createEnv(), m);
    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 4001), violations.get(0));

    m = new Mutation(new Text("0;foo"));
    ServerColumnFamily.SELECTED_COLUMN.put(m,
        new Value(new SelectedFiles(Set.of(new ReferencedTabletFile(
            new Path("hdfs://nn.somewhere.com:86753/accumulo/tables/42/t-0000/F00001.rf"))
            .insert()), true, fateId, SteadyTime.from(100, TimeUnit.NANOSECONDS))
            .getMetadataValue()));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());
  }

  @Test
  public void testCompacted() {
    testFateCqValidation(CompactedColumnFamily.STR_NAME, (short) 4002);
  }

  @Test
  public void testUserCompactionRequested() {
    testFateCqValidation(UserCompactionRequestedColumnFamily.STR_NAME, (short) 4003);
  }

  // Verify that columns that store a FateId in their CQ
  // validate and only allow a correctly formatted FateId
  private void testFateCqValidation(String column, short violation) {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;
    FateId fateId = FateId.from(FateInstanceType.META, UUID.randomUUID());

    m = new Mutation(new Text("0;foo"));
    m.put(column, fateId.canonical(), "");
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    m = new Mutation(new Text("0;foo"));
    m.put(column, "incorrect data", "");
    violations = mc.check(createEnv(), m);
    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(violation, violations.get(0));
  }

  @Test
  public void testUnsplittableColumn() {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;

    StoredTabletFile sf1 = StoredTabletFile.of(new Path("hdfs://nn1/acc/tables/1/t-0001/sf1.rf"));
    var unsplittableMeta = UnSplittableMetadata
        .toUnSplittable(KeyExtent.fromMetaRow(new Text("0;foo")), 100, 110, 120, Set.of(sf1));

    m = new Mutation(new Text("0;foo"));
    SplitColumnFamily.UNSPLITTABLE_COLUMN.put(m, new Value(unsplittableMeta.toBase64()));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    // Verify empty value not allowed
    m = new Mutation(new Text("0;foo"));
    SplitColumnFamily.UNSPLITTABLE_COLUMN.put(m, new Value());
    violations = mc.check(createEnv(), m);
    assertFalse(violations.isEmpty());
    assertEquals(2, violations.size());
    assertIterableEquals(List.of((short) 6, (short) 4004), violations);

    // test invalid args
    KeyExtent extent = KeyExtent.fromMetaRow(new Text("0;foo"));
    assertThrows(IllegalArgumentException.class,
        () -> UnSplittableMetadata.toUnSplittable(extent, -100, 110, 120, Set.of(sf1)));
    assertThrows(IllegalArgumentException.class,
        () -> UnSplittableMetadata.toUnSplittable(extent, 100, -110, 120, Set.of(sf1)));
    assertThrows(IllegalArgumentException.class,
        () -> UnSplittableMetadata.toUnSplittable(extent, 100, 110, -120, Set.of(sf1)));
    assertThrows(NullPointerException.class,
        () -> UnSplittableMetadata.toUnSplittable(extent, 100, 110, 120, null));

    // Test metadata constraints validate invalid hashcode
    m = new Mutation(new Text("0;foo"));
    unsplittableMeta = UnSplittableMetadata.toUnSplittable(extent, 100, 110, 120, Set.of(sf1));
    // partial hashcode is invalid
    var invalidHashCode =
        unsplittableMeta.toBase64().substring(0, unsplittableMeta.toBase64().length() - 1);
    SplitColumnFamily.UNSPLITTABLE_COLUMN.put(m, new Value(invalidHashCode));
    violations = mc.check(createEnv(), m);
    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(Short.valueOf((short) 4004), violations.get(0));
  }

  @Test
  public void testDirectoryColumn() {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;
    m = new Mutation(new Text("0;foo"));
    ServerColumnFamily.DIRECTORY_COLUMN.put(m, new Value("t-000009x"));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    m = new Mutation(new Text("0;foo"));
    m.put(ServerColumnFamily.DIRECTORY_COLUMN.getColumnFamily(),
        ServerColumnFamily.DIRECTORY_COLUMN.getColumnQualifier(), new Value("/invalid"));
    violations = mc.check(createEnv(), m);
    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals((short) 3102, violations.get(0));
  }

  @Test
  public void testMergeabilityColumn() {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;

    // Delay must be >= 0
    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.MERGEABILITY_COLUMN.put(m,
        new Value("{\"delay\":-1,\"steadyTime\":1,\"never\"=false}"));
    assertViolation(mc, m, (short) 4006);

    // SteadyTime must be null if never is true
    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.MERGEABILITY_COLUMN.put(m, new Value("{\"steadyTime\":1,\"never\"=true}"));
    assertViolation(mc, m, (short) 4006);

    // delay must be null if never is true
    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.MERGEABILITY_COLUMN.put(m, new Value("{\"delay\":1,\"never\"=true}"));
    assertViolation(mc, m, (short) 4006);

    // SteadyTime must be set if delay is set
    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.MERGEABILITY_COLUMN.put(m, new Value("{\"delay\":10,\"never\"=false}"));
    assertViolation(mc, m, (short) 4006);

    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.MERGEABILITY_COLUMN.put(m,
        TabletMergeabilityMetadata.toValue(TabletMergeabilityMetadata.never()));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.MERGEABILITY_COLUMN.put(m, TabletMergeabilityMetadata
        .toValue(TabletMergeabilityMetadata.always(SteadyTime.from(1, TimeUnit.SECONDS))));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());

    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.MERGEABILITY_COLUMN.put(m,
        TabletMergeabilityMetadata.toValue(TabletMergeabilityMetadata.after(Duration.ofDays(3),
            SteadyTime.from(Duration.ofHours(1)))));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());
  }

  @Test
  public void testAvailabilityColumn() {
    MetadataConstraints mc = new MetadataConstraints();
    Mutation m;
    List<Short> violations;

    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.AVAILABILITY_COLUMN.put(m, new Value("INVALID"));
    assertViolation(mc, m, (short) 4005);

    m = new Mutation(new Text("0;foo"));
    TabletColumnFamily.AVAILABILITY_COLUMN.put(m, new Value(TabletAvailability.UNHOSTED.name()));
    violations = mc.check(createEnv(), m);
    assertTrue(violations.isEmpty());
  }

  // Encode a row how it would appear in Json
  private static String encodeRowForMetadata(String row) {
    try {
      Method method = StoredTabletFile.class.getDeclaredMethod("encodeRow", Key.class);
      method.setAccessible(true);
      return Base64.getUrlEncoder()
          .encodeToString((byte[]) method.invoke(StoredTabletFile.class, new Key(row)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void assertViolation(MetadataConstraints mc, Mutation m, Short violation) {
    List<Short> violations = mc.check(createEnv(), m);
    assertFalse(violations.isEmpty());
    assertEquals(1, violations.size());
    assertEquals(violation, violations.get(0));
    assertNotNull(mc.getViolationDescription(violations.get(0)));
  }

}
