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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.uber.cadence.ActivityType;
import com.uber.cadence.TimeoutType;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.ApplicationFailureException;
import com.uber.cadence.client.WorkflowFailureException;
import com.uber.cadence.workflow.ActivityFailureException;
import com.uber.cadence.workflow.ActivityTimeoutException;
import com.uber.cadence.workflow.ChildWorkflowFailureException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IllegalFormatConversionException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
    assertEquals((Integer) 1, result.get("one"));
    assertEquals((Integer) 2, result.get("two"));
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

    // Test with a region zone to ensure WRITE_DATES_WITH_ZONE_ID is working
    ZonedDateTime zdtRegion =
        ZonedDateTime.of(2025, 4, 15, 10, 30, 0, 0, ZoneId.of("America/New_York"));
    byte[] dataRegion = converter.toData(zdtRegion);
    ZonedDateTime resultRegion =
        converter.fromData(dataRegion, ZonedDateTime.class, ZonedDateTime.class);
    assertEquals(zdtRegion, resultRegion);
  }

  @Test
  public void testOffsetDateTime() {
    OffsetDateTime odt = OffsetDateTime.of(2025, 4, 15, 10, 30, 0, 0, ZoneOffset.ofHours(5));
    byte[] data = converter.toData(odt);
    OffsetDateTime result = converter.fromData(data, OffsetDateTime.class, OffsetDateTime.class);
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
    private transient String transientField = "transient";
    private static String staticField = "static";

    public DetailedException(String message, int errorCode, String errorDetail) {
      super(message);
      this.errorCode = errorCode;
      this.errorDetail = errorDetail;
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
    assertFalse("transient field must be skipped", json.contains("transientField"));
    assertFalse("static field must be skipped", json.contains("staticField"));
    assertEquals("transient", e.transientField);
    assertEquals("static", DetailedException.staticField);

    // Round-trip deserialization should preserve the type and fields
    DetailedException fromConverted =
        converter.fromData(converted, DetailedException.class, DetailedException.class);
    assertEquals(DetailedException.class, fromConverted.getClass());
    assertEquals("failed", fromConverted.getMessage());
    assertEquals(42, fromConverted.getErrorCode());
    assertEquals("extra detail", fromConverted.getErrorDetail());
    assertNotNull(fromConverted.getStackTrace());
    assertTrue(fromConverted.getStackTrace().length > 0);
  }

  @Test
  public void testExceptionSubclassWithInvalidField() {
    DetailedException e = new DetailedException("failed", 42, "extra detail");
    byte[] converted = converter.toData(e);
    String json = new String(converted, StandardCharsets.UTF_8);
    // Replace "errorCode":42 with "errorCode":["invalid"] to cause a deserialization exception
    json = json.replace("\"errorCode\":42", "\"errorCode\":[\"invalid\"]");

    // Deserialization should succeed for the exception, but log a warning and leave errorCode as 0
    DetailedException fromConverted =
        converter.fromData(
            json.getBytes(StandardCharsets.UTF_8),
            DetailedException.class,
            DetailedException.class);
    assertEquals("failed", fromConverted.getMessage());
    assertEquals(0, fromConverted.getErrorCode()); // Default value since it failed to restore
    assertEquals("extra detail", fromConverted.getErrorDetail());
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
    assertTrue(
        "Outer should have class field", json.contains("\"class\":\"java.lang.RuntimeException\""));
    assertTrue("Should have cause with class field", json.contains("\"cause\""));

    // Verify round-trip
    RuntimeException fromConverted =
        converter.fromData(converted, RuntimeException.class, RuntimeException.class);
    assertEquals("outer", fromConverted.getMessage());
    assertNotNull(fromConverted.getCause());
    assertEquals("inner cause", fromConverted.getCause().getMessage());
  }

  private static byte[] utf8(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String asString(byte[] data) {
    return new String(data, StandardCharsets.UTF_8);
  }

  // -------- Empty payloads (JsonDataConverter compatibility) --------

  private static List<byte[]> blankPayloads() {
    return Arrays.asList(new byte[0], utf8(" \n\t\r"));
  }

  @Test
  public void testBlankPayloadDecodesAsNull() {
    DataConverter json = JsonDataConverter.getInstance();
    for (byte[] blank : blankPayloads()) {
      for (DataConverter c : Arrays.asList(converter, json)) {
        String name = c.getClass().getSimpleName();
        assertNull(name, c.fromData(blank, String.class, String.class));
        assertNull(name, c.fromData(blank, Integer.class, Integer.class));
        assertNull(name, c.fromData(blank, int.class, int.class));
        assertNull(name, c.fromData(blank, Void.class, Void.class));
        assertNull(name, c.fromData(blank, SimplePojo.class, SimplePojo.class));
        assertNull(name, c.fromData(blank, RuntimeException.class, RuntimeException.class));
      }
    }
  }

  @Test
  public void testBlankPayloadArrayMatchesJsonDataConverter() {
    Type listOfStrings = new TypeReference<List<String>>() {}.getType();
    DataConverter json = JsonDataConverter.getInstance();
    for (byte[] blank : blankPayloads()) {
      for (DataConverter c : Arrays.asList(converter, json)) {
        String name = c.getClass().getSimpleName();
        // Read as a single JSON null: the first argument is null, the others get defaults.
        assertArrayEquals(
            name, new Object[] {null, 0}, c.fromDataArray(blank, String.class, int.class));
        assertArrayEquals(
            name, new Object[] {null, null}, c.fromDataArray(blank, int.class, String.class));
        assertArrayEquals(
            name,
            new Object[] {null, null, false},
            c.fromDataArray(blank, String.class, listOfStrings, boolean.class));
        assertArrayEquals(name, new Object[] {null}, c.fromDataArray(blank, String.class));
        assertArrayEquals(name, new Object[0], c.fromDataArray(blank));
      }
    }
  }

  @Test
  public void testJsonNullDecodesAsNullException() {
    byte[] nullJson = converter.toData((Object) null);
    assertNull(converter.fromData(nullJson, RuntimeException.class, RuntimeException.class));
    assertNull(converter.fromData(nullJson, Throwable.class, Throwable.class));

    byte[] data = converter.toData("x", null);
    assertArrayEquals(
        new Object[] {"x", null},
        converter.fromDataArray(data, String.class, RuntimeException.class));
  }

  // -------- Sets keep the order of the payload --------

  public enum Color {
    RED,
    ORANGE,
    YELLOW,
    GREEN,
    BLUE,
    INDIGO,
    VIOLET,
    BLACK
  }

  /** Has no equals and hashCode, so a HashSet would order its instances by identity hash. */
  public static class Item {
    String name;

    public Item() {}

    Item(String name) {
      this.name = name;
    }
  }

  public static class SetHolder {
    Set<Color> colors;
    AbstractSet<String> names;
    SortedSet<String> sorted;
  }

  public static void setArgument(Set<Color> colors) {}

  private static final String REVERSED_COLORS =
      "[\"BLACK\",\"VIOLET\",\"INDIGO\",\"BLUE\",\"GREEN\",\"YELLOW\",\"ORANGE\",\"RED\"]";

  private static List<Color> reversedColors() {
    List<Color> colors = new ArrayList<>(Arrays.asList(Color.values()));
    Collections.reverse(colors);
    return colors;
  }

  @Test
  public void testSetKeepsPayloadOrder() {
    Type setOfStrings = new TypeReference<Set<String>>() {}.getType();
    @SuppressWarnings("unchecked")
    Set<String> strings = converter.fromData(utf8("[\"b\",\"a\",\"c\"]"), Set.class, setOfStrings);
    assertEquals(LinkedHashSet.class, strings.getClass());
    assertEquals(Arrays.asList("b", "a", "c"), new ArrayList<>(strings));

    Set<?> raw = converter.fromData(utf8("[\"b\",\"a\"]"), Set.class, Set.class);
    assertEquals(LinkedHashSet.class, raw.getClass());
    assertEquals(Arrays.asList("b", "a"), new ArrayList<>(raw));
  }

  @Test
  public void testEnumSetKeepsPayloadOrder() {
    Type setOfColors = new TypeReference<Set<Color>>() {}.getType();
    @SuppressWarnings("unchecked")
    Set<Color> colors = converter.fromData(utf8(REVERSED_COLORS), Set.class, setOfColors);
    assertEquals(LinkedHashSet.class, colors.getClass());
    assertEquals(reversedColors(), new ArrayList<>(colors));
  }

  @Test
  public void testSetOfObjectsWithoutHashCodeKeepsPayloadOrder() {
    Type setOfItems = new TypeReference<Set<Item>>() {}.getType();
    byte[] data = utf8("[{\"name\":\"c\"},{\"name\":\"a\"},{\"name\":\"d\"},{\"name\":\"b\"}]");
    @SuppressWarnings("unchecked")
    Set<Item> items = converter.fromData(data, Set.class, setOfItems);
    assertEquals(LinkedHashSet.class, items.getClass());
    List<String> names = new ArrayList<>();
    for (Item item : items) {
      names.add(item.name);
    }
    assertEquals(Arrays.asList("c", "a", "d", "b"), names);
  }

  @Test
  public void testSetFieldsKeepPayloadOrder() {
    byte[] data =
        utf8(
            "{\"colors\":[\"BLUE\",\"RED\",\"GREEN\"],"
                + "\"names\":[\"z\",\"a\",\"m\"],"
                + "\"sorted\":[\"z\",\"a\",\"m\"]}");
    SetHolder holder = converter.fromData(data, SetHolder.class, SetHolder.class);
    assertEquals(LinkedHashSet.class, holder.colors.getClass());
    assertEquals(Arrays.asList(Color.BLUE, Color.RED, Color.GREEN), new ArrayList<>(holder.colors));
    assertEquals(LinkedHashSet.class, holder.names.getClass());
    assertEquals(Arrays.asList("z", "a", "m"), new ArrayList<>(holder.names));
    // A sorted set stays sorted.
    assertEquals(TreeSet.class, holder.sorted.getClass());
    assertEquals(Arrays.asList("a", "m", "z"), new ArrayList<>(holder.sorted));
  }

  @Test
  public void testSetArgumentKeepsPayloadOrder() throws NoSuchMethodException {
    Method m = JacksonDataConverterTest.class.getDeclaredMethod("setArgument", Set.class);
    Type arg = m.getGenericParameterTypes()[0];
    Set<Color> colors = new LinkedHashSet<>(reversedColors());

    Object[] single = converter.fromDataArray(converter.toData(colors), arg);
    assertEquals(LinkedHashSet.class, single[0].getClass());
    assertEquals(reversedColors(), new ArrayList<>((Set<?>) single[0]));

    Object[] several = converter.fromDataArray(converter.toData("id", colors), String.class, arg);
    assertEquals("id", several[0]);
    assertEquals(reversedColors(), new ArrayList<>((Set<?>) several[1]));
  }

  @Test
  public void testSetSerializationIsUnchanged() {
    Set<String> strings = new LinkedHashSet<>(Arrays.asList("b", "a"));
    assertEquals("[\"b\",\"a\"]", asString(converter.toData(strings)));
    assertEquals(
        REVERSED_COLORS, asString(converter.toData(new LinkedHashSet<>(reversedColors()))));
  }

  // -------- Classes without a constructor Jackson can use --------

  /** Immutable class with an all-args constructor only. */
  public static final class ImmutableOrder {
    static final AtomicInteger constructorCalls = new AtomicInteger();

    private final String id;
    private final int quantity;
    private final List<String> items;

    public ImmutableOrder(String id, int quantity, List<String> items) {
      constructorCalls.incrementAndGet();
      this.id = id;
      this.quantity = quantity;
      this.items = items;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ImmutableOrder)) {
        return false;
      }
      ImmutableOrder that = (ImmutableOrder) o;
      return quantity == that.quantity
          && Objects.equals(id, that.id)
          && Objects.equals(items, that.items);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, quantity, items);
    }

    @Override
    public String toString() {
      return "ImmutableOrder{" + id + ", " + quantity + ", " + items + "}";
    }
  }

  /** Shaped like a Lombok {@code @Value} class: final fields and a package-private constructor. */
  public static final class ValueStyle {
    private final String name;
    private final long amount;

    ValueStyle(String name, long amount) {
      this.name = name;
      this.amount = amount;
    }

    public String getName() {
      return name;
    }

    public long getAmount() {
      return amount;
    }
  }

  public static final class PrivateNoArgConstructor {
    private String name;
    private String createdBy;

    private PrivateNoArgConstructor() {
      createdBy = "constructor";
    }
  }

  @SuppressWarnings("ClassCanBeStatic")
  public class InnerValue {
    private final String label;
    private final int count;

    public InnerValue(String label, int count) {
      this.label = label;
      this.count = count;
    }

    JacksonDataConverterTest outer() {
      return JacksonDataConverterTest.this;
    }
  }

  public static final class Box<T> {
    private final T value;

    public Box(T value) {
      this.value = value;
    }
  }

  /** Its only constructor takes a String, which Jackson uses for a JSON string. */
  public static final class Sku {
    private final String code;

    public Sku(String code) {
      this.code = code.toUpperCase(Locale.ROOT);
    }
  }

  public static final class ExplicitCreator {
    private final String value;

    @JsonCreator
    ExplicitCreator(@JsonProperty("value") String value) {
      this.value = "created:" + value;
    }
  }

  /** A JDK collection subclass without a no-arg constructor. */
  public static final class TagList extends ArrayList<String> {
    public TagList(String first) {
      add(first);
    }
  }

  public interface Shape {}

  public abstract static class AbstractShape implements Shape {
    int sides;
  }

  private static ImmutableOrder newOrder(String id) {
    return new ImmutableOrder(id, 2, Arrays.asList("apple", "pear"));
  }

  @Test
  public void testClassWithAllArgsConstructorOnly() {
    ImmutableOrder order = newOrder("o-1");
    byte[] data = converter.toData(order);
    int constructorCalls = ImmutableOrder.constructorCalls.get();
    ImmutableOrder result = converter.fromData(data, ImmutableOrder.class, ImmutableOrder.class);
    assertEquals(order, result);
    // Like Gson, the constructor is not run.
    assertEquals(constructorCalls, ImmutableOrder.constructorCalls.get());
  }

  @Test
  public void testValueStyleClass() {
    byte[] data = converter.toData(new ValueStyle("fee", 12L));
    ValueStyle result = converter.fromData(data, ValueStyle.class, ValueStyle.class);
    assertEquals("fee", result.getName());
    assertEquals(12L, result.getAmount());
  }

  @Test
  public void testPrivateNoArgConstructorIsStillUsed() {
    PrivateNoArgConstructor result =
        converter.fromData(
            utf8("{\"name\":\"n\"}"), PrivateNoArgConstructor.class, PrivateNoArgConstructor.class);
    assertEquals("n", result.name);
    assertEquals("constructor", result.createdBy);
  }

  @Test
  public void testNonStaticInnerClass() {
    byte[] data = converter.toData(new InnerValue("inner", 3));
    InnerValue result = converter.fromData(data, InnerValue.class, InnerValue.class);
    assertEquals("inner", result.label);
    assertEquals(3, result.count);
    assertNull(result.outer());
  }

  @Test
  public void testGenericClassWithoutNoArgConstructor() {
    Type type = new TypeReference<Box<ImmutableOrder>>() {}.getType();
    byte[] data = converter.toData(new Box<>(newOrder("o-2")));
    @SuppressWarnings("unchecked")
    Box<ImmutableOrder> result = converter.fromData(data, Box.class, type);
    assertEquals(newOrder("o-2"), result.value);
  }

  @Test
  public void testClassesWithoutNoArgConstructorInContainers() {
    List<ImmutableOrder> list = Arrays.asList(newOrder("a"), newOrder("b"));
    Type listType = new TypeReference<List<ImmutableOrder>>() {}.getType();
    @SuppressWarnings("unchecked")
    List<ImmutableOrder> listResult =
        converter.fromData(converter.toData(list), List.class, listType);
    assertEquals(list, listResult);

    Map<String, ImmutableOrder> map = new HashMap<>();
    map.put("c", newOrder("c"));
    Type mapType = new TypeReference<Map<String, ImmutableOrder>>() {}.getType();
    @SuppressWarnings("unchecked")
    Map<String, ImmutableOrder> mapResult =
        converter.fromData(converter.toData(map), Map.class, mapType);
    assertEquals(map, mapResult);

    ImmutableOrder[] array = {newOrder("d")};
    ImmutableOrder[] arrayResult =
        converter.fromData(
            converter.toData((Object) array), ImmutableOrder[].class, ImmutableOrder[].class);
    assertArrayEquals(array, arrayResult);

    Object[] arguments =
        converter.fromDataArray(
            converter.toData("x", newOrder("e")), String.class, ImmutableOrder.class);
    assertEquals(newOrder("e"), arguments[1]);
  }

  @Test
  public void testClassWithStringConstructorOnly() {
    // A JSON string goes through the constructor.
    assertEquals("ABC", converter.fromData(utf8("\"abc\""), Sku.class, Sku.class).code);
    // A JSON object sets the fields without running the constructor.
    assertEquals("abc", converter.fromData(utf8("{\"code\":\"abc\"}"), Sku.class, Sku.class).code);
    byte[] data = converter.toData(new Sku("xyz"));
    assertEquals("XYZ", converter.fromData(data, Sku.class, Sku.class).code);
  }

  @Test
  public void testExplicitCreatorIsStillUsed() {
    ExplicitCreator result =
        converter.fromData(utf8("{\"value\":\"a\"}"), ExplicitCreator.class, ExplicitCreator.class);
    assertEquals("created:a", result.value);
  }

  @Test
  public void testJdkSubclassWithoutNoArgConstructorIsNotInstantiated() {
    // Skipping the constructors of a JDK class would leave its internal state uninitialized.
    assertThrows(
        DataConverterException.class,
        () -> converter.fromData(utf8("[\"a\",\"b\"]"), TagList.class, TagList.class));
  }

  @Test
  public void testAbstractTypesAreNotInstantiated() {
    byte[] data = utf8("{\"sides\":3}");
    assertThrows(
        DataConverterException.class, () -> converter.fromData(data, Shape.class, Shape.class));
    assertThrows(
        DataConverterException.class,
        () -> converter.fromData(data, AbstractShape.class, AbstractShape.class));
  }

  // -------- Optional --------

  public static class OptionalHolder {
    Optional<String> text;
    Optional<SimplePojo> pojo;
    OptionalInt count;
    OptionalLong total;
    OptionalDouble ratio;
  }

  @Test
  public void testOptionalFieldsWithValues() {
    OptionalHolder holder = new OptionalHolder();
    holder.text = Optional.of("x");
    holder.pojo = Optional.of(new SimplePojo("p", 1, Arrays.asList("t")));
    holder.count = OptionalInt.of(3);
    holder.total = OptionalLong.of(4L);
    holder.ratio = OptionalDouble.of(0.5);

    byte[] data = converter.toData(holder);
    String json = asString(data);
    // The contained value is written, as if the field were not an Optional.
    assertTrue(json, json.contains("\"text\":\"x\""));
    assertTrue(json, json.contains("\"pojo\":{\"name\":\"p\""));
    assertTrue(json, json.contains("\"count\":3"));
    assertTrue(json, json.contains("\"total\":4"));
    assertTrue(json, json.contains("\"ratio\":0.5"));

    OptionalHolder result = converter.fromData(data, OptionalHolder.class, OptionalHolder.class);
    assertEquals(Optional.of("x"), result.text);
    assertEquals(holder.pojo, result.pojo);
    assertEquals(OptionalInt.of(3), result.count);
    assertEquals(OptionalLong.of(4L), result.total);
    assertEquals(OptionalDouble.of(0.5), result.ratio);
  }

  @Test
  public void testEmptyOptionalFields() {
    OptionalHolder holder = new OptionalHolder();
    holder.text = Optional.empty();
    holder.pojo = Optional.empty();
    holder.count = OptionalInt.empty();
    holder.total = OptionalLong.empty();
    holder.ratio = OptionalDouble.empty();

    byte[] data = converter.toData(holder);
    assertEquals(
        "{\"text\":null,\"pojo\":null,\"count\":null,\"total\":null,\"ratio\":null}",
        asString(data));

    OptionalHolder result = converter.fromData(data, OptionalHolder.class, OptionalHolder.class);
    assertEquals(Optional.empty(), result.text);
    assertEquals(Optional.empty(), result.pojo);
    assertEquals(OptionalInt.empty(), result.count);
    assertEquals(OptionalLong.empty(), result.total);
    assertEquals(OptionalDouble.empty(), result.ratio);
  }

  @Test
  public void testTopLevelOptional() {
    Type optionalString = new TypeReference<Optional<String>>() {}.getType();
    assertEquals("\"x\"", asString(converter.toData(Optional.of("x"))));
    assertEquals("null", asString(converter.toData(Optional.empty())));
    assertEquals("3", asString(converter.toData(OptionalInt.of(3))));
    assertEquals(
        Optional.of("x"), converter.fromData(utf8("\"x\""), Optional.class, optionalString));
    assertEquals(
        Optional.empty(), converter.fromData(utf8("null"), Optional.class, optionalString));
    assertEquals(
        OptionalInt.of(3), converter.fromData(utf8("3"), OptionalInt.class, OptionalInt.class));

    Type listOfOptionals = new TypeReference<List<Optional<String>>>() {}.getType();
    List<Optional<String>> list = Arrays.asList(Optional.of("a"), Optional.empty());
    byte[] data = converter.toData(list);
    assertEquals("[\"a\",null]", asString(data));
    assertEquals(list, converter.fromData(data, List.class, listOfOptionals));
  }

  // -------- Exceptions keep their exact type, message, fields, cause and suppressed --------

  /** Has neither a (String) nor a no-arg constructor. */
  public static class OrderFailedException extends RuntimeException {
    private final int code;
    private final LocalDate date;

    public OrderFailedException(String message, int code, LocalDate date, Throwable cause) {
      super(message, cause);
      this.code = code;
      this.date = date;
    }

    public int getCode() {
      return code;
    }

    public LocalDate getDate() {
      return date;
    }
  }

  /** Its only constructor formats the message. */
  public static class OrderNotFoundException extends RuntimeException {
    private final String orderId;

    public OrderNotFoundException(String orderId) {
      super("Order not found: " + orderId);
      this.orderId = orderId;
    }

    public String getOrderId() {
      return orderId;
    }
  }

  public static class DecoratedMessageException extends RuntimeException {
    public DecoratedMessageException(String message) {
      super(message);
    }

    @Override
    public String getMessage() {
      return "[decorated] " + super.getMessage();
    }
  }

  public static class OptionalFieldsException extends RuntimeException {
    private final Optional<String> hint;
    private final OptionalInt retries;
    private final OptionalLong limit;
    private final OptionalDouble ratio;

    public OptionalFieldsException(
        String message,
        Optional<String> hint,
        OptionalInt retries,
        OptionalLong limit,
        OptionalDouble ratio) {
      super(message);
      this.hint = hint;
      this.retries = retries;
      this.limit = limit;
      this.ratio = ratio;
    }
  }

  public static final class ExceptionHolder {
    private final OrderFailedException failure;
    private final List<OrderNotFoundException> notFound;
    private final Exception other;

    public ExceptionHolder(
        OrderFailedException failure, List<OrderNotFoundException> notFound, Exception other) {
      this.failure = failure;
      this.notFound = notFound;
      this.other = other;
    }
  }

  private static final LocalDate ORDER_DATE = LocalDate.of(2025, 4, 15);
  private static final ActivityType ACTIVITY_TYPE =
      new ActivityType().setName("Activities::charge");
  private static final WorkflowExecution EXECUTION =
      new WorkflowExecution().setWorkflowId("workflow-1").setRunId("run-1");

  /**
   * Decodes the exception through its own class and through Throwable and checks that both give the
   * exact class and message. Note that toData clears the cause of the exception it encodes.
   */
  private <T extends Throwable> T roundTrip(T exception) {
    @SuppressWarnings("unchecked")
    Class<T> type = (Class<T>) exception.getClass();
    String message = exception.getMessage();
    byte[] data = converter.toData(exception);

    Throwable asThrowable = converter.fromData(data, Throwable.class, Throwable.class);
    assertEquals(type, asThrowable.getClass());
    assertEquals(message, asThrowable.getMessage());

    Throwable result = converter.fromData(data, type, type);
    assertEquals(type, result.getClass());
    assertEquals(message, result.getMessage());
    return type.cast(result);
  }

  private static void assertSameFrame(StackTraceElement expected, StackTraceElement actual) {
    assertEquals(expected.getClassName(), actual.getClassName());
    assertEquals(expected.getMethodName(), actual.getMethodName());
    assertEquals(expected.getFileName(), actual.getFileName());
    assertEquals(expected.getLineNumber(), actual.getLineNumber());
  }

  private static void assertSameStackTrace(Throwable expected, Throwable actual) {
    assertEquals(expected.getStackTrace().length, actual.getStackTrace().length);
    assertSameFrame(expected.getStackTrace()[0], actual.getStackTrace()[0]);
  }

  @Test
  public void testExceptionWithoutStringOrNoArgConstructor() {
    IllegalStateException cause = new IllegalStateException("root");
    OrderFailedException exception = new OrderFailedException("order failed", 7, ORDER_DATE, cause);

    OrderFailedException result = roundTrip(exception);
    assertEquals(7, result.getCode());
    assertEquals(ORDER_DATE, result.getDate());
    assertSameStackTrace(exception, result);
    assertEquals(IllegalStateException.class, result.getCause().getClass());
    assertEquals("root", result.getCause().getMessage());
    assertSameStackTrace(cause, result.getCause());
  }

  @Test
  public void testFormattingConstructorDoesNotChangeMessage() {
    OrderNotFoundException result = roundTrip(new OrderNotFoundException("42"));
    assertEquals("Order not found: 42", result.getMessage());
    assertEquals("42", result.getOrderId());
  }

  @Test
  public void testNullMessageStaysNull() {
    IllegalStateException exception = new IllegalStateException((String) null);
    exception.initCause(new IOException("io"));

    IllegalStateException result = roundTrip(exception);
    assertNull(result.getMessage());
    assertEquals("java.lang.IllegalStateException", result.toString());
    assertEquals(IOException.class, result.getCause().getClass());
    assertEquals("io", result.getCause().getMessage());
  }

  @Test
  public void testOverriddenGetMessageIsNotAppliedTwice() {
    DecoratedMessageException exception = new DecoratedMessageException("m");
    byte[] data = converter.toData(exception);
    assertTrue(asString(data), asString(data).contains("\"detailMessage\":\"m\""));

    DecoratedMessageException result = roundTrip(exception);
    assertEquals("[decorated] m", result.getMessage());
  }

  @Test
  public void testSuppressedExceptionsKeepTheirTypes() {
    OrderFailedException exception = new OrderFailedException("outer", 1, ORDER_DATE, null);
    OrderNotFoundException first = new OrderNotFoundException("o-1");
    IllegalArgumentException second = new IllegalArgumentException("bad argument");
    exception.addSuppressed(first);
    exception.addSuppressed(second);

    OrderFailedException result = roundTrip(exception);
    assertNull(result.getCause());
    Throwable[] suppressed = result.getSuppressed();
    assertEquals(2, suppressed.length);
    assertEquals(OrderNotFoundException.class, suppressed[0].getClass());
    assertEquals("Order not found: o-1", suppressed[0].getMessage());
    assertEquals("o-1", ((OrderNotFoundException) suppressed[0]).getOrderId());
    assertSameStackTrace(first, suppressed[0]);
    assertEquals(IllegalArgumentException.class, suppressed[1].getClass());
    assertEquals("bad argument", suppressed[1].getMessage());
    assertSameStackTrace(second, suppressed[1]);
  }

  @Test
  public void testCauseChainKeepsExactTypes() {
    OrderNotFoundException root = new OrderNotFoundException("o-2");
    IllegalStateException middle = new IllegalStateException("middle", root);
    OrderFailedException exception = new OrderFailedException("top", 2, ORDER_DATE, middle);

    OrderFailedException result = roundTrip(exception);
    Throwable resultMiddle = result.getCause();
    assertEquals(IllegalStateException.class, resultMiddle.getClass());
    assertEquals("middle", resultMiddle.getMessage());
    Throwable resultRoot = resultMiddle.getCause();
    assertEquals(OrderNotFoundException.class, resultRoot.getClass());
    assertEquals("Order not found: o-2", resultRoot.getMessage());
    assertEquals("o-2", ((OrderNotFoundException) resultRoot).getOrderId());
    assertNull(resultRoot.getCause());
  }

  @Test
  public void testUnparseableStackTraceLinesAreSkipped() {
    String json =
        "{\"class\":\"java.lang.IllegalStateException\",\"detailMessage\":\"m\","
            + "\"stackTrace\":\"not a stack frame\\n"
            + "com.example.Foo.bar(Foo.java:12)\\n"
            + "\\tat garbage\\n"
            + "com.example.Foo.baz(Unknown Source)\\n\","
            + "\"suppressedExceptions\":[]}";
    Throwable result = converter.fromData(utf8(json), Throwable.class, Throwable.class);
    assertEquals(IllegalStateException.class, result.getClass());
    StackTraceElement[] trace = result.getStackTrace();
    assertEquals(2, trace.length);
    assertSameFrame(new StackTraceElement("com.example.Foo", "bar", "Foo.java", 12), trace[0]);
    assertSameFrame(new StackTraceElement("com.example.Foo", "baz", "Unknown Source", 0), trace[1]);
  }

  @Test
  public void testActivityFailureException() {
    OrderNotFoundException cause = new OrderNotFoundException("o-3");
    ActivityFailureException exception =
        new ActivityFailureException(
            5, ACTIVITY_TYPE, "activity-1", cause, 3, Duration.ofSeconds(2));

    ActivityFailureException result = roundTrip(exception);
    assertEquals(5, result.getEventId());
    assertEquals(ACTIVITY_TYPE, result.getActivityType());
    assertEquals("activity-1", result.getActivityId());
    assertEquals(3, result.getAttempt());
    assertEquals(Duration.ofSeconds(2), result.getBackoff());
    assertEquals(OrderNotFoundException.class, result.getCause().getClass());
    assertEquals("Order not found: o-3", result.getCause().getMessage());
  }

  @Test
  public void testActivityTimeoutException() {
    ActivityTimeoutException exception =
        new ActivityTimeoutException(
            6,
            ACTIVITY_TYPE,
            "activity-2",
            TimeoutType.HEARTBEAT,
            converter.toData("progress"),
            converter);

    ActivityTimeoutException result = roundTrip(exception);
    assertEquals(6, result.getEventId());
    assertEquals(ACTIVITY_TYPE, result.getActivityType());
    assertEquals("activity-2", result.getActivityId());
    assertEquals(TimeoutType.HEARTBEAT, result.getTimeoutType());
    assertEquals("progress", result.getDetails(String.class));
  }

  @Test
  public void testChildWorkflowFailureException() {
    WorkflowType workflowType = new WorkflowType().setName("Child::run");
    ActivityFailureException cause =
        new ActivityFailureException(
            5, ACTIVITY_TYPE, "activity-1", new IllegalStateException("x"));
    ChildWorkflowFailureException exception =
        new ChildWorkflowFailureException(7, EXECUTION, workflowType, cause);

    ChildWorkflowFailureException result = roundTrip(exception);
    assertEquals(7, result.getEventId());
    assertEquals(EXECUTION, result.getWorkflowExecution());
    assertEquals(workflowType, result.getWorkflowType());
    assertEquals(ActivityFailureException.class, result.getCause().getClass());
    assertEquals(IllegalStateException.class, result.getCause().getCause().getClass());
  }

  @Test
  public void testWorkflowFailureException() {
    WorkflowFailureException exception =
        new WorkflowFailureException(
            EXECUTION, Optional.of("Workflow::run"), 9, new OrderNotFoundException("o-4"));
    WorkflowFailureException result = roundTrip(exception);
    assertEquals(EXECUTION, result.getExecution());
    assertEquals(Optional.of("Workflow::run"), result.getWorkflowType());
    assertEquals(9, result.getDecisionTaskCompletedEventId());
    assertEquals(OrderNotFoundException.class, result.getCause().getClass());

    WorkflowFailureException withoutType =
        new WorkflowFailureException(EXECUTION, Optional.empty(), 10, new IllegalStateException());
    assertEquals(Optional.empty(), roundTrip(withoutType).getWorkflowType());
  }

  @Test
  public void testDataConverterException() {
    DataConverterException exception =
        new DataConverterException("conversion failed", new IllegalArgumentException("inner"));
    DataConverterException result = roundTrip(exception);
    assertEquals(IllegalArgumentException.class, result.getCause().getClass());
    assertEquals("inner", result.getCause().getMessage());
  }

  @Test
  public void testSimulatedTimeoutExceptionInternal() throws Exception {
    // Package-private in com.uber.cadence.internal.sync. Activities throw it to simulate a timeout
    // and the workflow side has to recognize its type.
    Class<? extends Throwable> type =
        Class.forName("com.uber.cadence.internal.sync.SimulatedTimeoutExceptionInternal")
            .asSubclass(Throwable.class);
    Constructor<? extends Throwable> constructor =
        type.getDeclaredConstructor(TimeoutType.class, byte[].class);
    constructor.setAccessible(true);
    Throwable exception =
        constructor.newInstance(TimeoutType.START_TO_CLOSE, converter.toData("details"));

    Throwable result = roundTrip(exception);
    assertNull(result.getMessage());
    Field timeoutType = type.getDeclaredField("timeoutType");
    timeoutType.setAccessible(true);
    assertEquals(TimeoutType.START_TO_CLOSE, timeoutType.get(result));
    Field details = type.getDeclaredField("details");
    details.setAccessible(true);
    assertEquals(
        "details", converter.fromData((byte[]) details.get(result), String.class, String.class));
  }

  @Test
  public void testJdkExceptionsKeepTheirTypes() {
    EnumConstantNotPresentException notPresent =
        roundTrip(new EnumConstantNotPresentException(Color.class, "PURPLE"));
    assertEquals(Color.class, notPresent.enumType());
    assertEquals("PURPLE", notPresent.constantName());

    String json =
        "{\"class\":\"java.util.MissingResourceException\",\"detailMessage\":\"missing\","
            + "\"className\":\"Bundle\",\"key\":\"k\",\"suppressedExceptions\":[]}";
    Throwable missing = converter.fromData(utf8(json), Throwable.class, Throwable.class);
    assertEquals("missing", missing.getMessage());
    if (isOpenToReflection(MissingResourceException.class)) {
      assertEquals(MissingResourceException.class, missing.getClass());
      assertEquals("Bundle", ((MissingResourceException) missing).getClassName());
      assertEquals("k", ((MissingResourceException) missing).getKey());
    } else {
      // Its fields cannot be restored, and it has no (String) constructor.
      assertEquals(ApplicationFailureException.class, missing.getClass());
    }
  }

  /**
   * Whether the package of the class is open to this code (not with JDK 16+ strong encapsulation).
   */
  private static boolean isOpenToReflection(Class<?> type) {
    try {
      type.getDeclaredFields()[0].setAccessible(true);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Test
  public void testJdkExceptionsWithPrivateState() {
    // With strong encapsulation their fields cannot be restored. They must still have the right
    // message and cause, and getMessage() must not fail.
    assertEquals(
        "d != java.lang.String",
        decode(new IllegalFormatConversionException('d', String.class)).getMessage());

    Throwable noSuchFile = decode(new NoSuchFileException("/tmp/x"));
    assertEquals(NoSuchFileException.class, noSuchFile.getClass());
    assertEquals("/tmp/x", noSuchFile.getMessage());

    Throwable accessDenied = decode(new AccessDeniedException("/etc/shadow", null, "denied"));
    assertEquals(AccessDeniedException.class, accessDenied.getClass());
    assertEquals("/etc/shadow: denied", accessDenied.getMessage());

    Throwable invocation = decode(new InvocationTargetException(new IOException("target")));
    assertEquals(IOException.class, invocation.getCause().getClass());
    assertEquals("target", invocation.getCause().getMessage());
  }

  private Throwable decode(Throwable exception) {
    return converter.fromData(converter.toData(exception), Throwable.class, Throwable.class);
  }

  public static class BaseCodeException extends RuntimeException {
    protected final int code;

    BaseCodeException(String message, int code) {
      super(message);
      this.code = code;
    }
  }

  public static class SubCodeException extends BaseCodeException {
    // Hides BaseCodeException.code.
    private final int code;

    SubCodeException(String message, int baseCode, int code) {
      super(message, baseCode);
      this.code = code;
    }
  }

  @Test
  public void testHiddenFieldIsNotRestoredFromTheSubclassField() {
    SubCodeException result = roundTrip(new SubCodeException("hidden", 1, 2));
    assertEquals(2, result.code);
  }

  /** Has fields with the names of keys of the JSON of exceptions. */
  public static class ReservedNamesException extends RuntimeException {
    private final String cause;
    private final String stackTrace;

    ReservedNamesException(String message, Throwable cause) {
      super(message, cause);
      this.cause = "reserved cause";
      this.stackTrace = "reserved stack";
    }
  }

  @Test
  public void testFieldsWithReservedNames() {
    ReservedNamesException result =
        roundTrip(new ReservedNamesException("reserved", new IOException("real cause")));
    assertEquals("reserved", result.getMessage());
    assertEquals(IOException.class, result.getCause().getClass());
    // These fields cannot be written, so they are not restored from the keys of the exception.
    assertNull(result.cause);
    assertNull(result.stackTrace);
  }

  @Test
  public void testUnknownExceptionClassBecomesApplicationFailure() {
    String json =
        "{\"class\":\"java.lang.IllegalStateException\",\"detailMessage\":\"outer\","
            + "\"cause\":{\"class\":\"com.example.NoSuchException\",\"detailMessage\":\"lost\"}}";
    Throwable result = converter.fromData(utf8(json), Throwable.class, Throwable.class);
    assertEquals(IllegalStateException.class, result.getClass());
    assertEquals(ApplicationFailureException.class, result.getCause().getClass());
    assertEquals("lost", result.getCause().getMessage());

    // An abstract class cannot be instantiated either.
    String abstractJson =
        "{\"class\":\"com.uber.cadence.workflow.ActivityException\",\"detailMessage\":\"abs\"}";
    Throwable abstractResult =
        converter.fromData(utf8(abstractJson), Throwable.class, Throwable.class);
    assertEquals(ApplicationFailureException.class, abstractResult.getClass());
    assertEquals("abs", abstractResult.getMessage());
  }

  @Test
  public void testInvalidExceptionJsonFails() {
    List<String> invalid =
        Arrays.asList(
            "{\"class\":\"java.lang.String\",\"detailMessage\":\"m\"}",
            "{\"detailMessage\":\"m\"}",
            "{\"class\":null,\"detailMessage\":\"m\"}",
            "\"text\"",
            "[1]",
            "42");
    for (String json : invalid) {
      assertThrows(
          json,
          DataConverterException.class,
          () -> converter.fromData(utf8(json), RuntimeException.class, RuntimeException.class));
      assertThrows(
          json,
          DataConverterException.class,
          () -> converter.fromData(utf8(json), Throwable.class, Throwable.class));
    }
  }

  @Test
  public void testExceptionFieldsOfPojo() {
    ExceptionHolder holder =
        new ExceptionHolder(
            new OrderFailedException("failed", 3, ORDER_DATE, new IllegalStateException("c")),
            Arrays.asList(new OrderNotFoundException("a"), new OrderNotFoundException("b")),
            new IllegalArgumentException("other"));

    ExceptionHolder result =
        converter.fromData(converter.toData(holder), ExceptionHolder.class, ExceptionHolder.class);
    assertEquals(OrderFailedException.class, result.failure.getClass());
    assertEquals("failed", result.failure.getMessage());
    assertEquals(3, result.failure.getCode());
    assertEquals(IllegalStateException.class, result.failure.getCause().getClass());
    assertEquals(2, result.notFound.size());
    assertEquals(OrderNotFoundException.class, result.notFound.get(1).getClass());
    assertEquals("Order not found: b", result.notFound.get(1).getMessage());
    assertEquals(IllegalArgumentException.class, result.other.getClass());
    assertEquals("other", result.other.getMessage());
  }

  @Test
  public void testExceptionInDataArray() {
    byte[] data = converter.toData("x", new OrderFailedException("failed", 4, ORDER_DATE, null), 5);
    Object[] result =
        converter.fromDataArray(data, String.class, OrderFailedException.class, int.class);
    assertEquals("x", result[0]);
    assertEquals(OrderFailedException.class, result[1].getClass());
    assertEquals(4, ((OrderFailedException) result[1]).getCode());
    assertEquals(5, result[2]);
  }

  @Test
  public void testExceptionOptionalFields() {
    OptionalFieldsException exception =
        new OptionalFieldsException(
            "m",
            Optional.of("retry later"),
            OptionalInt.of(2),
            OptionalLong.of(3L),
            OptionalDouble.of(0.25));
    OptionalFieldsException result = roundTrip(exception);
    assertEquals(Optional.of("retry later"), result.hint);
    assertEquals(OptionalInt.of(2), result.retries);
    assertEquals(OptionalLong.of(3L), result.limit);
    assertEquals(OptionalDouble.of(0.25), result.ratio);

    // Missing, null and invalid values leave an empty Optional rather than null.
    String className = OptionalFieldsException.class.getName();
    List<String> jsons =
        Arrays.asList(
            "{\"class\":\"" + className + "\",\"detailMessage\":\"m\"}",
            "{\"class\":\""
                + className
                + "\",\"detailMessage\":\"m\","
                + "\"hint\":null,\"retries\":null,\"limit\":null,\"ratio\":null}",
            "{\"class\":\""
                + className
                + "\",\"detailMessage\":\"m\","
                + "\"hint\":{\"a\":1},\"retries\":[1],\"limit\":\"x\",\"ratio\":{}}");
    for (String json : jsons) {
      OptionalFieldsException empty =
          converter.fromData(
              utf8(json), OptionalFieldsException.class, OptionalFieldsException.class);
      assertEquals(json, "m", empty.getMessage());
      assertEquals(json, Optional.empty(), empty.hint);
      assertEquals(json, OptionalInt.empty(), empty.retries);
      assertEquals(json, OptionalLong.empty(), empty.limit);
      assertEquals(json, OptionalDouble.empty(), empty.ratio);
    }
  }

  @Test
  public void testExceptionEncodedByJsonDataConverter() {
    DetailedException exception = new DetailedException("failed", 42, "extra detail");
    exception.initCause(new IllegalStateException("cause"));
    byte[] data = JsonDataConverter.getInstance().toData(exception);

    DetailedException result =
        converter.fromData(data, DetailedException.class, DetailedException.class);
    assertEquals(DetailedException.class, result.getClass());
    assertEquals("failed", result.getMessage());
    assertEquals(42, result.getErrorCode());
    assertEquals("extra detail", result.getErrorDetail());
    assertSameStackTrace(exception, result);
    assertEquals(IllegalStateException.class, result.getCause().getClass());
    assertEquals("cause", result.getCause().getMessage());
  }

  // -------- Exceptions with a no-arg constructor --------

  /** Its no-arg constructor sets a default message, and its fields have initializers. */
  public static class InitializedException extends RuntimeException {
    private final transient List<String> context = new ArrayList<>();
    private String code = "DEFAULT";
    private String detail = "initial";

    public InitializedException() {
      super("default message");
    }

    InitializedException(String message, String code, String detail) {
      super(message);
      this.code = code;
      this.detail = detail;
    }
  }

  @Test
  public void testNoArgConstructorOfExceptionIsUsed() {
    InitializedException original = new InitializedException("failed", "E42", "detail");
    InitializedException result =
        converter.fromData(
            converter.toData(original), InitializedException.class, InitializedException.class);
    assertEquals(InitializedException.class, result.getClass());
    assertEquals("failed", result.getMessage());
    assertEquals("E42", result.code);
    assertEquals("detail", result.detail);
    // Transient fields are not serialized, and keep the value the constructor gave them.
    assertEquals(Collections.emptyList(), result.context);
    assertSameStackTrace(original, result);
  }

  @Test
  public void testMissingAndNullFieldsOfExceptionWithNoArgConstructor() {
    String json =
        "{\"detailMessage\":null,\"detail\":null,\"stackTrace\":\"\",\"suppressedExceptions\":[],"
            + "\"class\":\""
            + InitializedException.class.getName()
            + "\"}";
    InitializedException result =
        converter.fromData(utf8(json), InitializedException.class, InitializedException.class);
    assertNull(result.getMessage());
    // A field missing in the JSON keeps its initial value, a null one is set to null.
    assertEquals("DEFAULT", result.code);
    assertNull(result.detail);
    assertNotNull(result.context);
  }

  /** Its no-arg constructor sets a cause. */
  public static class PresetCauseException extends RuntimeException {
    public PresetCauseException() {
      super("preset", new IllegalArgumentException("preset cause"));
    }

    PresetCauseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  @Test
  public void testCauseSetByNoArgConstructorIsReplaced() {
    PresetCauseException original =
        new PresetCauseException("failed", new IOException("actual cause"));
    PresetCauseException result =
        converter.fromData(
            converter.toData(original), PresetCauseException.class, PresetCauseException.class);
    assertEquals("failed", result.getMessage());
    assertEquals(IOException.class, result.getCause().getClass());
    assertEquals("actual cause", result.getCause().getMessage());

    // Without a cause in the JSON, the one set by the constructor is removed.
    PresetCauseException withoutCause =
        converter.fromData(
            converter.toData(new PresetCauseException("no cause", null)),
            PresetCauseException.class,
            PresetCauseException.class);
    assertEquals("no cause", withoutCause.getMessage());
    assertNull(withoutCause.getCause());
  }

  // -------- Unusual payloads --------

  @Test
  public void testExceptionJsonWithoutOptionalParts() {
    String json =
        "{\"code\":null,\"cause\":null,"
            + "\"suppressedExceptions\":[null,{\"class\":\"java.io.IOException\"}],"
            + "\"class\":\""
            + OrderFailedException.class.getName()
            + "\"}";
    OrderFailedException result =
        converter.fromData(utf8(json), OrderFailedException.class, OrderFailedException.class);
    assertNull(result.getMessage());
    // A primitive field that is null in the JSON keeps its value.
    assertEquals(0, result.getCode());
    assertNull(result.getDate());
    assertNull(result.getCause());
    assertEquals(0, result.getStackTrace().length);
    assertEquals(1, result.getSuppressed().length);
    assertEquals(IOException.class, result.getSuppressed()[0].getClass());

    String withoutSuppressed =
        "{\"suppressedExceptions\":null,\"class\":\"java.lang.IllegalStateException\"}";
    Throwable noSuppressed =
        converter.fromData(utf8(withoutSuppressed), Throwable.class, Throwable.class);
    assertEquals(IllegalStateException.class, noSuppressed.getClass());
    assertEquals(0, noSuppressed.getSuppressed().length);
  }

  @Test
  public void testExceptionWithoutCauseEncodedByJsonDataConverter() {
    byte[] data = JsonDataConverter.getInstance().toData(new IllegalStateException("no cause"));
    Throwable result = converter.fromData(data, Throwable.class, Throwable.class);
    assertEquals(IllegalStateException.class, result.getClass());
    assertEquals("no cause", result.getMessage());
    assertNull(result.getCause());
  }

  /** Has a synthetic field that references the enclosing instance. */
  @SuppressWarnings("ClassCanBeStatic")
  public class InnerException extends RuntimeException {
    private final int code;

    public InnerException(String message, int code) {
      super(message);
      this.code = code;
    }
  }

  @Test
  public void testNonStaticInnerException() {
    InnerException result =
        converter.fromData(
            converter.toData(new InnerException("inner", 3)),
            InnerException.class,
            InnerException.class);
    assertEquals(InnerException.class, result.getClass());
    assertEquals("inner", result.getMessage());
    assertEquals(3, result.code);
  }

  static int failToInitialize() {
    throw new IllegalStateException("cannot initialize");
  }

  public static class BrokenClass {
    static final int VALUE = failToInitialize();
    final String name;

    BrokenClass(String name) {
      this.name = name;
    }
  }

  public static class BrokenException extends RuntimeException {
    static final int VALUE = failToInitialize();

    public BrokenException() {}

    public BrokenException(String message) {
      super(message);
    }
  }

  @Test
  public void testClassesThatFailToInitialize() {
    assertThrows(
        DataConverterException.class,
        () -> converter.fromData(utf8("{\"name\":\"x\"}"), BrokenClass.class, BrokenClass.class));

    String json =
        "{\"detailMessage\":\"broken\",\"class\":\"" + BrokenException.class.getName() + "\"}";
    Throwable result = converter.fromData(utf8(json), Throwable.class, Throwable.class);
    assertEquals(ApplicationFailureException.class, result.getClass());
    assertEquals("broken", result.getMessage());
  }

  public static class Tags {
    final List<String> values;
    final boolean fromCreator;

    private Tags(List<String> values) {
      this.values = values;
      this.fromCreator = true;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Tags of(List<String> values) {
      return new Tags(values);
    }
  }

  public static class Attributes {
    final Map<String, String> values;
    final boolean fromCreator;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Attributes(Map<String, String> values) {
      this.values = values;
      this.fromCreator = true;
    }
  }

  @Test
  public void testDelegatingCreatorsAreStillUsed() {
    Tags tags = converter.fromData(utf8("[\"a\",\"b\"]"), Tags.class, Tags.class);
    assertTrue(tags.fromCreator);
    assertEquals(Arrays.asList("a", "b"), tags.values);

    Attributes attributes =
        converter.fromData(utf8("{\"k\":\"v\"}"), Attributes.class, Attributes.class);
    assertTrue(attributes.fromCreator);
    assertEquals(Collections.singletonMap("k", "v"), attributes.values);
  }

  public static class ConverterAndClass {
    DataConverter converter;
    Class<?> type;
  }

  @Test
  public void testDataConverterAndClassFields() {
    ConverterAndClass holder =
        converter.fromData(
            utf8(
                "{\"converter\":{\"type\":\"JSON\"},\"type\":{\"className\":\"java.lang.String\"}}"),
            ConverterAndClass.class,
            ConverterAndClass.class);
    assertSame(JacksonDataConverter.getInstance(), holder.converter);
    assertEquals(String.class, holder.type);

    for (String json :
        Arrays.asList(
            "{\"converter\":{}}",
            "{\"converter\":{\"type\":\"XML\"}}",
            "{\"type\":{}}",
            "{\"type\":{\"className\":\"com.example.Missing\"}}")) {
      assertThrows(
          json,
          DataConverterException.class,
          () -> converter.fromData(utf8(json), ConverterAndClass.class, ConverterAndClass.class));
    }
  }

  public static class SelfReference {
    final SelfReference self = this;
  }

  @Test
  public void testValueThatCannotBeSerialized() {
    assertThrows(DataConverterException.class, () -> converter.toData(new SelfReference()));
  }

  // -------- Customized ObjectMapper --------

  @Test
  public void testCustomizedMapperKeepsCadenceHandling() {
    List<Function<ObjectMapper, ObjectMapper>> interceptors =
        Arrays.asList(
            mapper -> mapper,
            ObjectMapper::copy,
            mapper -> mapper.copy().registerModule(new SimpleModule("application-module")));
    for (Function<ObjectMapper, ObjectMapper> interceptor : interceptors) {
      DataConverter custom = new JacksonDataConverter(interceptor);

      ImmutableOrder order = newOrder("custom");
      assertEquals(
          order, custom.fromData(custom.toData(order), ImmutableOrder.class, ImmutableOrder.class));

      byte[] exception = custom.toData(new OrderFailedException("custom", 8, ORDER_DATE, null));
      Throwable result = custom.fromData(exception, Throwable.class, Throwable.class);
      assertEquals(OrderFailedException.class, result.getClass());
      assertEquals("custom", result.getMessage());
      assertEquals(8, ((OrderFailedException) result).getCode());

      Type setOfColors = new TypeReference<Set<Color>>() {}.getType();
      @SuppressWarnings("unchecked")
      Set<Color> colors = custom.fromData(utf8(REVERSED_COLORS), Set.class, setOfColors);
      assertEquals(reversedColors(), new ArrayList<>(colors));

      assertNull(custom.fromData(new byte[0], String.class, String.class));
    }
  }

  public static final class MaybeHolder {
    final Optional<String> maybe;

    @JsonCreator
    MaybeHolder(@JsonProperty("maybe") Optional<String> maybe) {
      this.maybe = maybe;
    }
  }

  /** A JavaTimeModule or Jdk8Module that the application registers applies to its values. */
  @Test
  public void testApplicationJavaTimeAndJdk8ModulesApply() {
    JavaTimeModule javaTime = new JavaTimeModule();
    javaTime.addSerializer(
        LocalDate.class,
        new JsonSerializer<LocalDate>() {
          @Override
          public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeString("CUSTOM-" + value);
          }
        });
    javaTime.addSerializer(
        Duration.class,
        new JsonSerializer<Duration>() {
          @Override
          public void serialize(Duration value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            gen.writeNumber(value.toMillis());
          }
        });
    DataConverter custom =
        new JacksonDataConverter(
            mapper ->
                mapper
                    .registerModule(javaTime)
                    .registerModule(new Jdk8Module().configureReadAbsentAsNull(true)));

    assertEquals("\"CUSTOM-2025-04-15\"", asString(custom.toData(ORDER_DATE)));
    assertEquals("90000", asString(custom.toData(Duration.ofSeconds(90))));
    // A missing Optional is null rather than empty.
    assertNull(custom.fromData(utf8("{}"), MaybeHolder.class, MaybeHolder.class).maybe);
    assertEquals(
        Optional.empty(),
        converter.fromData(utf8("{}"), MaybeHolder.class, MaybeHolder.class).maybe);
  }
}
