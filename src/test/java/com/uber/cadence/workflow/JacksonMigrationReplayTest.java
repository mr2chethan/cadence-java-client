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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.gson.annotations.SerializedName;
import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
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
import com.uber.cadence.testing.WorkflowReplayer;
import com.uber.cadence.worker.Worker;
import com.uber.cadence.worker.WorkerFactoryOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;

/**
 * Replays with JacksonDataConverter histories recorded by workers using JsonDataConverter, as when
 * a deployment switches converters while workflows are open. Each workflow compares what it decoded
 * with the values that it was started with and takes another path when they differ, so a value that
 * JacksonDataConverter decodes differently fails the replay.
 *
 * <p>The histories are recorded at test time when JsonDataConverter can convert java.time values
 * (JDK 15 and earlier, or java.base/java.time opened to it). The same workflows are also replayed
 * from histories checked in as resources, which were recorded with JsonDataConverter on JDK 11 in
 * the UTC time zone, so that this is tested on every JVM.
 */
public class JacksonMigrationReplayTest {

  static final String TASK_LIST = "JacksonMigrationReplayTest";
  private static final int WORKFLOW_TIMEOUT_SECONDS = 3600;
  private static final long RESULT_TIMEOUT_SECONDS = 30;

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
  public void testReplayJavaTimeHistoryRecordedWithGson() throws Exception {
    assumeGsonCanConvertJavaTime();
    WorkflowExecutionHistory history = recordWithGson(Recording.JAVA_TIME);
    replay(JacksonDataConverter.getInstance(), history);
  }

  @Test
  public void testReplayUntypedNumbersHistoryRecordedWithGson() throws Exception {
    WorkflowExecutionHistory history = recordWithGson(Recording.UNTYPED_NUMBERS);
    assertReplayFails(() -> replay(JacksonDataConverter.getInstance(), history));
    replay(withGsonCompatibleNumbers(), history);
  }

  @Test
  public void testReplayClientPayloadsHistoryRecordedWithGson() throws Exception {
    assumeGsonCanConvertJavaTime();
    WorkflowExecutionHistory history = recordWithGson(Recording.CLIENT_PAYLOADS);
    replay(JacksonDataConverter.getInstance(), history);
    replay(customized(), history);
  }

  @Test
  public void testReplayCheckedInJavaTimeHistory() throws Exception {
    WorkflowExecutionHistory history =
        WorkflowExecutionUtils.readHistoryFromResource(Recording.JAVA_TIME.resource);
    String input =
        new String(
            history.getEvents().get(0).getWorkflowExecutionStartedEventAttributes().getInput(),
            StandardCharsets.UTF_8);
    // What JsonDataConverter writes through reflection and its own adapters.
    assertTrue(input, input.contains("{\"year\":2024,\"month\":2,\"day\":29}"));
    assertTrue(input, input.contains("{\"seconds\":1700000000,\"nanos\":5}"));
    assertTrue(input, input.contains("\"Nov 14, 2023, 10:13:20 PM\""));
    assertTrue(input, input.contains("{\"value\":\"hello\"}"));

    TimeZone defaultZone = TimeZone.getDefault();
    // JsonDataConverter wrote the Date in the time zone of the JVM that recorded the history.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    try {
      WorkflowReplayer.replayWorkflowExecutionFromResource(
          Recording.JAVA_TIME.resource, JacksonDataConverter.getInstance(), TimeWorkflowImpl.class);
    } finally {
      TimeZone.setDefault(defaultZone);
    }
  }

  @Test
  public void testReplayCheckedInUntypedNumbersHistory() throws Exception {
    assertReplayFails(
        () ->
            WorkflowReplayer.replayWorkflowExecutionFromResource(
                Recording.UNTYPED_NUMBERS.resource,
                JacksonDataConverter.getInstance(),
                NumbersWorkflowImpl.class));
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        Recording.UNTYPED_NUMBERS.resource, withGsonCompatibleNumbers(), NumbersWorkflowImpl.class);
  }

  @Test
  public void testReplayCheckedInClientPayloadsHistory() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        Recording.CLIENT_PAYLOADS.resource,
        JacksonDataConverter.getInstance(),
        ClientPayloadsWorkflowImpl.class);
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        Recording.CLIENT_PAYLOADS.resource, customized(), ClientPayloadsWorkflowImpl.class);
  }

  /**
   * The workflow casts untyped numbers to Double, which JacksonDataConverter decodes as Integer by
   * default. It fails, and does not schedule the activity that the history holds, which the replay
   * reports as nondeterminism.
   */
  private static void assertReplayFails(ThrowingRunnable replay) {
    RuntimeException e = assertThrows(RuntimeException.class, replay);
    assertTrue(e.getMessage(), e.getMessage().contains("nondeterministic"));
  }

  private static DataConverter withGsonCompatibleNumbers() {
    return new JacksonDataConverter(
        mapper -> mapper.registerModule(JacksonDataConverter.gsonCompatibleNumbersModule()));
  }

  /** A customization that does not apply to the data the client records for itself. */
  private static DataConverter customized() {
    return new JacksonDataConverter(
        mapper ->
            mapper
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
  }

  private static void assumeGsonCanConvertJavaTime() {
    DataConverter gson = JsonDataConverter.getInstance();
    boolean canConvert;
    try {
      gson.fromData(gson.toData(Duration.ofSeconds(1)), Duration.class, Duration.class);
      canConvert = true;
    } catch (DataConverterException e) {
      canConvert = false;
    }
    assumeTrue("JsonDataConverter cannot convert java.time values in this JVM", canConvert);
  }

  private WorkflowExecutionHistory recordWithGson(Recording recording) throws TimeoutException {
    TestWorkflowEnvironment environment = newEnvironment(JsonDataConverter.getInstance());
    environments.add(environment);
    return record(environment, recording);
  }

  private void replay(DataConverter converter, WorkflowExecutionHistory history) throws Exception {
    TestWorkflowEnvironment environment = newEnvironment(converter);
    environments.add(environment);
    Worker worker = environment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(
        TimeWorkflowImpl.class, NumbersWorkflowImpl.class, ClientPayloadsWorkflowImpl.class);
    worker.replayWorkflowExecution(history);
  }

  static TestWorkflowEnvironment newEnvironment(DataConverter converter) {
    return TestWorkflowEnvironment.newInstance(
        new TestEnvironmentOptions.Builder()
            .setDataConverter(converter)
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setDataConverter(converter).build())
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder().setDisableStickyExecution(true).build())
            .build());
  }

  /** Runs the workflow of the recording in the environment and returns its history. */
  static WorkflowExecutionHistory record(TestWorkflowEnvironment environment, Recording recording)
      throws TimeoutException {
    Worker worker = environment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(
        TimeWorkflowImpl.class, NumbersWorkflowImpl.class, ClientPayloadsWorkflowImpl.class);
    worker.registerActivitiesImplementations(new MigrationActivitiesImpl());
    environment.start();
    WorkflowStub workflow =
        WorkflowStub.fromTyped(recording.start(environment.newWorkflowClient()));
    assertEquals(
        recording.expected,
        workflow.getResult(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, String.class));
    List<HistoryEvent> events = new ArrayList<>();
    Iterator<HistoryEvent> iterator =
        WorkflowExecutionUtils.getHistory(
            environment.getWorkflowService(), environment.getDomain(), workflow.getExecution());
    while (iterator.hasNext()) {
      events.add(iterator.next());
    }
    assertFalse(WorkflowExecutionUtils.containsEvent(events, EventType.DecisionTaskFailed));
    return new WorkflowExecutionHistory(events);
  }

  /** The recorded workflows, and the resources their histories recorded with Gson are in. */
  enum Recording {
    JAVA_TIME(
        "testGsonJavaTimeHistory.json",
        "day=2024-02-29 at=2023-11-14T22:13:20.000000005Z when=1700000000000"
            + " calendar=2024-02-29 10:30:15 note=Optional[hello] next=2024-03-01"
            + " later=1700000060000 timeout=PT1M30.000000005S"
            + " slot=2024-03-01T10:30/P3D/OptionalInt[4] shout=Optional[HELLO]"),
    UNTYPED_NUMBERS(
        "testGsonUntypedNumbersHistory.json", "count=3.0 first=1.0 ratio=0.5 total=12.0 unit=kg"),
    CLIENT_PAYLOADS(
        "testGsonClientPayloadsHistory.json",
        "v1 once-ok(c-1) 7/7 twice-ok(c-1) QuotaException: storage over quota 5 [storage/5] v1");

    final String resource;
    final String expected;

    Recording(String resource, String expected) {
      this.resource = resource;
      this.expected = expected;
    }

    /** Starts the workflow and returns its stub. */
    Object start(WorkflowClient client) {
      switch (this) {
        case JAVA_TIME:
          TimeWorkflow time = client.newWorkflowStub(TimeWorkflow.class);
          WorkflowClient.start(
              time::run,
              expected,
              LocalDate.of(2024, 2, 29),
              Instant.ofEpochSecond(1_700_000_000L, 5),
              new Date(1_700_000_000_000L),
              new GregorianCalendar(2024, Calendar.FEBRUARY, 29, 10, 30, 15),
              Optional.of("hello"));
          return time;
        case UNTYPED_NUMBERS:
          Map<String, Object> attributes = new LinkedHashMap<>();
          attributes.put("count", 3);
          attributes.put("sizes", Arrays.asList(1, 2));
          attributes.put("ratio", 0.5);
          NumbersWorkflow numbers = client.newWorkflowStub(NumbersWorkflow.class);
          WorkflowClient.start(numbers::run, expected, attributes);
          return numbers;
        case CLIENT_PAYLOADS:
          ClientPayloadsWorkflow payloads = client.newWorkflowStub(ClientPayloadsWorkflow.class);
          WorkflowClient.start(payloads::run, expected, "c-1");
          return payloads;
      }
      throw new IllegalStateException("Unknown recording " + this);
    }
  }

  /**
   * Completes the workflow with the description of the values it decoded. When they differ from the
   * expected ones it starts a timer first, which does not match the recorded history.
   */
  private static String complete(String expected, String actual) {
    if (!actual.equals(expected)) {
      Workflow.sleep(Duration.ofSeconds(1));
    }
    return activities().report(actual);
  }

  private static MigrationActivities activities() {
    return Workflow.newActivityStub(
        MigrationActivities.class,
        new ActivityOptions.Builder().setScheduleToCloseTimeout(Duration.ofSeconds(30)).build());
  }

  private static RetryOptions.Builder retryOptions(Duration initialInterval) {
    return new RetryOptions.Builder().setInitialInterval(initialInterval).setMaximumAttempts(3);
  }

  public static final class Slot {
    private final LocalDateTime start;
    private final Period length;
    private final OptionalInt seats;

    public Slot(LocalDateTime start, Period length, OptionalInt seats) {
      this.start = start;
      this.length = length;
      this.seats = seats;
    }

    Slot next() {
      return new Slot(start.plusDays(1), length.plusDays(1), OptionalInt.of(seats.getAsInt() + 1));
    }

    @Override
    public String toString() {
      return start + "/" + length + "/" + seats;
    }
  }

  /** Has only a constructor with several arguments, and a field renamed with Gson. */
  public static class QuotaException extends RuntimeException {
    private final String resource;

    @SerializedName("max")
    private final int quota;

    public QuotaException(String resource, int quota) {
      super(resource + " over quota " + quota);
      this.resource = resource;
      this.quota = quota;
    }

    String describe() {
      return getClass().getSimpleName() + ": " + getMessage() + " [" + resource + "/" + quota + "]";
    }
  }

  public interface MigrationActivities {
    LocalDate nextDay(LocalDate day);

    Date later(Date when);

    Duration timeout();

    Slot reschedule(Slot slot);

    Optional<String> shout(Optional<String> note);

    Map<String, Object> stats();

    String failOnce(String id);

    String failTwice(String id);

    String reject(String id);

    String report(String description);
  }

  public static class MigrationActivitiesImpl implements MigrationActivities {
    private final AtomicInteger failOnceCalls = new AtomicInteger();
    private final AtomicInteger failTwiceCalls = new AtomicInteger();

    @Override
    public LocalDate nextDay(LocalDate day) {
      return day.plusDays(1);
    }

    @Override
    public Date later(Date when) {
      return new Date(when.getTime() + 60_000);
    }

    @Override
    public Duration timeout() {
      return Duration.ofSeconds(90, 5);
    }

    @Override
    public Slot reschedule(Slot slot) {
      return slot.next();
    }

    @Override
    public Optional<String> shout(Optional<String> note) {
      return note.map(value -> value.toUpperCase(Locale.ROOT));
    }

    @Override
    public Map<String, Object> stats() {
      Map<String, Object> stats = new LinkedHashMap<>();
      stats.put("total", 12);
      stats.put("unit", "kg");
      return stats;
    }

    @Override
    public String failOnce(String id) {
      if (failOnceCalls.incrementAndGet() < 2) {
        throw new IllegalStateException("fails once");
      }
      return "once-ok(" + id + ")";
    }

    @Override
    public String failTwice(String id) {
      if (failTwiceCalls.incrementAndGet() < 3) {
        throw new IllegalStateException("fails twice");
      }
      return "twice-ok(" + id + ")";
    }

    @Override
    public String reject(String id) {
      throw new QuotaException("storage", 5);
    }

    @Override
    public String report(String description) {
      return description;
    }
  }

  public interface TimeWorkflow {
    @WorkflowMethod(
      executionStartToCloseTimeoutSeconds = WORKFLOW_TIMEOUT_SECONDS,
      taskList = TASK_LIST
    )
    String run(
        String expected,
        LocalDate day,
        Instant at,
        Date when,
        Calendar calendar,
        Optional<String> note);
  }

  public static class TimeWorkflowImpl implements TimeWorkflow {
    @Override
    public String run(
        String expected,
        LocalDate day,
        Instant at,
        Date when,
        Calendar calendar,
        Optional<String> note) {
      MigrationActivities activities = activities();
      Slot slot = new Slot(day.atTime(10, 30), Period.ofDays(2), OptionalInt.of(3));
      String actual =
          String.join(
              " ",
              "day=" + day,
              "at=" + at,
              "when=" + when.getTime(),
              String.format(Locale.ROOT, "calendar=%tF %<tT", calendar),
              "note=" + note,
              "next=" + activities.nextDay(day),
              "later=" + activities.later(when).getTime(),
              "timeout=" + activities.timeout(),
              "slot=" + activities.reschedule(slot),
              "shout=" + activities.shout(note));
      return complete(expected, actual);
    }
  }

  public interface NumbersWorkflow {
    @WorkflowMethod(
      executionStartToCloseTimeoutSeconds = WORKFLOW_TIMEOUT_SECONDS,
      taskList = TASK_LIST
    )
    String run(String expected, Map<String, Object> attributes);
  }

  /** Written for JsonDataConverter, which decodes untyped numbers as Double. */
  public static class NumbersWorkflowImpl implements NumbersWorkflow {
    @Override
    public String run(String expected, Map<String, Object> attributes) {
      double count = (Double) attributes.get("count");
      double first = (Double) ((List<?>) attributes.get("sizes")).get(0);
      double ratio = (Double) attributes.get("ratio");
      Map<String, Object> stats = activities().stats();
      double total = (Double) stats.get("total");
      String actual =
          "count="
              + count
              + " first="
              + first
              + " ratio="
              + ratio
              + " total="
              + total
              + " unit="
              + stats.get("unit");
      return complete(expected, actual);
    }
  }

  public interface ClientPayloadsWorkflow {
    @WorkflowMethod(
      executionStartToCloseTimeoutSeconds = WORKFLOW_TIMEOUT_SECONDS,
      taskList = TASK_LIST
    )
    String run(String expected, String id);
  }

  /**
   * Records the data the client records for itself: the markers of getVersion, of a local activity
   * retried after a backoff and of mutableSideEffect, and the RetryOptions of Workflow.retry.
   */
  public static class ClientPayloadsWorkflowImpl implements ClientPayloadsWorkflow {
    @Override
    public String run(String expected, String id) {
      int version = Workflow.getVersion("migration", Workflow.DEFAULT_VERSION, 1);
      // The backoff is longer than the decision task timeout, so the retry waits for a timer.
      MigrationActivities localActivities =
          Workflow.newLocalActivityStub(
              MigrationActivities.class,
              new LocalActivityOptions.Builder()
                  .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                  .setRetryOptions(retryOptions(Duration.ofSeconds(15)).build())
                  .build());
      String local = localActivities.failOnce(id);
      Integer first =
          Workflow.mutableSideEffect("value", Integer.class, (stored, value) -> false, () -> 7);
      Workflow.sleep(Duration.ofMinutes(1));
      Integer second =
          Workflow.mutableSideEffect("value", Integer.class, (stored, value) -> false, () -> 8);
      MigrationActivities activities = activities();
      String retried =
          Workflow.retry(
              retryOptions(Duration.ofSeconds(1)).build(), () -> activities.failTwice(id));
      String rejected;
      try {
        rejected =
            Workflow.retry(
                retryOptions(Duration.ofSeconds(1)).setDoNotRetry(QuotaException.class).build(),
                () -> activities.reject(id));
      } catch (ActivityFailureException e) {
        Throwable cause = e.getCause();
        rejected =
            cause instanceof QuotaException
                ? ((QuotaException) cause).describe()
                : String.valueOf(cause);
      }
      int laterVersion = Workflow.getVersion("migration", Workflow.DEFAULT_VERSION, 1);
      String actual =
          String.join(
              " ",
              "v" + version,
              local,
              first + "/" + second,
              retried,
              rejected,
              "v" + laterVersion);
      return complete(expected, actual);
    }
  }
}
