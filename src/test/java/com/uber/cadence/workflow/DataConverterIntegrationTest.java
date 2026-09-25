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

package com.uber.cadence.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Splitter;
import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.activity.LocalActivityOptions;
import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowStub;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.common.WorkflowExecutionHistory;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.converter.JacksonDataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.testUtils.TestEnvironment;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerFactoryOptions;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Runs workflows end to end in the in-memory test service with the data converter used by both the
 * client and the workers. Besides user payloads this covers the payloads the client itself encodes
 * with the configured converter: markers of local activities, getVersion and mutableSideEffect, the
 * RetryOptions of Workflow.retry, failures and empty results of void workflows.
 */
@RunWith(Parameterized.class)
public class DataConverterIntegrationTest {

  private static final String TASK_LIST = "DataConverterIntegrationTest";
  private static final int WORKFLOW_TIMEOUT_SECONDS = 3600;
  private static final long RESULT_TIMEOUT_SECONDS = 30;

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    List<Object[]> rows = new ArrayList<>();
    if (isConverterEnabled("gson")) {
      DataConverter gson = JsonDataConverter.getInstance();
      rows.add(new Object[] {"gson sticky OFF", gson, true});
      rows.add(new Object[] {"gson sticky ON", gson, false});
      // A custom converter that wraps JsonDataConverter.
      rows.add(new Object[] {"prefixing-gson sticky OFF", new PrefixingDataConverter(gson), true});
    }
    if (isConverterEnabled("jackson")) {
      DataConverter jackson = JacksonDataConverter.getInstance();
      rows.add(new Object[] {"jackson sticky OFF", jackson, true});
      rows.add(new Object[] {"jackson sticky ON", jackson, false});
    }
    return rows;
  }

  /**
   * Whether the tests run with the named converter ("gson" or "jackson"). The system property
   * cadence.test.converters restricts them to a comma separated list of converters.
   */
  private static boolean isConverterEnabled(String name) {
    String converters = System.getProperty("cadence.test.converters", "gson,jackson");
    return Splitter.on(',').trimResults().splitToList(converters).contains(name);
  }

  private final DataConverter converter;
  private final boolean disableStickyExecution;
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

  private TestActivitiesImpl activities;
  private TestWorkflowEnvironment testEnvironment;
  private WorkflowClient client;

  public DataConverterIntegrationTest(
      String ignored, DataConverter converter, boolean disableStickyExecution) {
    this.converter = converter;
    this.disableStickyExecution = disableStickyExecution;
  }

  @Before
  public void setUp() {
    assumeFalse("Uses the in-memory test service", TestEnvironment.isUseDockerService());
    activities = new TestActivitiesImpl();
    testEnvironment = startEnvironment(converter, activities);
    client = testEnvironment.newWorkflowClient();
  }

  @Test
  public void testLocalActivityRetriedAfterTimer() throws Exception {
    assumeGsonCanConvert(Duration.ofSeconds(1));
    ScenarioWorkflow workflow = startScenario(Scenario.LOCAL_ACTIVITY_RETRY);
    assertEquals("once-ok", resultOf(workflow, String.class));
    assertEquals(2, activities.failOnceCalls.get());
    // The backoff is longer than the decision task timeout, so the retry waits for a timer.
    List<HistoryEvent> history =
        historyOf(testEnvironment, WorkflowStub.fromTyped(workflow).getExecution());
    assertTrue(WorkflowExecutionUtils.containsEvent(history, EventType.TimerStarted));
  }

  @Test
  public void testGetVersionAcrossDecisionTasks() throws Exception {
    assertEquals("1/1 tag(done)", runScenario(Scenario.VERSION));
  }

  @Test
  public void testMutableSideEffectAcrossDecisionTasks() throws Exception {
    assertEquals("7/7", runScenario(Scenario.MUTABLE_SIDE_EFFECT));
  }

  @Test
  public void testWorkflowRetry() throws Exception {
    assumeGsonCanConvert(Duration.ofSeconds(1));
    assertEquals("twice-ok", runScenario(Scenario.RETRY));
    assertEquals(3, activities.failTwiceCalls.get());
  }

  @Test
  public void testVoidWorkflowResult() throws Exception {
    client.newWorkflowStub(VoidWorkflow.class).run();

    VoidWorkflow executed = client.newWorkflowStub(VoidWorkflow.class);
    assertNull(WorkflowClient.execute(executed::run).get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));

    VoidWorkflow started = client.newWorkflowStub(VoidWorkflow.class);
    WorkflowClient.start(started::run);
    assertNull(resultOf(started, Void.class));
  }

  @Test
  public void testVoidChildWorkflow() throws Exception {
    assertEquals("completed", runScenario(Scenario.VOID_CHILD));
  }

  @Test
  public void testReplayOwnHistory() throws Exception {
    assumeGsonCanConvert(Duration.ofSeconds(1));
    replay(runMarkersScenario(testEnvironment));
  }

  @Test
  public void testSetKeepsIterationOrder() throws Exception {
    // Item has no hashCode, so a HashSet would iterate in a different order in every decision
    // task, and activity results would be attributed to the wrong items on replay.
    Set<Item> items = new LinkedHashSet<>();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String name = "item-" + i;
      items.add(new Item(name));
      expected.add(name + " -> tag(" + name + ")");
    }
    ItemsWorkflow workflow = client.newWorkflowStub(ItemsWorkflow.class);
    WorkflowClient.start(workflow::run, items);
    assertEquals(String.join(", ", expected), resultOf(workflow, String.class));
  }

  private TestWorkflowEnvironment newEnvironment(DataConverter dataConverter) {
    TestWorkflowEnvironment environment =
        TestWorkflowEnvironment.newInstance(
            new TestEnvironmentOptions.Builder()
                .setDataConverter(dataConverter)
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(dataConverter).build())
                .setWorkerFactoryOptions(
                    WorkerFactoryOptions.newBuilder()
                        .setDisableStickyExecution(disableStickyExecution)
                        .build())
                .build());
    environments.add(environment);
    return environment;
  }

  private TestWorkflowEnvironment startEnvironment(
      DataConverter dataConverter, TestActivitiesImpl activitiesImpl) {
    TestWorkflowEnvironment environment = newEnvironment(dataConverter);
    Worker worker = environment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(
        ScenarioWorkflowImpl.class, VoidWorkflowImpl.class, ItemsWorkflowImpl.class);
    worker.registerActivitiesImplementations(activitiesImpl);
    environment.start();
    return environment;
  }

  private ScenarioWorkflow startScenario(Scenario scenario) {
    ScenarioWorkflow workflow = client.newWorkflowStub(ScenarioWorkflow.class);
    WorkflowClient.start(workflow::run, scenario);
    return workflow;
  }

  private String runScenario(Scenario scenario) throws TimeoutException {
    return resultOf(startScenario(scenario), String.class);
  }

  private static <R> R resultOf(Object workflow, Class<R> resultClass) throws TimeoutException {
    return WorkflowStub.fromTyped(workflow)
        .getResult(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, resultClass);
  }

  /** Runs a workflow that records every kind of marker and returns its history. */
  private static WorkflowExecutionHistory runMarkersScenario(TestWorkflowEnvironment environment)
      throws TimeoutException {
    ScenarioWorkflow workflow =
        environment.newWorkflowClient().newWorkflowStub(ScenarioWorkflow.class);
    WorkflowClient.start(workflow::run, Scenario.MARKERS);
    assertEquals("1 once-ok 7/7 twice-ok", resultOf(workflow, String.class));
    return new WorkflowExecutionHistory(
        historyOf(environment, WorkflowStub.fromTyped(workflow).getExecution()));
  }

  private void replay(WorkflowExecutionHistory history) throws Exception {
    replay(converter, history);
  }

  private void replay(DataConverter dataConverter, WorkflowExecutionHistory history)
      throws Exception {
    Worker replayer = newEnvironment(dataConverter).newWorker(TASK_LIST);
    replayer.registerWorkflowImplementationTypes(ScenarioWorkflowImpl.class);
    replayer.replayWorkflowExecution(history);
  }

  private static List<HistoryEvent> historyOf(
      TestWorkflowEnvironment environment, WorkflowExecution execution) {
    List<HistoryEvent> events = new ArrayList<>();
    Iterator<HistoryEvent> iterator =
        WorkflowExecutionUtils.getHistory(
            environment.getWorkflowService(), environment.getDomain(), execution);
    while (iterator.hasNext()) {
      events.add(iterator.next());
    }
    return events;
  }

  /**
   * JsonDataConverter reflects on the fields of JDK classes such as java.time.Duration and
   * java.util.Optional, which fails when java.base is not open to it (JDK 16+). Skips Gson based
   * rows in that case.
   */
  private void assumeGsonCanConvert(Object value) {
    if (!(converter instanceof JacksonDataConverter)) {
      assumeTrue(
          "JsonDataConverter cannot convert " + value.getClass().getName() + " in this JVM",
          canConvertWithGson(value));
    }
  }

  private static boolean canConvertWithGson(Object value) {
    DataConverter gson = JsonDataConverter.getInstance();
    try {
      gson.fromData(gson.toData(value), value.getClass(), value.getClass());
      return true;
    } catch (DataConverterException e) {
      return false;
    }
  }

  static TestActivities newActivityStub() {
    return Workflow.newActivityStub(
        TestActivities.class,
        new ActivityOptions.Builder().setScheduleToCloseTimeout(Duration.ofSeconds(30)).build());
  }

  /** Has no hashCode, so its hash code differs in every decoded instance. */
  public static final class Item {
    private final String name;

    public Item(String name) {
      this.name = name;
    }

    private Item() {
      this(null);
    }

    public String getName() {
      return name;
    }
  }

  public interface TestActivities {
    String failOnce();

    String failTwice();

    String tag(Item item);
  }

  public static class TestActivitiesImpl implements TestActivities {
    final AtomicInteger failOnceCalls = new AtomicInteger();
    final AtomicInteger failTwiceCalls = new AtomicInteger();

    @Override
    public String failOnce() {
      if (failOnceCalls.incrementAndGet() < 2) {
        throw new IllegalStateException("fails once");
      }
      return "once-ok";
    }

    @Override
    public String failTwice() {
      if (failTwiceCalls.incrementAndGet() < 3) {
        throw new IllegalStateException("fails twice");
      }
      return "twice-ok";
    }

    @Override
    public String tag(Item item) {
      return "tag(" + item.getName() + ")";
    }
  }

  public enum Scenario {
    LOCAL_ACTIVITY_RETRY,
    VERSION,
    MUTABLE_SIDE_EFFECT,
    RETRY,
    VOID_CHILD,
    MARKERS
  }

  public interface ScenarioWorkflow {
    @WorkflowMethod(
      executionStartToCloseTimeoutSeconds = WORKFLOW_TIMEOUT_SECONDS,
      taskList = TASK_LIST
    )
    String run(Scenario scenario);
  }

  public static class ScenarioWorkflowImpl implements ScenarioWorkflow {

    private final TestActivities activities = newActivityStub();

    @Override
    public String run(Scenario scenario) {
      switch (scenario) {
        case LOCAL_ACTIVITY_RETRY:
          return localActivities(retryOptions(Duration.ofSeconds(30))).failOnce();
        case VERSION:
          return version();
        case MUTABLE_SIDE_EFFECT:
          return mutableSideEffect();
        case RETRY:
          return Workflow.retry(retryOptions(Duration.ofSeconds(1)), activities::failTwice);
        case VOID_CHILD:
          return voidChild();
        case MARKERS:
          return markers();
      }
      throw new IllegalArgumentException("Unknown scenario " + scenario);
    }

    private String version() {
      int first = Workflow.getVersion("change", Workflow.DEFAULT_VERSION, 1);
      Workflow.sleep(Duration.ofMinutes(1));
      int second = Workflow.getVersion("change", Workflow.DEFAULT_VERSION, 1);
      return first + "/" + second + " " + activities.tag(new Item("done"));
    }

    private static String voidChild() {
      Workflow.newChildWorkflowStub(VoidWorkflow.class).run();
      Void result = Workflow.newUntypedChildWorkflowStub("VoidWorkflow::run").execute(Void.class);
      return result == null ? "completed" : "unexpected result";
    }

    private static String mutableSideEffect() {
      Integer first =
          Workflow.mutableSideEffect("value", Integer.class, (stored, value) -> false, () -> 7);
      Workflow.sleep(Duration.ofMinutes(1));
      // Returns the value recorded by the first call, as the new value is not an update.
      Integer second =
          Workflow.mutableSideEffect("value", Integer.class, (stored, value) -> false, () -> 8);
      return first + "/" + second;
    }

    private String markers() {
      int version = Workflow.getVersion("change", Workflow.DEFAULT_VERSION, 1);
      String local = localActivities(retryOptions(Duration.ofSeconds(30))).failOnce();
      String sideEffect = mutableSideEffect();
      String retried = Workflow.retry(retryOptions(Duration.ofSeconds(1)), activities::failTwice);
      return version + " " + local + " " + sideEffect + " " + retried;
    }

    private static TestActivities localActivities(RetryOptions retryOptions) {
      return Workflow.newLocalActivityStub(
          TestActivities.class,
          new LocalActivityOptions.Builder()
              .setScheduleToCloseTimeout(Duration.ofMinutes(5))
              .setRetryOptions(retryOptions)
              .build());
    }

    private static RetryOptions retryOptions(Duration initialInterval) {
      return new RetryOptions.Builder()
          .setInitialInterval(initialInterval)
          .setMaximumAttempts(3)
          .build();
    }
  }

  public interface VoidWorkflow {
    @WorkflowMethod(
      executionStartToCloseTimeoutSeconds = WORKFLOW_TIMEOUT_SECONDS,
      taskList = TASK_LIST
    )
    void run();
  }

  public static class VoidWorkflowImpl implements VoidWorkflow {
    @Override
    public void run() {}
  }

  public interface ItemsWorkflow {
    @WorkflowMethod(
      executionStartToCloseTimeoutSeconds = WORKFLOW_TIMEOUT_SECONDS,
      taskList = TASK_LIST
    )
    String run(Set<Item> items);
  }

  public static class ItemsWorkflowImpl implements ItemsWorkflow {
    @Override
    public String run(Set<Item> items) {
      TestActivities activities = newActivityStub();
      List<String> results = new ArrayList<>();
      // One activity per decision task: without sticky execution each one decodes the input again.
      for (Item item : items) {
        results.add(item.getName() + " -> " + activities.tag(item));
      }
      return String.join(", ", results);
    }
  }

  /**
   * A custom converter that prefixes the payloads of another converter and rejects payloads without
   * the prefix, so that a payload the client decodes without having encoded it with the configured
   * converter fails.
   */
  static final class PrefixingDataConverter implements DataConverter {
    private static final byte[] PREFIX = {'C', 'D', 'C', '1'};

    private final DataConverter delegate;

    PrefixingDataConverter(DataConverter delegate) {
      this.delegate = delegate;
    }

    @Override
    public byte[] toData(Object... values) throws DataConverterException {
      byte[] data = delegate.toData(values);
      if (data == null || data.length == 0) {
        return data;
      }
      byte[] result = Arrays.copyOf(PREFIX, PREFIX.length + data.length);
      System.arraycopy(data, 0, result, PREFIX.length, data.length);
      return result;
    }

    @Override
    public <T> T fromData(byte[] content, Class<T> valueClass, Type valueType)
        throws DataConverterException {
      return delegate.fromData(strip(content, valueType), valueClass, valueType);
    }

    @Override
    public Object[] fromDataArray(byte[] content, Type... valueTypes)
        throws DataConverterException {
      return delegate.fromDataArray(strip(content, valueTypes), valueTypes);
    }

    private static byte[] strip(byte[] content, Type... valueTypes) {
      // Empty payloads, such as the result of a void workflow, are written by the client itself.
      if (content == null || content.length == 0) {
        return content;
      }
      if (content.length < PREFIX.length
          || !Arrays.equals(PREFIX, Arrays.copyOf(content, PREFIX.length))) {
        throw new DataConverterException(
            "Payload not written by PrefixingDataConverter", content, valueTypes);
      }
      return Arrays.copyOfRange(content, PREFIX.length, content.length);
    }
  }
}
