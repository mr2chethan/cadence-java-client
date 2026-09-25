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

package com.uber.cadence.internal.replay;

import static com.uber.cadence.converter.JacksonDataConverterTest.compatibilityLogOf;
import static com.uber.cadence.converter.JacksonDataConverterTest.newCustomizedConverter;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.google.common.base.Splitter;
import com.uber.cadence.EventType;
import com.uber.cadence.Header;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.MarkerRecordedEventAttributes;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JacksonDataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;

/**
 * The headers of version and mutable side effect markers are encoded with the data converter of the
 * worker, so every converter has to be able to decode them, including headers recorded by
 * JsonDataConverter.
 */
@RunWith(Parameterized.class)
public class MarkerHandlerDataConverterTest {

  private static final String HEADER_KEY = "MutableMarkerHeader";

  /** Header of a marker recorded by JsonDataConverter. */
  private static final String GSON_HEADER = "{\"id\":\"cid1\",\"eventId\":5,\"accessCount\":0}";

  /**
   * Details of a marker recorded before the header existed, by JsonDataConverter. The data is the
   * encoded integer 3.
   */
  private static final String GSON_LEGACY_DETAILS =
      "{\"id\":\"cid1\",\"eventId\":5,\"data\":[51],\"accessCount\":1}";

  private final DataConverter converter;

  public MarkerHandlerDataConverterTest(String name, DataConverter converter) {
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
  public void testMarkerHeaderRoundTrip() {
    byte[] data = converter.toData(3);
    MarkerHandler.MarkerData marker = new MarkerHandler.MarkerData("cid1", 5, data, 2);

    MarkerHandler.MarkerInterface decoded =
        MarkerHandler.MarkerInterface.fromEventAttributes(
            markerAttributes(marker.getHeader(converter), data), converter);

    assertEquals("cid1", decoded.getId());
    assertEquals(5L, decoded.getEventId());
    assertEquals(2, decoded.getAccessCount());
    assertArrayEquals(data, decoded.getData());
  }

  @Test
  public void testMarkerHeaderIsReadableByAllConverters() {
    Header header = new MarkerHandler.MarkerData("cid1", 5, null, 1).getHeader(converter);

    for (DataConverter reader :
        new DataConverter[] {
          JsonDataConverter.getInstance(),
          JacksonDataConverter.getInstance(),
          newCustomizedConverter()
        }) {
      MarkerHandler.MarkerInterface decoded =
          MarkerHandler.MarkerInterface.fromEventAttributes(markerAttributes(header, null), reader);
      assertEquals("cid1", decoded.getId());
      assertEquals(5L, decoded.getEventId());
      assertEquals(1, decoded.getAccessCount());
    }
  }

  @Test
  public void testGsonMarkerHeaderFormatIsUnchanged() {
    assumeTrue("Checks the format of JsonDataConverter", converter instanceof JsonDataConverter);

    Header header = new MarkerHandler.MarkerData("cid1", 5, null, 0).getHeader(converter);

    assertEquals(
        GSON_HEADER, new String(header.getFields().get(HEADER_KEY), StandardCharsets.UTF_8));
  }

  @Test
  public void testDecodesMarkerHeaderRecordedByGson() {
    Header header = new Header();
    header.getFields().put(HEADER_KEY, GSON_HEADER.getBytes(StandardCharsets.UTF_8));
    byte[] data = converter.toData(3);

    MarkerHandler.MarkerInterface decoded =
        MarkerHandler.MarkerInterface.fromEventAttributes(
            markerAttributes(header, data), converter);

    assertEquals("cid1", decoded.getId());
    assertEquals(5L, decoded.getEventId());
    assertEquals(0, decoded.getAccessCount());
    assertArrayEquals(data, decoded.getData());
  }

  @Test
  public void testLegacyMarkerWithoutHeaderRoundTrip() {
    byte[] data = converter.toData(3);
    byte[] details = converter.toData(new MarkerHandler.PlainMarkerData("cid1", 5, data, 1));

    MarkerHandler.MarkerInterface decoded =
        MarkerHandler.MarkerInterface.fromEventAttributes(
            markerAttributes(null, details), converter);

    assertEquals("cid1", decoded.getId());
    assertEquals(5L, decoded.getEventId());
    assertEquals(1, decoded.getAccessCount());
    assertArrayEquals(data, decoded.getData());
  }

  @Test
  public void testDecodesLegacyMarkerRecordedByGson() {
    MarkerHandler.MarkerInterface decoded =
        MarkerHandler.MarkerInterface.fromEventAttributes(
            markerAttributes(null, GSON_LEGACY_DETAILS.getBytes(StandardCharsets.UTF_8)),
            converter);

    assertEquals("cid1", decoded.getId());
    assertEquals(5L, decoded.getEventId());
    assertEquals(1, decoded.getAccessCount());
    assertEquals(
        Integer.valueOf(3), converter.fromData(decoded.getData(), Integer.class, Integer.class));
  }

  /**
   * Records a version marker the way Workflow.getVersion does and then replays it, which decodes
   * the recorded header with the same converter.
   */
  @Test
  public void testRecordedMarkerIsFoundOnReplay() {
    byte[] version = converter.toData(3);
    DecisionsHelper recordingDecisions = mock(DecisionsHelper.class);
    when(recordingDecisions.getNextDecisionEventId()).thenReturn(5L);
    MarkerHandler recorder =
        new MarkerHandler(
            recordingDecisions, ClockDecisionContext.VERSION_MARKER_NAME, () -> false);

    MarkerHandler.HandleResult recorded =
        recorder.handle("changeId", converter, stored -> Optional.of(version));

    assertTrue(recorded.isNewlyStored());
    ArgumentCaptor<Header> header = ArgumentCaptor.forClass(Header.class);
    verify(recordingDecisions)
        .recordMarker(eq(ClockDecisionContext.VERSION_MARKER_NAME), header.capture(), eq(version));

    HistoryEvent event = new HistoryEvent();
    event.setEventId(5L);
    event.setEventType(EventType.MarkerRecorded);
    event.setMarkerRecordedEventAttributes(
        markerAttributes(header.getValue(), version)
            .setMarkerName(ClockDecisionContext.VERSION_MARKER_NAME));
    DecisionsHelper replayDecisions = mock(DecisionsHelper.class);
    when(replayDecisions.getNextDecisionEventId()).thenReturn(5L);
    when(replayDecisions.getOptionalDecisionEvent(5L)).thenReturn(Optional.of(event));
    MarkerHandler replayer =
        new MarkerHandler(replayDecisions, ClockDecisionContext.VERSION_MARKER_NAME, () -> true);

    MarkerHandler.HandleResult replayed =
        replayer.handle(
            "changeId",
            converter,
            stored -> {
              fail("The recorded value must be used on replay");
              return Optional.empty();
            });

    assertFalse(replayed.isNewlyStored());
    assertTrue(replayed.getStoredData().isPresent());
    assertEquals(
        Integer.valueOf(3),
        converter.fromData(replayed.getStoredData().get(), Integer.class, Integer.class));
    verify(replayDecisions)
        .recordMarker(eq(ClockDecisionContext.VERSION_MARKER_NAME), any(Header.class), eq(version));
  }

  /** A header and details with a property this version does not know, as a newer client writes. */
  @Test
  public void testUnknownPropertiesAreIgnoredSilently() {
    Header header = new Header();
    header
        .getFields()
        .put(
            HEADER_KEY,
            "{\"id\":\"cid1\",\"eventId\":5,\"accessCount\":0,\"markerHeaderTestExtra\":[1]}"
                .getBytes(StandardCharsets.UTF_8));
    byte[] details =
        ("{\"id\":\"cid2\",\"eventId\":6,\"data\":null,\"accessCount\":1,"
                + "\"markerDetailsTestExtra\":{}}")
            .getBytes(StandardCharsets.UTF_8);
    List<MarkerHandler.MarkerInterface> decoded = new ArrayList<>();

    List<ILoggingEvent> logged =
        compatibilityLogOf(
            () -> {
              decoded.add(
                  MarkerHandler.MarkerInterface.fromEventAttributes(
                      markerAttributes(header, null), converter));
              decoded.add(
                  MarkerHandler.MarkerInterface.fromEventAttributes(
                      markerAttributes(null, details), converter));
            });

    assertEquals(Collections.emptyList(), logged);
    assertEquals("cid1", decoded.get(0).getId());
    assertEquals(5L, decoded.get(0).getEventId());
    assertEquals(0, decoded.get(0).getAccessCount());
    assertEquals("cid2", decoded.get(1).getId());
    assertEquals(6L, decoded.get(1).getEventId());
    assertEquals(1, decoded.get(1).getAccessCount());
    assertNull(decoded.get(1).getData());
  }

  private static MarkerRecordedEventAttributes markerAttributes(Header header, byte[] details) {
    return new MarkerRecordedEventAttributes().setHeader(header).setDetails(details);
  }
}
