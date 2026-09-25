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

package com.uber.cadence.internal.common;

import static com.uber.cadence.converter.JacksonDataConverterTest.newCustomizedConverter;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Splitter;
import com.uber.cadence.ActivityType;
import com.uber.cadence.Header;
import com.uber.cadence.MarkerRecordedEventAttributes;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.converter.JacksonDataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * The header of a local activity marker is encoded with the data converter of the worker, so every
 * converter has to be able to decode it, including headers recorded by JsonDataConverter.
 */
@RunWith(Parameterized.class)
public class LocalActivityMarkerDataConverterTest {

  private static final String HEADER_KEY = "LocalActivityHeader";

  private static final ActivityType ACTIVITY_TYPE = new ActivityType().setName("Activity::run");

  /** Header of a failed local activity recorded by JsonDataConverter, which escapes '='. */
  private static final String GSON_FAILURE_HEADER =
      "{\"activityId\":\"la-1\",\"activityType\":\"ActivityType(name\\u003dActivity::run)\","
          + "\"errReason\":\"java.lang.IllegalStateException\",\"replayTimeMillis\":1234,"
          + "\"attempt\":2,\"backoff\":{\"seconds\":30,\"nanos\":0},\"isCancelled\":false}";

  /**
   * The header of {@link #GSON_FAILURE_HEADER} as JacksonDataConverter writes it, whatever the
   * configuration of its ObjectMapper: it only does not escape '='.
   */
  private static final String JACKSON_FAILURE_HEADER =
      "{\"activityId\":\"la-1\",\"activityType\":\"ActivityType(name=Activity::run)\","
          + "\"errReason\":\"java.lang.IllegalStateException\",\"replayTimeMillis\":1234,"
          + "\"attempt\":2,\"backoff\":{\"seconds\":30,\"nanos\":0},\"isCancelled\":false}";

  /** Header of a completed local activity recorded by JsonDataConverter. */
  private static final String GSON_SUCCESS_HEADER =
      "{\"activityId\":\"la-2\",\"activityType\":\"ActivityType(name\\u003dActivity::run)\","
          + "\"errReason\":null,\"replayTimeMillis\":1234,\"attempt\":0,\"backoff\":null,"
          + "\"isCancelled\":false}";

  private final DataConverter converter;

  public LocalActivityMarkerDataConverterTest(String name, DataConverter converter) {
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
  public void testFailedActivityMarkerRoundTrip() {
    assumeDurationIsSupported();
    byte[] details = converter.toData(new IllegalStateException("simulated"));
    LocalActivityMarkerData marker =
        new LocalActivityMarkerData.Builder()
            .setActivityId("la-1")
            .setActivityType(ACTIVITY_TYPE)
            .setReplayTimeMillis(1234L)
            .setTaskFailedRequest(
                new RespondActivityTaskFailedRequest()
                    .setReason("java.lang.IllegalStateException")
                    .setDetails(details))
            .setAttempt(2)
            .setBackoff(Duration.ofSeconds(30))
            .build();

    LocalActivityMarkerData decoded = roundTrip(marker);

    assertEquals("la-1", decoded.getActivityId());
    assertEquals(ACTIVITY_TYPE.toString(), decoded.getActivityType());
    assertEquals(1234L, decoded.getReplayTimeMillis());
    assertEquals("java.lang.IllegalStateException", decoded.getErrReason());
    assertArrayEquals(details, decoded.getErrJson());
    assertEquals(2, decoded.getAttempt());
    assertEquals(Duration.ofSeconds(30), decoded.getBackoff());
    assertFalse(decoded.getIsCancelled());
    Throwable failure = converter.fromData(decoded.getErrJson(), Throwable.class, Throwable.class);
    assertEquals(IllegalStateException.class, failure.getClass());
    assertEquals("simulated", failure.getMessage());
  }

  @Test
  public void testCompletedActivityMarkerRoundTrip() {
    assumeDurationIsSupported();
    byte[] result = converter.toData("done");
    LocalActivityMarkerData marker =
        new LocalActivityMarkerData.Builder()
            .setActivityId("la-2")
            .setActivityType(ACTIVITY_TYPE)
            .setReplayTimeMillis(1234L)
            .setResult(result)
            .build();

    LocalActivityMarkerData decoded = roundTrip(marker);

    assertEquals("la-2", decoded.getActivityId());
    assertEquals(ACTIVITY_TYPE.toString(), decoded.getActivityType());
    assertEquals(1234L, decoded.getReplayTimeMillis());
    assertNull(decoded.getErrReason());
    assertNull(decoded.getErrJson());
    assertEquals(0, decoded.getAttempt());
    assertNull(decoded.getBackoff());
    assertFalse(decoded.getIsCancelled());
    assertEquals("done", converter.fromData(decoded.getResult(), String.class, String.class));
  }

  @Test
  public void testCancelledActivityMarkerRoundTrip() {
    assumeDurationIsSupported();
    byte[] details = "cancelled".getBytes(StandardCharsets.UTF_8);
    LocalActivityMarkerData marker =
        new LocalActivityMarkerData.Builder()
            .setActivityId("la-3")
            .setActivityType(ACTIVITY_TYPE)
            .setTaskCancelledRequest(new RespondActivityTaskCanceledRequest().setDetails(details))
            .build();

    LocalActivityMarkerData decoded = roundTrip(marker);

    assertEquals("la-3", decoded.getActivityId());
    assertEquals("cancelled", decoded.getErrReason());
    assertTrue(decoded.getIsCancelled());
    assertArrayEquals(details, decoded.getResult());
  }

  @Test
  public void testGsonHeaderFormatIsUnchanged() {
    assumeTrue("Checks the format of JsonDataConverter", converter instanceof JsonDataConverter);
    assumeDurationIsSupported();
    LocalActivityMarkerData marker =
        new LocalActivityMarkerData.Builder()
            .setActivityId("la-1")
            .setActivityType(ACTIVITY_TYPE)
            .setReplayTimeMillis(1234L)
            .setTaskFailedRequest(
                new RespondActivityTaskFailedRequest().setReason("java.lang.IllegalStateException"))
            .setAttempt(2)
            .setBackoff(Duration.ofSeconds(30))
            .build();

    byte[] header = marker.getHeader(converter).getFields().get(HEADER_KEY);

    assertEquals(GSON_FAILURE_HEADER, new String(header, StandardCharsets.UTF_8));
  }

  @Test
  public void testJacksonHeaderFormat() {
    assumeFalse(
        "Checks the format of JacksonDataConverter", converter instanceof JsonDataConverter);

    byte[] header =
        failedMarker(Duration.ofSeconds(30)).getHeader(converter).getFields().get(HEADER_KEY);

    assertEquals(JACKSON_FAILURE_HEADER, new String(header, StandardCharsets.UTF_8));
  }

  /** JsonDataConverter reads the header written by every converter, for example on a rollback. */
  @Test
  public void testGsonReadsHeaderWrittenByThisConverter() {
    DataConverter gson = JsonDataConverter.getInstance();
    assumeDurationIsSupported(gson);
    LocalActivityMarkerData marker = failedMarker(Duration.ofMillis(1500));
    MarkerRecordedEventAttributes attributes =
        new MarkerRecordedEventAttributes().setHeader(marker.getHeader(converter));

    LocalActivityMarkerData decoded = LocalActivityMarkerData.fromEventAttributes(attributes, gson);

    assertEquals("la-1", decoded.getActivityId());
    assertEquals(ACTIVITY_TYPE.toString(), decoded.getActivityType());
    assertEquals(1234L, decoded.getReplayTimeMillis());
    assertEquals("java.lang.IllegalStateException", decoded.getErrReason());
    assertEquals(2, decoded.getAttempt());
    assertEquals(Duration.ofMillis(1500), decoded.getBackoff());
    assertFalse(decoded.getIsCancelled());
  }

  @Test
  public void testDecodesFailedActivityHeaderRecordedByGson() {
    assumeDurationIsSupported();
    byte[] details = "{}".getBytes(StandardCharsets.UTF_8);

    LocalActivityMarkerData decoded =
        LocalActivityMarkerData.fromEventAttributes(
            markerAttributes(GSON_FAILURE_HEADER, details), converter);

    assertEquals("la-1", decoded.getActivityId());
    assertEquals(ACTIVITY_TYPE.toString(), decoded.getActivityType());
    assertEquals(1234L, decoded.getReplayTimeMillis());
    assertEquals("java.lang.IllegalStateException", decoded.getErrReason());
    assertArrayEquals(details, decoded.getErrJson());
    assertEquals(2, decoded.getAttempt());
    assertEquals(Duration.ofSeconds(30), decoded.getBackoff());
    assertFalse(decoded.getIsCancelled());
  }

  @Test
  public void testDecodesBackoffWithNanosRecordedByGson() {
    assumeDurationIsSupported();
    String header =
        GSON_FAILURE_HEADER.replace(
            "{\"seconds\":30,\"nanos\":0}", "{\"seconds\":1,\"nanos\":500000000}");

    LocalActivityMarkerData decoded =
        LocalActivityMarkerData.fromEventAttributes(markerAttributes(header, null), converter);

    assertEquals(Duration.ofMillis(1500), decoded.getBackoff());
  }

  @Test
  public void testDecodesCompletedActivityHeaderRecordedByGson() {
    assumeDurationIsSupported();
    byte[] result = converter.toData("done");

    LocalActivityMarkerData decoded =
        LocalActivityMarkerData.fromEventAttributes(
            markerAttributes(GSON_SUCCESS_HEADER, result), converter);

    assertEquals("la-2", decoded.getActivityId());
    assertNull(decoded.getErrReason());
    assertNull(decoded.getErrJson());
    assertEquals(0, decoded.getAttempt());
    assertNull(decoded.getBackoff());
    assertFalse(decoded.getIsCancelled());
    assertArrayEquals(result, decoded.getResult());
  }

  private static LocalActivityMarkerData failedMarker(Duration backoff) {
    return new LocalActivityMarkerData.Builder()
        .setActivityId("la-1")
        .setActivityType(ACTIVITY_TYPE)
        .setReplayTimeMillis(1234L)
        .setTaskFailedRequest(
            new RespondActivityTaskFailedRequest().setReason("java.lang.IllegalStateException"))
        .setAttempt(2)
        .setBackoff(backoff)
        .build();
  }

  private LocalActivityMarkerData roundTrip(LocalActivityMarkerData marker) {
    MarkerRecordedEventAttributes attributes =
        new MarkerRecordedEventAttributes()
            .setHeader(marker.getHeader(converter))
            .setDetails(marker.getResult());
    return LocalActivityMarkerData.fromEventAttributes(attributes, converter);
  }

  private static MarkerRecordedEventAttributes markerAttributes(String header, byte[] details) {
    Header markerHeader = new Header();
    markerHeader.getFields().put(HEADER_KEY, header.getBytes(StandardCharsets.UTF_8));
    return new MarkerRecordedEventAttributes().setHeader(markerHeader).setDetails(details);
  }

  /**
   * JsonDataConverter handles java.time.Duration reflectively, which fails when java.base/java.time
   * is not open to it (JDK 16+ without --add-opens).
   */
  private void assumeDurationIsSupported() {
    assumeDurationIsSupported(converter);
  }

  private static void assumeDurationIsSupported(DataConverter converter) {
    if (converter instanceof JsonDataConverter) {
      try {
        converter.toData(Duration.ofSeconds(1));
      } catch (DataConverterException e) {
        assumeNoException("JsonDataConverter cannot access java.time.Duration", e);
      }
    }
  }
}
