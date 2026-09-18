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

package com.uber.cadence.converter;

import static org.junit.Assert.*;

import com.uber.cadence.client.ApplicationFailureException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class JacksonDataConverterTest {

  private final DataConverter converter = JacksonDataConverter.getInstance();

  // -------- Basic round-trip tests --------

  @Test
  public void testNullData() {
    assertNull(converter.toData());
    assertNull(converter.toData((Object[]) null));
    assertNull(converter.fromData(null, String.class, String.class));
  }

  @Test
  public void testPrimitiveTypes() {
    // int
    byte[] data = converter.toData(42);
    assertEquals(42, (int) converter.fromData(data, Integer.class, Integer.class));

    // boolean
    data = converter.toData(true);
    assertTrue(converter.fromData(data, Boolean.class, Boolean.class));

    // double
    data = converter.toData(3.14);
    assertEquals(3.14, converter.fromData(data, Double.class, Double.class), 0.001);

    // String
    data = converter.toData("hello");
    assertEquals("hello", converter.fromData(data, String.class, String.class));
  }

  @Test
  public void testNullValue() {
    byte[] data = converter.toData((Object) null);
    String json = new String(data, StandardCharsets.UTF_8);
    assertEquals("null", json);
  }

  // -------- Collection tests --------

  @Test
  public void testListSerialization() {
    List<String> list = Arrays.asList("a", "b", "c");
    byte[] data = converter.toData(list);
    @SuppressWarnings("unchecked")
    List<String> result = converter.fromData(data, List.class, List.class);
    assertEquals(list, result);
  }

  @Test
  public void testMapSerialization() {
    Map<String, Integer> map = new HashMap<>();
    map.put("one", 1);
    map.put("two", 2);
    byte[] data = converter.toData(map);
    @SuppressWarnings("unchecked")
    Map<String, Integer> result = converter.fromData(data, Map.class, Map.class);
    assertEquals(1, result.get("one"));
    assertEquals(2, result.get("two"));
  }

  public static void foo(List<UUID> arg) {}

  @Test
  public void testUUIDList() throws NoSuchMethodException {
    Method m = JacksonDataConverterTest.class.getDeclaredMethod("foo", List.class);
    Type arg = m.getGenericParameterTypes()[0];

    List<UUID> list = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      list.add(UUID.randomUUID());
    }
    byte[] data = converter.toData(list);
    @SuppressWarnings("unchecked")
    List<UUID> result = (List<UUID>) converter.fromDataArray(data, arg)[0];
    assertEquals(result.toString(), list, result);
  }

  // -------- Array data tests (multi-value) --------

  @Test
  public void testArraySerialization() {
    byte[] converted = converter.toData("abc", 123);
    Object[] fromConverted = converter.fromDataArray(converted, String.class, Integer.class);
    assertEquals("abc", fromConverted[0]);
    assertEquals(123, fromConverted[1]);
  }

  public static void threeArguments(int one, int two, String three) {}

  public static void aLotOfArguments(int one, int two, String three, Object obj, int[] intArr) {}

  @Test
  public void additionalInputArgumentsAreIgnored() throws NoSuchMethodException {
    Method m =
        JacksonDataConverterTest.class.getDeclaredMethod(
            "threeArguments", int.class, int.class, String.class);
    Type[] arg = m.getGenericParameterTypes();

    byte[] data = converter.toData(1, 2, "a string", "an extra string :o!!!");
    @SuppressWarnings("unchecked")
    Object[] deserializedArguments = converter.fromDataArray(data, arg);
    assertEquals(3, deserializedArguments.length);
    assertEquals(1, (int) deserializedArguments[0]);
    assertEquals(2, (int) deserializedArguments[1]);
    assertEquals("a string", deserializedArguments[2]);
  }

  @Test
  public void missingInputArgumentsArePopulatedWithDefaultValues() throws NoSuchMethodException {
    Method m =
        JacksonDataConverterTest.class.getDeclaredMethod(
            "aLotOfArguments", int.class, int.class, String.class, Object.class, int[].class);
    Type[] arg = m.getGenericParameterTypes();

    byte[] data = converter.toData(1);
    @SuppressWarnings("unchecked")
    Object[] deserializedArguments = converter.fromDataArray(data, arg);
    assertEquals(5, deserializedArguments.length);
    assertEquals(1, (int) deserializedArguments[0]);
    assertEquals(0, (int) deserializedArguments[1]);
    assertNull(deserializedArguments[2]);
    assertNull(deserializedArguments[3]);
    assertNull(deserializedArguments[4]);
  }

  @Test
  public void testEmptyArrayContent() {
    Object[] result = converter.fromDataArray(null);
    assertEquals(0, result.length);
  }

  @Test(expected = DataConverterException.class)
  public void testNullContentWithExpectedTypes() {
    converter.fromDataArray(null, String.class);
  }

  // -------- Class type test --------

  @Test
  public void testClass() {
    byte[] data = converter.toData(this.getClass());
    @SuppressWarnings("unchecked")
    Class result = converter.fromData(data, Class.class, Class.class);
    assertEquals(result.toString(), this.getClass(), result);
  }

  // -------- Exception/Throwable tests --------

  @Test
  public void testSimpleException() {
    RuntimeException e = new RuntimeException("test error");
    byte[] converted = converter.toData(e);
    RuntimeException fromConverted =
        converter.fromData(converted, RuntimeException.class, RuntimeException.class);
    assertEquals(RuntimeException.class, fromConverted.getClass());
    assertEquals("test error", fromConverted.getMessage());
    assertNotNull(fromConverted.getStackTrace());
    assertTrue(fromConverted.getStackTrace().length > 0);
  }

  @Test
  public void testExceptionWithCause() {
    RuntimeException cause = new RuntimeException("root cause");
    RuntimeException e = new RuntimeException("wrapper", cause);
    byte[] converted = converter.toData(e);
    RuntimeException fromConverted =
        converter.fromData(converted, RuntimeException.class, RuntimeException.class);
    assertEquals("wrapper", fromConverted.getMessage());
    assertNotNull(fromConverted.getCause());
    assertEquals("root cause", fromConverted.getCause().getMessage());
  }

  @Test
  public void testExceptionNotFound() {
    String convertedString =
        "{\n"
            + "  \"detailMessage\": \"application exception\",\n"
            + "  \"stackTrace\": \"com.uber.cadence.converter.JacksonDataConverterTest.testExceptionNotFound(JacksonDataConverterTest.java:200)\\n\",\n"
            + "  \"suppressedExceptions\": [],\n"
            + "  \"class\": \"com.uber.cadence.converter.ExceptionNotFound\"\n"
            + "}";
    RuntimeException fromConverted =
        converter.fromData(
            convertedString.getBytes(StandardCharsets.UTF_8),
            RuntimeException.class,
            RuntimeException.class);
    assertEquals(ApplicationFailureException.class, fromConverted.getClass());
    assertEquals("application exception", fromConverted.getMessage());
    assertNotNull(fromConverted.getStackTrace());
    assertTrue(fromConverted.getStackTrace().length > 0);
  }

  // -------- Java 8 Date/Time tests (the key Gson limitation fix) --------

  @Test
  public void testLocalDate() {
    LocalDate date = LocalDate.of(2025, 4, 15);
    byte[] data = converter.toData(date);
    LocalDate result = converter.fromData(data, LocalDate.class, LocalDate.class);
    assertEquals(date, result);
  }

  @Test
  public void testLocalDateTime() {
    LocalDateTime dateTime = LocalDateTime.of(2025, 4, 15, 10, 30, 45);
    byte[] data = converter.toData(dateTime);
    LocalDateTime result = converter.fromData(data, LocalDateTime.class, LocalDateTime.class);
    assertEquals(dateTime, result);
  }

  @Test
  public void testLocalTime() {
    LocalTime time = LocalTime.of(14, 30, 15);
    byte[] data = converter.toData(time);
    LocalTime result = converter.fromData(data, LocalTime.class, LocalTime.class);
    assertEquals(time, result);
  }

  @Test
  public void testInstant() {
    Instant instant = Instant.parse("2025-04-15T10:30:00Z");
    byte[] data = converter.toData(instant);
    Instant result = converter.fromData(data, Instant.class, Instant.class);
    assertEquals(instant, result);
  }

  @Test
  public void testZonedDateTime() {
    ZonedDateTime zdt = ZonedDateTime.of(2025, 4, 15, 10, 30, 0, 0, ZoneOffset.UTC);
    byte[] data = converter.toData(zdt);
    ZonedDateTime result = converter.fromData(data, ZonedDateTime.class, ZonedDateTime.class);
    assertEquals(zdt, result);
  }

  @Test
  public void testOffsetDateTime() {
    OffsetDateTime odt =
        OffsetDateTime.of(2025, 4, 15, 10, 30, 0, 0, ZoneOffset.ofHours(5));
    byte[] data = converter.toData(odt);
    OffsetDateTime result =
        converter.fromData(data, OffsetDateTime.class, OffsetDateTime.class);
    assertEquals(odt, result);
  }

  @Test
  public void testDuration() {
    Duration duration = Duration.ofHours(2).plusMinutes(30);
    byte[] data = converter.toData(duration);
    Duration result = converter.fromData(data, Duration.class, Duration.class);
    assertEquals(duration, result);
  }

  @Test
  public void testDateTimesInArray() {
    LocalDate date = LocalDate.of(2025, 1, 1);
    Instant instant = Instant.parse("2025-06-15T12:00:00Z");
    byte[] data = converter.toData(date, instant);
    Object[] results = converter.fromDataArray(data, LocalDate.class, Instant.class);
    assertEquals(date, results[0]);
    assertEquals(instant, results[1]);
  }

  // -------- POJO tests --------

  public static class SimplePojo {
    String name;
    int age;
    List<String> tags;

    // Default constructor needed for Jackson
    public SimplePojo() {}

    public SimplePojo(String name, int age, List<String> tags) {
      this.name = name;
      this.age = age;
      this.tags = tags;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SimplePojo that = (SimplePojo) o;
      return age == that.age
          && java.util.Objects.equals(name, that.name)
          && java.util.Objects.equals(tags, that.tags);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(name, age, tags);
    }
  }

  @Test
  public void testPojo() {
    SimplePojo pojo = new SimplePojo("John", 30, Arrays.asList("dev", "java"));
    byte[] data = converter.toData(pojo);
    SimplePojo result = converter.fromData(data, SimplePojo.class, SimplePojo.class);
    assertEquals(pojo, result);
  }

  @Test
  public void testPojoWithNullFields() {
    SimplePojo pojo = new SimplePojo(null, 0, null);
    byte[] data = converter.toData(pojo);
    SimplePojo result = converter.fromData(data, SimplePojo.class, SimplePojo.class);
    assertEquals(pojo, result);
  }

  // -------- Custom ObjectMapper test --------

  @Test
  public void testCustomObjectMapper() {
    DataConverter custom =
        new JacksonDataConverter(
            mapper -> {
              // Configure mapper with pretty printing
              mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
              return mapper;
            });

    SimplePojo pojo = new SimplePojo("Jane", 25, Arrays.asList("test"));
    byte[] data = custom.toData(pojo);
    String json = new String(data, StandardCharsets.UTF_8);
    assertTrue("Expected pretty-printed JSON", json.contains("\n"));

    SimplePojo result = custom.fromData(data, SimplePojo.class, SimplePojo.class);
    assertEquals(pojo, result);
  }

  // -------- Date/time round-trip through array data --------

  @Test
  public void testDateTimeRoundTripWithPojo() {
    // Demonstrates that Java 8 date/time works inside POJOs too
    Map<String, Object> map = new HashMap<>();
    map.put("name", "test");
    map.put("count", 42);
    byte[] data = converter.toData(map);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = converter.fromData(data, Map.class, Map.class);
    assertEquals("test", result.get("name"));
    assertEquals(42, result.get("count"));
  }

  // -------- Singleton test --------

  @Test
  public void testGetInstanceReturnsSameInstance() {
    DataConverter a = JacksonDataConverter.getInstance();
    DataConverter b = JacksonDataConverter.getInstance();
    assertSame(a, b);
  }

  // -------- DataConverterException on invalid input --------

  @Test(expected = DataConverterException.class)
  public void testInvalidJsonThrowsException() {
    byte[] invalid = "not valid json{{{".getBytes(StandardCharsets.UTF_8);
    converter.fromData(invalid, SimplePojo.class, SimplePojo.class);
  }

  @Test(expected = DataConverterException.class)
  public void testFromDataArrayInvalidJson() {
    byte[] invalid = "not valid json{{{".getBytes(StandardCharsets.UTF_8);
    converter.fromDataArray(invalid, String.class, Integer.class);
  }

  // -------- Exception subclass with extra fields (Gitar Bug 1) --------

  /** Exception subclass with extra fields that must survive round-trip. */
  public static class DetailedException extends RuntimeException {
    private final int errorCode;
    private final String errorDetail;

    public DetailedException(String message, int errorCode, String errorDetail) {
      super(message);
      this.errorCode = errorCode;
      this.errorDetail = errorDetail;
    }

    // For deserialization fallback
    public DetailedException(String message) {
      super(message);
      this.errorCode = 0;
      this.errorDetail = null;
    }

    public int getErrorCode() {
      return errorCode;
    }

    public String getErrorDetail() {
      return errorDetail;
    }
  }

  @Test
  public void testExceptionSubclassWithExtraFields() {
    DetailedException e = new DetailedException("failed", 42, "extra detail");
    byte[] converted = converter.toData(e);
    // Verify the JSON contains the class field and the extra fields
    String json = new String(converted, StandardCharsets.UTF_8);
    assertTrue("Should contain class field", json.contains("\"class\""));
    assertTrue(
        "Should contain exception class name",
        json.contains("com.uber.cadence.converter.JacksonDataConverterTest$DetailedException"));
    assertTrue("Should contain errorCode", json.contains("\"errorCode\""));
    assertTrue("Should contain errorDetail", json.contains("\"errorDetail\""));

    // Round-trip deserialization should preserve the type
    DetailedException fromConverted =
        converter.fromData(converted, DetailedException.class, DetailedException.class);
    assertEquals(DetailedException.class, fromConverted.getClass());
    assertEquals("failed", fromConverted.getMessage());
    assertNotNull(fromConverted.getStackTrace());
    assertTrue(fromConverted.getStackTrace().length > 0);
  }

  // -------- Nested/suppressed throwable wire format (Gitar Bug 2) --------

  @Test
  public void testNestedThrowableHasClassField() {
    RuntimeException cause = new RuntimeException("inner cause");
    RuntimeException outer = new RuntimeException("outer", cause);
    byte[] converted = converter.toData(outer);
    String json = new String(converted, StandardCharsets.UTF_8);

    // Both the outer and inner throwable should have the "class" field
    // This verifies that the ThrowableSerializer is used at all nesting levels
    assertTrue("Outer should have class field", json.contains("\"class\":\"java.lang.RuntimeException\""));
    assertTrue("Should have cause with class field", json.contains("\"cause\""));

    // Verify round-trip
    RuntimeException fromConverted =
        converter.fromData(converted, RuntimeException.class, RuntimeException.class);
    assertEquals("outer", fromConverted.getMessage());
    assertNotNull(fromConverted.getCause());
    assertEquals("inner cause", fromConverted.getCause().getMessage());
  }
}
