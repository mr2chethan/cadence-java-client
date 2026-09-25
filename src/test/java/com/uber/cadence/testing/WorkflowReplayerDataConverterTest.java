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

package com.uber.cadence.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import com.uber.cadence.HistoryEvent;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.common.WorkflowExecutionHistory;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JacksonDataConverter;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.testUtils.TestEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;

/**
 * Replays histories whose payloads only the data converter that recorded them reads: the LocalDate
 * that JacksonDataConverter writes as "2024-02-29" is not read by JsonDataConverter, which the
 * methods of WorkflowReplayer without a data converter use.
 */
public class WorkflowReplayerDataConverterTest {

  static final String TASK_LIST = "WorkflowReplayerDataConverterTest";
  /** Recorded with JacksonDataConverter. */
  static final String HISTORY_RESOURCE = "testJacksonLocalDateHistory.json";

  private static final String EXPECTED_RESULT = "2024-02-29 -> 2024-03-01";

  private final List<TestWorkflowEnvironment> environments = new ArrayList<>();

  // Closes the environments outside of the timeout, so also after a test that timed out.
  @Rule
  public final RuleChain rules =
      RuleChain.outerRule(
              new ExternalResource() {
                @Override
                protected void after() {
                  for (TestWorkflowEnvironment environment : environments) {
                    environment.close();
                  }
                }
              })
          .around(Timeout.seconds(60));

  @Before
  public void setUp() {
    assumeFalse("Uses the in-memory test service", TestEnvironment.isUseDockerService());
  }

  @Test
  public void testReplayWithDataConverter() throws Exception {
    TestWorkflowEnvironment environment = newEnvironment(JacksonDataConverter.getInstance());
    environments.add(environment);
    WorkflowExecutionHistory history = record(environment);

    WorkflowReplayer.replayWorkflowExecution(
        history, JacksonDataConverter.getInstance(), DateWorkflowImpl.class);
    assertReplayFails(
        () -> WorkflowReplayer.replayWorkflowExecution(history, DateWorkflowImpl.class));
  }

  @Test
  public void testReplayFromResourceWithDataConverter() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        HISTORY_RESOURCE, JacksonDataConverter.getInstance(), DateWorkflowImpl.class);
    assertReplayFails(
        () ->
            WorkflowReplayer.replayWorkflowExecutionFromResource(
                HISTORY_RESOURCE, DateWorkflowImpl.class));
  }

  /** The environment of each replay is closed, also when the replay fails. */
  @Test
  public void testReplayClosesItsEnvironment() throws Exception {
    int before = liveTimerPumps();
    for (int i = 0; i < 3; i++) {
      WorkflowReplayer.replayWorkflowExecutionFromResource(
          HISTORY_RESOURCE, JacksonDataConverter.getInstance(), DateWorkflowImpl.class);
      assertReplayFails(
          () ->
              WorkflowReplayer.replayWorkflowExecutionFromResource(
                  HISTORY_RESOURCE, DateWorkflowImpl.class));
    }
    int after = liveTimerPumps();
    assertTrue(before + " timer threads before the replays, " + after + " after", after <= before);
  }

  /** Each environment has one, which closing the environment stops. */
  private static int liveTimerPumps() {
    int count = 0;
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && thread.getName().equals("SelfAdvancingTimer Pump")) {
        count++;
      }
    }
    return count;
  }

  /**
   * The workflow fails to decode its input, so it does not schedule the activity that the history
   * holds, which the replay reports as nondeterminism.
   */
  private static void assertReplayFails(ThrowingRunnable replay) {
    RuntimeException e = assertThrows(RuntimeException.class, replay);
    assertTrue(e.getMessage(), e.getMessage().contains("nondeterministic"));
  }

  static TestWorkflowEnvironment newEnvironment(DataConverter converter) {
    return TestWorkflowEnvironment.newInstance(
        new TestEnvironmentOptions.Builder()
            .setDataConverter(converter)
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setDataConverter(converter).build())
            .build());
  }

  /** Runs the workflow in the environment and returns its history. */
  static WorkflowExecutionHistory record(TestWorkflowEnvironment environment)
      throws TimeoutException {
    Worker worker = environment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(DateWorkflowImpl.class);
    worker.registerActivitiesImplementations(new DateActivitiesImpl());
    environment.start();
    DateWorkflow workflow = environment.newWorkflowClient().newWorkflowStub(DateWorkflow.class);
    WorkflowClient.start(workflow::run, LocalDate.of(2024, 2, 29));
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    assertEquals(EXPECTED_RESULT, stub.getResult(30, TimeUnit.SECONDS, String.class));
    List<HistoryEvent> events = new ArrayList<>();
    Iterator<HistoryEvent> iterator =
        WorkflowExecutionUtils.getHistory(
            environment.getWorkflowService(), environment.getDomain(), stub.getExecution());
    while (iterator.hasNext()) {
      events.add(iterator.next());
    }
    return new WorkflowExecutionHistory(events);
  }

  public interface DateActivities {
    LocalDate nextDay(LocalDate day);
  }

  public static class DateActivitiesImpl implements DateActivities {
    @Override
    public LocalDate nextDay(LocalDate day) {
      return day.plusDays(1);
    }
  }

  public interface DateWorkflow {
    @WorkflowMethod(executionStartToCloseTimeoutSeconds = 3600, taskList = TASK_LIST)
    String run(LocalDate day);
  }

  public static class DateWorkflowImpl implements DateWorkflow {
    @Override
    public String run(LocalDate day) {
      DateActivities activities =
          Workflow.newActivityStub(
              DateActivities.class,
              new ActivityOptions.Builder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                  .build());
      return day + " -> " + activities.nextDay(day);
    }
  }
}
