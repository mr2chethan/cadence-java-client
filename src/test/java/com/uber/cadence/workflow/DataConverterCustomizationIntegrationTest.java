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

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.MarkerRecordedEventAttributes;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * Runs a workflow end to end in the in-memory test service with JacksonDataConverters whose
 * ObjectMapper is customized the way applications do it. Applied to the payloads that the client
 * records for itself (the markers of getVersion, local activities and mutableSideEffect and the
 * RetryOptions of Workflow.retry), these customizations would change their JSON or make it
 * unreadable. The histories recorded with a customized converter replay with the default
 * JacksonDataConverter and with JsonDataConverter, and the other way around.
 */
@RunWith(Parameterized.class)
public class DataConverterCustomizationIntegrationTest {

  private static final String TASK_LIST = "DataConverterCustomizationIntegrationTest";
  private static final int WORKFLOW_TIMEOUT_SECONDS = 3600;
  private static final long RESULT_TIMEOUT_SECONDS = 30;
  private static final Ticket TICKET = new Ticket("t-1", 2);
  private static final String EXPECTED_RESULT =
      "v1 once-ok(t-1) 7/7 twice-ok(t-1) LimitException: seats over limit 5 [seats/5] t-1:3 v1";

  @Parameters(name = "{0}")
  public static Collection<Object[]> data() {
    List<Object[]> rows = new ArrayList<>();
    for (Customization customization : Customization.values()) {
      rows.add(new Object[] {customization + " sticky OFF", customization, true});
      rows.add(new Object[] {customization + " sticky ON", customization, false});
    }
    return rows;
  }

  private final Customization customization;
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

  public DataConverterCustomizationIntegrationTest(
      String ignored, Customization customization, boolean disableStickyExecution) {
    this.customization = customization;
    this.converter = customization.newConverter();
    this.disableStickyExecution = disableStickyExecution;
  }

  @Before
  public void setUp() {
    assumeFalse("Uses the in-memory test service", TestEnvironment.isUseDockerService());
  }

  @Test
  public void testWorkflowCompletesAndReplays() throws Exception {
    WorkflowExecutionHistory history = record(converter);
    assertClientPayloadsHaveDefaultForm(history);
    replay(converter, history);
  }

  @Test
  public void testReplayHistoryRecordedWithDefaultConverter() throws Exception {
    replay(converter, record(JacksonDataConverter.getInstance()));
  }

  @Test
  public void testDefaultConverterReplaysHistory() throws Exception {
    replay(JacksonDataConverter.getInstance(), record(converter));
  }

  @Test
  public void testJsonDataConverterReplaysHistory() throws Exception {
    assumeTrue(
        "JsonDataConverter cannot convert java.time.Duration in this JVM",
        canConvertWithGson(Duration.ofSeconds(1)));
    replay(JsonDataConverter.getInstance(), record(converter));
  }

  @Test
  public void testInterceptorReturningAnotherMapperFails() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> new JacksonDataConverter(mapper -> customization.configure(new ObjectMapper())));
    assertTrue(
        e.getMessage(),
        e.getMessage()
            .startsWith(
                "mapperInterceptor must return the ObjectMapper it was given, or a copy() of it"));
    assertThrows(IllegalArgumentException.class, () -> new JacksonDataConverter(mapper -> null));
  }

  /** Customizations of the ObjectMapper of a JacksonDataConverter. */
  enum Customization {
    /** Settings that Spring Boot applications use, applied to the given mapper. */
    SPRING_STYLE {
      @Override
      ObjectMapper configure(ObjectMapper mapper) {
        return springStyle(mapper);
      }
    },
    /** The interceptor keeps the mapper, which the application changes after construction. */
    LEAKED_REFERENCE {
      @Override
      ObjectMapper configure(ObjectMapper mapper) {
        return mapper;
      }

      @Override
      DataConverter newConverter() {
        AtomicReference<ObjectMapper> leaked = new AtomicReference<>();
        DataConverter converter =
            new JacksonDataConverter(
                mapper -> {
                  leaked.set(mapper);
                  return mapper;
                });
        // Applied to the converter, this would make Ticket unwritable, as it hides all its
        // properties, and change the JSON of the client's own payloads.
        springStyle(leaked.get())
            .registerModule(isoDurationModule())
            .setVisibility(PropertyAccessor.ALL, Visibility.NONE);
        return converter;
      }
    },
    DEFAULT_TYPING {
      @Override
      ObjectMapper configure(ObjectMapper mapper) {
        return defaultTyping(mapper);
      }
    },
    USER_DURATION_MODULE {
      @Override
      ObjectMapper configure(ObjectMapper mapper) {
        return mapper.registerModule(isoDurationModule());
      }
    },
    /** All of the above, returning a copy of the given mapper. */
    ALL_COMBINED {
      @Override
      ObjectMapper configure(ObjectMapper mapper) {
        return defaultTyping(springStyle(mapper).registerModule(isoDurationModule())).copy();
      }
    };

    abstract ObjectMapper configure(ObjectMapper mapper);

    DataConverter newConverter() {
      return new JacksonDataConverter(this::configure);
    }
  }

  private static ObjectMapper springStyle(ObjectMapper mapper) {
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new Jdk8Module());
    mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mapper.setVisibility(PropertyAccessor.FIELD, Visibility.NONE);
    mapper.setVisibility(PropertyAccessor.GETTER, Visibility.PUBLIC_ONLY);
    mapper.setVisibility(PropertyAccessor.IS_GETTER, Visibility.PUBLIC_ONLY);
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    return mapper;
  }

  private static ObjectMapper defaultTyping(ObjectMapper mapper) {
    return mapper.activateDefaultTyping(
        LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
  }

  /** Writes Durations as ISO-8601 text, like "PT15S", and reads nothing else. */
  private static SimpleModule isoDurationModule() {
    SimpleModule module = new SimpleModule("iso-duration");
    module.addSerializer(Duration.class, new IsoDurationSerializer());
    module.addDeserializer(Duration.class, new IsoDurationDeserializer());
    return module;
  }

  private static final class IsoDurationSerializer extends StdSerializer<Duration> {
    IsoDurationSerializer() {
      super(Duration.class);
    }

    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeString(value.toString());
    }
  }

  private static final class IsoDurationDeserializer extends StdDeserializer<Duration> {
    IsoDurationDeserializer() {
      super(Duration.class);
    }

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (!p.hasToken(JsonToken.VALUE_STRING)) {
        return (Duration) ctxt.handleUnexpectedToken(Duration.class, p);
      }
      return Duration.parse(p.getText());
    }
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

  /** Runs the workflow with the converter and returns its history. */
  private WorkflowExecutionHistory record(DataConverter dataConverter) throws TimeoutException {
    TestWorkflowEnvironment environment = newEnvironment(dataConverter);
    Worker worker = environment.newWorker(TASK_LIST);
    worker.registerWorkflowImplementationTypes(CustomizationWorkflowImpl.class);
    TicketActivitiesImpl activities = new TicketActivitiesImpl();
    worker.registerActivitiesImplementations(activities);
    environment.start();

    CustomizationWorkflow workflow =
        environment.newWorkflowClient().newWorkflowStub(CustomizationWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run, TICKET);
    assertEquals(
        EXPECTED_RESULT,
        WorkflowStub.fromTyped(workflow)
            .getResult(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, String.class));
    assertEquals(2, activities.failOnceCalls.get());
    assertEquals(3, activities.failTwiceCalls.get());
    assertEquals(1, activities.rejectCalls.get());

    List<HistoryEvent> events = new ArrayList<>();
    Iterator<HistoryEvent> iterator =
        WorkflowExecutionUtils.getHistory(
            environment.getWorkflowService(), environment.getDomain(), execution);
    while (iterator.hasNext()) {
      events.add(iterator.next());
    }
    assertFalse(WorkflowExecutionUtils.containsEvent(events, EventType.DecisionTaskFailed));
    return new WorkflowExecutionHistory(events);
  }

  private void replay(DataConverter dataConverter, WorkflowExecutionHistory history)
      throws Exception {
    Worker replayer = newEnvironment(dataConverter).newWorker(TASK_LIST);
    replayer.registerWorkflowImplementationTypes(CustomizationWorkflowImpl.class);
    replayer.replayWorkflowExecution(history);
  }

  /** The client's own payloads are written as the default JacksonDataConverter writes them. */
  private static void assertClientPayloadsHaveDefaultForm(WorkflowExecutionHistory history) {
    List<String> headers = new ArrayList<>();
    List<String> details = new ArrayList<>();
    for (HistoryEvent event : history.getEvents()) {
      MarkerRecordedEventAttributes marker = event.getMarkerRecordedEventAttributes();
      if (marker == null) {
        continue;
      }
      if (marker.getHeader() != null) {
        for (byte[] value : marker.getHeader().getFields().values()) {
          headers.add(new String(value, StandardCharsets.UTF_8));
        }
      }
      if (marker.getDetails() != null) {
        details.add(new String(marker.getDetails(), StandardCharsets.UTF_8));
      }
    }
    assertContains(headers, "{\"id\":\"customization\",\"eventId\":");
    assertContains(headers, "\"errReason\":\"java.lang.IllegalStateException\"");
    assertContains(headers, "\"backoff\":{\"seconds\":15,\"nanos\":0}");
    assertContains(details, "{\"initialInterval\":{\"seconds\":1,\"nanos\":0},");
  }

  private static void assertContains(List<String> values, String expected) {
    for (String value : values) {
      if (value.contains(expected)) {
        return;
      }
    }
    throw new AssertionError("No value contains " + expected + ": " + values);
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

  private static RetryOptions.Builder retryOptions(Duration initialInterval) {
    return new RetryOptions.Builder().setInitialInterval(initialInterval).setMaximumAttempts(3);
  }

  /**
   * Has getters, which a mapper with Spring style visibility needs, and single-word properties,
   * which SNAKE_CASE leaves unchanged, so that all converters write and read it alike.
   */
  public static final class Ticket {
    private final String id;
    private final int seats;

    public Ticket(String id, int seats) {
      this.id = id;
      this.seats = seats;
    }

    public String getId() {
      return id;
    }

    public int getSeats() {
      return seats;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Ticket)) {
        return false;
      }
      Ticket ticket = (Ticket) o;
      return seats == ticket.seats && Objects.equals(id, ticket.id);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, seats);
    }

    @Override
    public String toString() {
      return id + ":" + seats;
    }
  }

  /** Has only a constructor with several arguments. */
  public static class LimitException extends RuntimeException {
    private final String resource;
    private final int limit;

    public LimitException(String resource, int limit) {
      super(resource + " over limit " + limit);
      this.resource = resource;
      this.limit = limit;
    }

    String describe() {
      return getClass().getSimpleName() + ": " + getMessage() + " [" + resource + "/" + limit + "]";
    }
  }

  public interface TicketActivities {
    String failOnce(String id);

    String failTwice(String id);

    String reject(Ticket ticket);

    Ticket book(Ticket ticket);
  }

  public static class TicketActivitiesImpl implements TicketActivities {
    final AtomicInteger failOnceCalls = new AtomicInteger();
    final AtomicInteger failTwiceCalls = new AtomicInteger();
    final AtomicInteger rejectCalls = new AtomicInteger();

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
    public String reject(Ticket ticket) {
      rejectCalls.incrementAndGet();
      throw new LimitException("seats", 5);
    }

    @Override
    public Ticket book(Ticket ticket) {
      return new Ticket(ticket.getId(), ticket.getSeats() + 1);
    }
  }

  public interface CustomizationWorkflow {
    @WorkflowMethod(
      executionStartToCloseTimeoutSeconds = WORKFLOW_TIMEOUT_SECONDS,
      taskList = TASK_LIST
    )
    String run(Ticket ticket);
  }

  public static class CustomizationWorkflowImpl implements CustomizationWorkflow {

    @Override
    public String run(Ticket ticket) {
      int version = Workflow.getVersion("customization", Workflow.DEFAULT_VERSION, 1);
      // The first attempt fails. The backoff is longer than the decision task timeout, so the
      // retry waits for a timer.
      TicketActivities localActivities =
          Workflow.newLocalActivityStub(
              TicketActivities.class,
              new LocalActivityOptions.Builder()
                  .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                  .setRetryOptions(retryOptions(Duration.ofSeconds(15)).build())
                  .build());
      String local = localActivities.failOnce(ticket.getId());

      Integer first =
          Workflow.mutableSideEffect("value", Integer.class, (stored, value) -> false, () -> 7);
      Workflow.sleep(Duration.ofMinutes(1));
      // Returns the value recorded by the first call, as the new value is not an update.
      Integer second =
          Workflow.mutableSideEffect("value", Integer.class, (stored, value) -> false, () -> 8);

      TicketActivities activities =
          Workflow.newActivityStub(
              TicketActivities.class,
              new ActivityOptions.Builder()
                  .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                  .build());
      String retried =
          Workflow.retry(
              retryOptions(Duration.ofSeconds(1)).build(),
              () -> activities.failTwice(ticket.getId()));
      String rejected;
      try {
        rejected =
            Workflow.retry(
                retryOptions(Duration.ofSeconds(1)).setDoNotRetry(LimitException.class).build(),
                () -> activities.reject(ticket));
      } catch (ActivityFailureException e) {
        Throwable cause = e.getCause();
        rejected =
            cause instanceof LimitException
                ? ((LimitException) cause).describe()
                : String.valueOf(cause);
      }
      Ticket booked = activities.book(ticket);
      int laterVersion = Workflow.getVersion("customization", Workflow.DEFAULT_VERSION, 1);
      return String.join(
          " ",
          "v" + version,
          local,
          first + "/" + second,
          retried,
          rejected,
          booked.toString(),
          "v" + laterVersion);
    }
  }
}
