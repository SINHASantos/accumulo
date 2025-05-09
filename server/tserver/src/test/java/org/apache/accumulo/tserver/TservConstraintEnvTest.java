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
package org.apache.accumulo.tserver;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.securityImpl.thrift.TCredentials;
import org.apache.accumulo.server.ServerContext;
import org.apache.accumulo.server.security.AuditedSecurityOperation;
import org.junit.jupiter.api.Test;

public class TservConstraintEnvTest {

  @Test
  public void testGetAuthorizationsContainer() {
    ServerContext context = createMock(ServerContext.class);
    AuditedSecurityOperation security = createMock(AuditedSecurityOperation.class);
    TCredentials goodCred = createMock(TCredentials.class);
    TCredentials badCred = createMock(TCredentials.class);

    ByteSequence bs = new ArrayByteSequence("foo".getBytes(UTF_8));
    List<ByteBuffer> bbList =
        Collections.singletonList(ByteBuffer.wrap(bs.getBackingArray(), bs.offset(), bs.length()));

    expect(security.authenticatedUserHasAuthorizations(goodCred, bbList)).andReturn(true);
    expect(security.authenticatedUserHasAuthorizations(badCred, bbList)).andReturn(false);
    expect(context.getSecurityOperation()).andReturn(security).atLeastOnce();
    replay(context, security, goodCred, badCred);

    assertTrue(new TservConstraintEnv(context, goodCred).getAuthorizationsContainer().contains(bs));
    assertFalse(new TservConstraintEnv(context, badCred).getAuthorizationsContainer().contains(bs));
    verify(context, security, goodCred, badCred);
  }
}
