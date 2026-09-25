/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.internal.shadowing;

import static com.uber.cadence.converter.JacksonDataConverterTest.newCustomizedConverter;
import static org.junit.Assert.assertEquals;

import com.google.common.base.Splitter;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JacksonDataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * The shadowing activity heartbeats its progress with the data converter of the worker and reads it
 * back when it is retried, so every converter has to be able to decode the heartbeat details.
 */
@RunWith(Parameterized.class)
public class ReplayWorkflowActivityImplHeartbeatTest {

  private static final String DETAILS =
      "{\"replayResult\":{\"succeeded\":3,\"skipped\":1,\"failed\":2},\"replayExecutionIndex\":7}";

  private final DataConverter converter;

  public ReplayWorkflowActivityImplHeartbeatTest(String name, DataConverter converter) {
    this.converter = converter;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> converters() {
    List<Object[]> result = new ArrayList<>();
    for (String name :
        Splitter.on(',')
            .trimResults()
            .omitEmptyStrings()
            .split(System.getProperty("cadence.test.converters", "gson,jackson"))) {
      switch (name) {
        case "gson":
          result.add(new Object[] {name, JsonDataConverter.getInstance()});
          break;
        case "jackson":
          result.add(new Object[] {name, JacksonDataConverter.getInstance()});
          result.add(new Object[] {"jackson-customized", newCustomizedConverter()});
          break;
        default:
          throw new IllegalArgumentException("Unknown converter: " + name);
      }
    }
    return result;
  }

  @Test
  public void testHeartbeatDetailRoundTrip() throws Exception {
    Class<?> detailClass = heartbeatDetailClass();
    ReplayWorkflowActivityResult progress = new ReplayWorkflowActivityResult();
    progress.setSucceeded(3);
    progress.setSkipped(1);
    progress.setFailed(2);
    Constructor<?> constructor =
        detailClass.getDeclaredConstructor(ReplayWorkflowActivityResult.class, int.class);
    constructor.setAccessible(true);
    Object detail = constructor.newInstance(progress, 7);

    Object decoded = converter.fromData(converter.toData(detail), detailClass, detailClass);

    assertProgress(decoded);
  }

  @Test
  public void testDecodesHeartbeatDetail() throws Exception {
    Class<?> detailClass = heartbeatDetailClass();

    Object decoded =
        converter.fromData(DETAILS.getBytes(StandardCharsets.UTF_8), detailClass, detailClass);

    assertProgress(decoded);
  }

  /** Details with properties this version does not know, as a newer client writes. */
  @Test
  public void testUnknownPropertiesOfHeartbeatDetailAreIgnored() throws Exception {
    String unknown = "unknown" + UUID.randomUUID().toString().replace("-", "");
    Class<?> detailClass = heartbeatDetailClass();
    byte[] details =
        ("{\"replayResult\":{\"succeeded\":3,\"skipped\":1,\"failed\":2,\""
                + unknown
                + "\":0},\"replayExecutionIndex\":7,\""
                + unknown
                + "\":\"x\"}")
            .getBytes(StandardCharsets.UTF_8);

    assertProgress(converter.fromData(details, detailClass, detailClass));
  }

  /**
   * The shadowing workflow, which the Cadence server runs, passes the parameters of the activity
   * and reads its result.
   */
  @Test
  public void testActivityParametersWrittenByShadowingWorkflow() {
    String unknown = "unknown" + UUID.randomUUID().toString().replace("-", "");
    byte[] input =
        ("{\"domain\":\"samples\",\"executions\":[{\"workflowId\":\"w-1\",\"runId\":\"r-1\",\""
                + unknown
                + "\":1}],\""
                + unknown
                + "\":{\"a\":[]}}")
            .getBytes(StandardCharsets.UTF_8);

    ReplayWorkflowActivityParams params =
        (ReplayWorkflowActivityParams)
            converter.fromDataArray(input, ReplayWorkflowActivityParams.class)[0];
    assertEquals("samples", params.getDomain());
    assertEquals(1, params.getExecutions().size());
    assertEquals("w-1", params.getExecutions().get(0).getWorkflowId());
    assertEquals("r-1", params.getExecutions().get(0).getRunId());

    ReplayWorkflowActivityResult result = new ReplayWorkflowActivityResult();
    result.setSucceeded(3);
    result.setSkipped(1);
    result.setFailed(2);
    assertEquals(
        "{\"succeeded\":3,\"skipped\":1,\"failed\":2}",
        new String(converter.toData(result), StandardCharsets.UTF_8));
  }

  private static Class<?> heartbeatDetailClass() throws ClassNotFoundException {
    return Class.forName(ReplayWorkflowActivityImpl.class.getName() + "$HeartbeatDetail");
  }

  private static void assertProgress(Object detail) throws Exception {
    ReplayWorkflowActivityResult progress =
        (ReplayWorkflowActivityResult) invoke(detail, "getReplayResult");
    assertEquals(3, progress.getSucceeded());
    assertEquals(1, progress.getSkipped());
    assertEquals(2, progress.getFailed());
    assertEquals(Integer.valueOf(7), invoke(detail, "getReplayExecutionIndex"));
  }

  private static Object invoke(Object target, String methodName) throws Exception {
    Method method = target.getClass().getDeclaredMethod(methodName);
    method.setAccessible(true);
    return method.invoke(target);
  }
}
