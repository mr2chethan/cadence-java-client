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

package com.uber.cadence.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import com.google.common.base.Splitter;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.converter.JacksonDataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Workflow.retry records its RetryOptions with the data converter of the worker (as a mutable side
 * effect) and compares the recorded value with equals, so the options have to survive a round trip
 * through every converter unchanged.
 */
@RunWith(Parameterized.class)
public class RetryOptionsDataConverterTest {

  private final DataConverter converter;

  public RetryOptionsDataConverterTest(String name, DataConverter converter) {
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
          break;
        default:
          throw new IllegalArgumentException("Unknown converter: " + name);
      }
    }
    return result;
  }

  @Test
  public void testRoundTripKeepsOptionsEqual() {
    assumeDurationIsSupported();
    RetryOptions options = fullOptions();

    RetryOptions decoded = roundTrip(options);

    assertEquals(options, decoded);
    assertEquals(options.hashCode(), decoded.hashCode());
    assertEquals(Duration.ofSeconds(1), decoded.getInitialInterval());
    assertEquals(Duration.ofMinutes(5), decoded.getExpiration());
    assertEquals(Duration.ofMillis(10500), decoded.getMaximumInterval());
    assertEquals(1.5, decoded.getBackoffCoefficient(), 0);
    assertEquals(4, decoded.getMaximumAttempts());
    assertEquals(
        Arrays.asList(IllegalStateException.class, QuotaException.class), decoded.getDoNotRetry());
  }

  @Test
  public void testRoundTripKeepsUnsetOptions() {
    assumeDurationIsSupported();
    RetryOptions options =
        new RetryOptions.Builder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(3)
            .validateBuildWithDefaults();

    RetryOptions decoded = roundTrip(options);

    assertEquals(options, decoded);
    assertNull(decoded.getExpiration());
    assertNull(decoded.getMaximumInterval());
    // Null means that the doNotRetry of a MethodRetry annotation applies.
    assertNull(decoded.getDoNotRetry());
  }

  @Test
  public void testRoundTripKeepsEmptyDoNotRetry() {
    assumeDurationIsSupported();
    RetryOptions options =
        new RetryOptions.Builder()
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumAttempts(3)
            .setDoNotRetry()
            .validateBuildWithDefaults();

    RetryOptions decoded = roundTrip(options);

    assertEquals(options, decoded);
    // Empty, unlike null, means that every exception is retried.
    assertEquals(Collections.emptyList(), decoded.getDoNotRetry());
  }

  @Test
  public void testDecodedOptionsDecideLikeOriginal() {
    assumeDurationIsSupported();
    RetryOptions options = fullOptions();

    RetryOptions decoded = roundTrip(options);

    assertTrue(decoded.shouldRethrow(new QuotaException("quota"), 1, 0, 0));
    assertTrue(decoded.shouldRethrow(new IllegalStateException(), 1, 0, 0));
    assertFalse(decoded.shouldRethrow(new IllegalArgumentException(), 1, 0, 0));
    assertTrue(decoded.shouldRethrow(new IllegalArgumentException(), 4, 0, 0));
    for (int attempt = 1; attempt <= 4; attempt++) {
      assertEquals(options.calculateSleepTime(attempt), decoded.calculateSleepTime(attempt));
    }
  }

  private RetryOptions roundTrip(RetryOptions options) {
    byte[] data = converter.toData(options);
    return converter.fromData(data, RetryOptions.class, RetryOptions.class);
  }

  private static RetryOptions fullOptions() {
    return new RetryOptions.Builder()
        .setInitialInterval(Duration.ofSeconds(1))
        .setExpiration(Duration.ofMinutes(5))
        .setMaximumInterval(Duration.ofMillis(10500))
        .setBackoffCoefficient(1.5)
        .setMaximumAttempts(4)
        .setDoNotRetry(IllegalStateException.class, QuotaException.class)
        .validateBuildWithDefaults();
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

  /** An application exception, which the options refer to by its class name. */
  static final class QuotaException extends RuntimeException {
    QuotaException(String message) {
      super(message);
    }
  }
}
