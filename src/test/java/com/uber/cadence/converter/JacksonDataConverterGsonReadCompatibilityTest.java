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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.ReferenceType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.uber.cadence.ActivityType;
import com.uber.cadence.MarkerRecordedEventAttributes;
import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.client.WorkflowFailureException;
import com.uber.cadence.common.RetryOptions;
import com.uber.cadence.internal.common.CheckedExceptionWrapper;
import com.uber.cadence.internal.common.LocalActivityMarkerData;
import com.uber.cadence.workflow.ActivityFailureException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * JacksonDataConverter reads the JSON that JsonDataConverter (Gson) writes, and writes the
 * Durations of the client's own payloads the way JsonDataConverter reads them.
 */
public class JacksonDataConverterGsonReadCompatibilityTest {

  private static final DataConverter JACKSON = JacksonDataConverter.getInstance();

  private static final TimeZone LOS_ANGELES = TimeZone.getTimeZone("America/Los_Angeles");

  // What JsonDataConverter writes on JDK 15 and earlier, where Gson reflects on java.time.
  private static final String GSON_LOCAL_DATE = "{\"year\":2023,\"month\":11,\"day\":15}";
  private static final String GSON_LOCAL_TIME =
      "{\"hour\":3,\"minute\":43,\"second\":20,\"nano\":5}";
  private static final String GSON_LOCAL_DATE_TIME =
      "{\"date\":" + GSON_LOCAL_DATE + ",\"time\":" + GSON_LOCAL_TIME + "}";
  private static final String GSON_INSTANT = "{\"seconds\":1700019800,\"nanos\":123456789}";
  private static final String GSON_OFFSET_DATE_TIME =
      "{\"dateTime\":" + GSON_LOCAL_DATE_TIME + ",\"offset\":{\"totalSeconds\":18000}}";
  private static final String GSON_OFFSET_TIME =
      "{\"time\":" + GSON_LOCAL_TIME + ",\"offset\":{\"totalSeconds\":-10800}}";
  private static final String GSON_ZONE_OFFSET = "{\"totalSeconds\":19800}";
  private static final String GSON_PERIOD = "{\"years\":1,\"months\":2,\"days\":3}";
  private static final String GSON_YEAR = "{\"year\":2023}";
  private static final String GSON_YEAR_MONTH = "{\"year\":2023,\"month\":11}";
  private static final String GSON_MONTH_DAY = "{\"month\":11,\"day\":15}";
  private static final String GSON_CALENDAR =
      "{\"year\":2023,\"month\":10,\"dayOfMonth\":15,\"hourOfDay\":3,\"minute\":43,\"second\":20}";

  private static final LocalDate LOCAL_DATE = LocalDate.of(2023, 11, 15);
  private static final LocalTime LOCAL_TIME = LocalTime.of(3, 43, 20, 5);
  private static final LocalDateTime LOCAL_DATE_TIME = LocalDateTime.of(LOCAL_DATE, LOCAL_TIME);
  private static final Instant INSTANT = Instant.ofEpochSecond(1700019800L, 123456789);

  private static final String RETRY_OPTIONS_JSON =
      "{\"initialInterval\":{\"seconds\":1,\"nanos\":0},\"backoffCoefficient\":2.0,"
          + "\"expiration\":{\"seconds\":60,\"nanos\":0},\"maximumAttempts\":3,"
          + "\"maximumInterval\":{\"seconds\":10,\"nanos\":500000000},"
          + "\"doNotRetry\":[{\"className\":\"java.lang.IllegalStateException\"}]}";

  public enum Color {
    RED,
    GREEN
  }

  public static class Times {
    public LocalDate localDate;
    public LocalTime localTime;
    public LocalDateTime localDateTime;
    public Instant instant;
    public OffsetDateTime offsetDateTime;
    public OffsetTime offsetTime;
    public ZoneOffset zoneOffset;
    public Period period;
    public Year year;
    public YearMonth yearMonth;
    public MonthDay monthDay;
    public Duration duration;
    public OptionalInt optionalInt;
    public OptionalLong optionalLong;
    public OptionalDouble optionalDouble;
    public Optional<String> optional;
    public List<LocalDate> dates;
    public Map<String, Instant> instants;
  }

  public static class Dates {
    public Date date;
    public java.sql.Timestamp timestamp;
    public java.sql.Date sqlDate;
    public java.sql.Time sqlTime;
    public Calendar calendar;
    public GregorianCalendar gregorianCalendar;
    public byte[] bytes;
    public List<Date> dateList;
  }

  public static class NoValue {
    public String name;
  }

  public static class WithValue {
    public String value;
    public int other;
  }

  public static class Optionals {
    public Optional<String> text;
    public Optional<Integer> number;
    public Optional<NoValue> bean;
    public Optional<List<String>> list;
    public Optional<Color> color;
    public Optional<LocalDate> date;
    public Optional<String> empty;
  }

  public static class Formatted {
    @JsonFormat(pattern = "dd/MM/yyyy", locale = "en_US")
    public LocalDate day;

    @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "yyyy-MM-dd HH:mm",
      timezone = "UTC",
      locale = "en_US"
    )
    public Date when;
  }

  public static class Untyped {
    public Object any;
    public Number number;
    public Integer count;
    public Map<String, Object> attributes;
  }

  public static class WithRetryOptions {
    public RetryOptions retryOptions;
    public Duration timeout;
  }

  public static class SnakeCased {
    public String someField;
  }

  // ---------- java.time and Optional* object shapes ----------

  @Test
  public void readsGsonJavaTimeShapes() {
    Object[][] cases = {
      {LocalDate.class, GSON_LOCAL_DATE, LOCAL_DATE},
      {LocalTime.class, GSON_LOCAL_TIME, LOCAL_TIME},
      {LocalDateTime.class, GSON_LOCAL_DATE_TIME, LOCAL_DATE_TIME},
      {Instant.class, GSON_INSTANT, INSTANT},
      {
        OffsetDateTime.class,
        GSON_OFFSET_DATE_TIME,
        OffsetDateTime.of(LOCAL_DATE_TIME, ZoneOffset.ofHours(5))
      },
      {OffsetTime.class, GSON_OFFSET_TIME, OffsetTime.of(LOCAL_TIME, ZoneOffset.ofHours(-3))},
      {ZoneOffset.class, GSON_ZONE_OFFSET, ZoneOffset.ofHoursMinutes(5, 30)},
      {Period.class, GSON_PERIOD, Period.of(1, 2, 3)},
      {Year.class, GSON_YEAR, Year.of(2023)},
      {YearMonth.class, GSON_YEAR_MONTH, YearMonth.of(2023, 11)},
      {MonthDay.class, GSON_MONTH_DAY, MonthDay.of(11, 15)},
      {Duration.class, "{\"seconds\":90,\"nanos\":5000000}", Duration.ofSeconds(90, 5_000_000)},
      {OptionalInt.class, "{\"isPresent\":true,\"value\":7}", OptionalInt.of(7)},
      {OptionalInt.class, "{\"isPresent\":false,\"value\":0}", OptionalInt.empty()},
      {
        OptionalLong.class,
        "{\"isPresent\":true,\"value\":9007199254740993}",
        OptionalLong.of(9007199254740993L)
      },
      {OptionalLong.class, "{\"isPresent\":false,\"value\":0}", OptionalLong.empty()},
      {OptionalDouble.class, "{\"isPresent\":true,\"value\":7.5}", OptionalDouble.of(7.5)},
      {OptionalDouble.class, "{\"isPresent\":false,\"value\":0.0}", OptionalDouble.empty()},
    };
    for (Object[] c : cases) {
      Class<?> type = (Class<?>) c[0];
      String message = type.getSimpleName() + " " + c[1];
      assertEquals(message, c[2], read(JACKSON, (String) c[1], type));
      // What JacksonDataConverter writes itself is still read.
      assertEquals(message, c[2], read(JACKSON, write(JACKSON, c[2]), type));
      assertEquals(message, c[2], JACKSON.fromDataArray(bytes((String) c[1]), type)[0]);
    }

    String json =
        "{\"localDate\":"
            + GSON_LOCAL_DATE
            + ",\"localTime\":"
            + GSON_LOCAL_TIME
            + ",\"localDateTime\":"
            + GSON_LOCAL_DATE_TIME
            + ",\"instant\":"
            + GSON_INSTANT
            + ",\"offsetDateTime\":"
            + GSON_OFFSET_DATE_TIME
            + ",\"offsetTime\":"
            + GSON_OFFSET_TIME
            + ",\"zoneOffset\":"
            + GSON_ZONE_OFFSET
            + ",\"period\":"
            + GSON_PERIOD
            + ",\"year\":"
            + GSON_YEAR
            + ",\"yearMonth\":"
            + GSON_YEAR_MONTH
            + ",\"monthDay\":"
            + GSON_MONTH_DAY
            + ",\"duration\":{\"seconds\":90,\"nanos\":5000000}"
            + ",\"optionalInt\":{\"isPresent\":true,\"value\":7}"
            + ",\"optionalLong\":{\"isPresent\":false,\"value\":0}"
            + ",\"optionalDouble\":{\"isPresent\":true,\"value\":7.5}"
            + ",\"optional\":{\"value\":\"x\"}"
            + ",\"dates\":["
            + GSON_LOCAL_DATE
            + ",\"2023-11-16\"]"
            + ",\"instants\":{\"a\":"
            + GSON_INSTANT
            + "}}";
    Times times = read(JACKSON, json, Times.class);
    assertEquals(write(JACKSON, expectedTimes()), write(JACKSON, times));
    assertEquals(OffsetTime.of(LOCAL_TIME, ZoneOffset.ofHours(-3)), times.offsetTime);
    assertEquals(OptionalLong.empty(), times.optionalLong);
    assertEquals(Arrays.asList(LOCAL_DATE, LocalDate.of(2023, 11, 16)), times.dates);
    assertEquals(Collections.singletonMap("a", INSTANT), times.instants);
  }

  @Test
  public void readsWhatJsonDataConverterWrites() {
    DataConverter gson = new JsonDataConverter(b -> b);
    assumeTrue(
        "JsonDataConverter cannot access the fields of java.time and java.util classes",
        canWrite(gson, LOCAL_DATE) && canWrite(gson, Optional.of("x")));
    List<Object> values =
        Arrays.asList(
            LOCAL_DATE,
            LOCAL_TIME,
            LOCAL_DATE_TIME,
            INSTANT,
            OffsetDateTime.of(LOCAL_DATE_TIME, ZoneOffset.ofHours(5)),
            OffsetTime.of(LOCAL_TIME, ZoneOffset.ofHours(-3)),
            ZoneOffset.ofHoursMinutes(5, 30),
            Period.of(1, 2, 3),
            Year.of(2023),
            YearMonth.of(2023, 11),
            MonthDay.of(11, 15),
            Duration.ofSeconds(90, 5_000_000),
            OptionalInt.of(7),
            OptionalInt.empty(),
            OptionalLong.of(7),
            OptionalDouble.of(7.5));
    for (Object value : values) {
      byte[] data = gson.toData(value);
      assertEquals(
          value.toString(), value, JACKSON.fromData(data, value.getClass(), value.getClass()));
    }

    TypeReference<Optional<String>> optionalString = new TypeReference<Optional<String>>() {};
    assertEquals(Optional.of("x"), read(JACKSON, write(gson, Optional.of("x")), optionalString));
    assertEquals(Optional.empty(), read(JACKSON, write(gson, Optional.empty()), optionalString));

    Times expected = expectedTimes();
    Times times = read(JACKSON, write(gson, expected), Times.class);
    assertEquals(write(JACKSON, expected), write(JACKSON, times));
  }

  // ---------- Dates ----------

  @Test
  public void readsWhatJsonDataConverterWritesForDates() {
    withDefaults(
        LOS_ANGELES,
        Locale.US,
        JacksonDataConverterGsonReadCompatibilityTest::assertDatesOfJsonDataConverterAreRead);
    withDefaults(
        LOS_ANGELES,
        Locale.GERMANY,
        JacksonDataConverterGsonReadCompatibilityTest::assertDatesOfJsonDataConverterAreRead);
    withDefaults(
        TimeZone.getTimeZone("Asia/Kolkata"),
        Locale.forLanguageTag("en-IN"),
        JacksonDataConverterGsonReadCompatibilityTest::assertDatesOfJsonDataConverterAreRead);
  }

  private static void assertDatesOfJsonDataConverterAreRead() {
    // A new instance, as Gson's date adapters keep the time zone and locale they were created in.
    DataConverter gson = new JsonDataConverter(b -> b);
    Dates dates = new Dates();
    // Gson's date formats have no milliseconds.
    dates.date = new Date(1700019800000L);
    dates.timestamp = new java.sql.Timestamp(1700019800000L);
    dates.sqlDate = java.sql.Date.valueOf("2023-11-15");
    dates.sqlTime = java.sql.Time.valueOf("21:13:20");
    dates.calendar = new GregorianCalendar(2023, Calendar.NOVEMBER, 15, 3, 43, 20);
    dates.gregorianCalendar = new GregorianCalendar(2024, Calendar.FEBRUARY, 29, 23, 59, 59);
    dates.bytes = new byte[] {1, 2, -1};
    dates.dateList = Collections.singletonList(new Date(1700019800000L));

    String json = write(gson, dates);
    Dates read = read(JACKSON, json, Dates.class);
    String context = TimeZone.getDefault().getID() + " " + Locale.getDefault() + " " + json;
    assertEquals(context, dates.date, read.date);
    assertEquals(context, dates.timestamp, read.timestamp);
    assertEquals(context, java.sql.Timestamp.class, read.timestamp.getClass());
    assertEquals(context, dates.sqlDate, read.sqlDate);
    assertEquals(context, java.sql.Date.class, read.sqlDate.getClass());
    assertEquals(context, dates.sqlTime, read.sqlTime);
    assertEquals(context, java.sql.Time.class, read.sqlTime.getClass());
    assertEquals(context, dates.calendar.getTimeInMillis(), read.calendar.getTimeInMillis());
    assertEquals(
        context,
        dates.gregorianCalendar.getTimeInMillis(),
        read.gregorianCalendar.getTimeInMillis());
    assertArrayEquals(context, dates.bytes, read.bytes);
    assertEquals(context, dates.dateList, read.dateList);

    assertEquals(context, dates.date, read(JACKSON, write(gson, dates.date), Date.class));
    assertEquals(
        context, dates.sqlTime, read(JACKSON, write(gson, dates.sqlTime), java.sql.Time.class));
    assertArrayEquals(
        context, dates.bytes, read(JACKSON, write(gson, (Object) dates.bytes), byte[].class));
  }

  @Test
  public void readsGsonDateStrings() {
    long morning = epochMilli(LocalDateTime.of(2023, 11, 15, 9, 13, 20));
    long evening = epochMilli(LocalDateTime.of(2023, 11, 15, 21, 13, 20));
    withDefaults(
        LOS_ANGELES,
        Locale.US,
        () -> {
          // JDK 8 (COMPAT) and JDK 9+ (CLDR) US formats, and JDK 20+ spaces before AM/PM.
          assertGsonDate(morning, "Nov 15, 2023 9:13:20 AM");
          assertGsonDate(evening, "Nov 15, 2023 9:13:20 PM");
          assertGsonDate(morning, "Nov 15, 2023, 9:13:20 AM");
          assertGsonDate(morning, "Nov 15, 2023, 9:13:20\u202FAM");
          assertGsonDate(evening, "Nov 15, 2023, 9:13:20\u00A0PM");
          assertGsonDate(morning, DateFormat.getDateTimeInstance().format(new Date(morning)));

          assertEquals(
              epochMilli(LocalDateTime.of(2023, 11, 15, 0, 0)),
              read(JACKSON, "\"Nov 15, 2023\"", java.sql.Date.class).getTime());
          assertEquals(
              epochMilli(LocalDateTime.of(1970, 1, 1, 9, 13, 20)),
              read(JACKSON, "\"09:13:20 AM\"", java.sql.Time.class).getTime());
          assertEquals(
              epochMilli(LocalDateTime.of(1970, 1, 1, 21, 13, 20)),
              read(JACKSON, "\"09:13:20 PM\"", java.sql.Time.class).getTime());

          // Jackson's own forms are read as before, in UTC.
          assertEquals(
              1700019800123L,
              read(JACKSON, "\"2023-11-15T03:43:20.123+00:00\"", Date.class).getTime());
          assertEquals(1700019800123L, read(JACKSON, "1700019800123", Date.class).getTime());
          assertEquals(
              1700019800123L,
              read(
                      JACKSON,
                      write(JACKSON, new java.sql.Timestamp(1700019800123L)),
                      java.sql.Timestamp.class)
                  .getTime());
        });
    withDefaults(
        LOS_ANGELES,
        Locale.GERMANY,
        () -> {
          // Gson also reads the DEFAULT format of the default locale.
          String german = DateFormat.getDateTimeInstance().format(new Date(morning));
          assertGsonDate(morning, german);
          assertGsonDate(morning, "Nov 15, 2023, 9:13:20 AM");
          assertGsonDate(evening, "Nov 15, 2023 9:13:20 PM");
          assertEquals(
              epochMilli(LocalDateTime.of(2023, 11, 15, 0, 0)),
              read(JACKSON, "\"Nov 15, 2023\"", java.sql.Date.class).getTime());
          assertEquals(
              epochMilli(LocalDateTime.of(1970, 1, 1, 9, 13, 20)),
              read(JACKSON, "\"09:13:20 AM\"", java.sql.Time.class).getTime());
        });
  }

  private static void assertGsonDate(long expected, String text) {
    String json = "\"" + text + "\"";
    assertEquals(text, expected, read(JACKSON, json, Date.class).getTime());
    java.sql.Timestamp timestamp = read(JACKSON, json, java.sql.Timestamp.class);
    assertEquals(text, expected, timestamp.getTime());
    Dates dates = read(JACKSON, "{\"date\":" + json + ",\"dateList\":[" + json + "]}", Dates.class);
    assertEquals(text, expected, dates.date.getTime());
    assertEquals(text, Collections.singletonList(new Date(expected)), dates.dateList);
  }

  @Test
  public void unparseableDateStringKeepsJacksonError() {
    ObjectMapper jackson = new ObjectMapper();
    for (Class<?> type :
        Arrays.asList(
            Date.class, java.sql.Timestamp.class, java.sql.Date.class, java.sql.Time.class)) {
      DataConverterException e =
          assertThrows(DataConverterException.class, () -> read(JACKSON, "\"not a date\"", type));
      JsonProcessingException expected =
          assertThrows(
              JsonProcessingException.class, () -> jackson.readValue("\"not a date\"", type));
      assertEquals(type.getName(), expected.getClass(), e.getCause().getClass());
      assertEquals(type.getName(), expected.getMessage(), e.getCause().getMessage());
    }
    assertNull(GsonCompatibility.parseGsonDate(Date.class, "Nov 15, 2023"));
    assertNull(GsonCompatibility.parseGsonDate(java.sql.Date.class, "15 Nov 2023"));
    assertNull(GsonCompatibility.parseGsonDate(java.sql.Time.class, "21:13"));
  }

  @Test
  public void readsGsonCalendar() {
    withDefaults(
        LOS_ANGELES,
        Locale.US,
        () -> {
          long expected = epochMilli(LocalDateTime.of(2023, 11, 15, 3, 43, 20));
          for (Class<? extends Calendar> type :
              Arrays.asList(Calendar.class, GregorianCalendar.class)) {
            Calendar calendar = read(JACKSON, GSON_CALENDAR, type);
            assertEquals(GregorianCalendar.class, calendar.getClass());
            assertEquals(expected, calendar.getTimeInMillis());
            // Like JsonDataConverter, in the default time zone.
            assertEquals(LOS_ANGELES.getID(), calendar.getTimeZone().getID());
          }
          Dates dates =
              read(
                  JACKSON,
                  "{\"calendar\":"
                      + GSON_CALENDAR
                      + ",\"gregorianCalendar\":"
                      + GSON_CALENDAR
                      + "}",
                  Dates.class);
          assertEquals(expected, dates.calendar.getTimeInMillis());
          assertEquals(expected, dates.gregorianCalendar.getTimeInMillis());

          // Jackson's own forms.
          assertEquals(
              1700019800123L, read(JACKSON, "1700019800123", Calendar.class).getTimeInMillis());
          assertEquals(
              1700019800123L,
              read(JACKSON, "\"2023-11-15T03:43:20.123+00:00\"", GregorianCalendar.class)
                  .getTimeInMillis());
        });
  }

  // ---------- Optional ----------

  @Test
  public void readsGsonOptionals() {
    assertEquals(
        Optional.of("x"),
        read(JACKSON, "{\"value\":\"x\"}", new TypeReference<Optional<String>>() {}));
    assertEquals(
        Optional.empty(),
        read(JACKSON, "{\"value\":null}", new TypeReference<Optional<String>>() {}));
    assertEquals(
        Optional.of(5), read(JACKSON, "{\"value\":5}", new TypeReference<Optional<Integer>>() {}));
    assertEquals(
        Optional.of(Arrays.asList("a", "b")),
        read(JACKSON, "{\"value\":[\"a\",\"b\"]}", new TypeReference<Optional<List<String>>>() {}));
    assertArrayEquals(
        new String[] {"a"},
        read(JACKSON, "{\"value\":[\"a\"]}", new TypeReference<Optional<String[]>>() {}).get());
    assertEquals(
        Optional.of(Color.GREEN),
        read(JACKSON, "{\"value\":\"GREEN\"}", new TypeReference<Optional<Color>>() {}));
    assertEquals(
        Optional.of(LOCAL_DATE),
        read(
            JACKSON,
            "{\"value\":" + GSON_LOCAL_DATE + "}",
            new TypeReference<Optional<LocalDate>>() {}));
    assertEquals(
        "n",
        read(JACKSON, "{\"value\":{\"name\":\"n\"}}", new TypeReference<Optional<NoValue>>() {})
            .get()
            .name);

    // Jackson's own forms, including a bean that is an empty object.
    assertEquals(
        Optional.of("x"), read(JACKSON, "\"x\"", new TypeReference<Optional<String>>() {}));
    assertEquals(Optional.empty(), read(JACKSON, "null", new TypeReference<Optional<String>>() {}));
    assertEquals(
        "n",
        read(JACKSON, "{\"name\":\"n\"}", new TypeReference<Optional<NoValue>>() {}).get().name);
    assertNull(read(JACKSON, "{}", new TypeReference<Optional<NoValue>>() {}).get().name);

    String json =
        "{\"text\":{\"value\":\"x\"},\"number\":{\"value\":5},\"bean\":{\"value\":{\"name\":\"n\"}},"
            + "\"list\":{\"value\":[\"a\"]},\"color\":{\"value\":\"RED\"},"
            + "\"date\":{\"value\":"
            + GSON_LOCAL_DATE
            + "},\"empty\":{\"value\":null}}";
    Optionals optionals = read(JACKSON, json, Optionals.class);
    assertEquals(Optional.of("x"), optionals.text);
    assertEquals(Optional.of(5), optionals.number);
    assertEquals("n", optionals.bean.get().name);
    assertEquals(Optional.of(Collections.singletonList("a")), optionals.list);
    assertEquals(Optional.of(Color.RED), optionals.color);
    assertEquals(Optional.of(LOCAL_DATE), optionals.date);
    assertEquals(Optional.empty(), optionals.empty);
  }

  @Test
  public void ambiguousOptionalsKeepJacksonMeaning() {
    // A map or untyped value with the single key "value" is also what Jackson writes for them.
    assertEquals(
        Optional.of(Collections.singletonMap("value", 1)),
        read(JACKSON, "{\"value\":1}", new TypeReference<Optional<Map<String, Object>>>() {}));
    assertEquals(
        Optional.of(Collections.singletonMap("value", "x")),
        read(JACKSON, "{\"value\":\"x\"}", new TypeReference<Optional<Object>>() {}));
    JsonNode node =
        read(JACKSON, "{\"value\":1}", new TypeReference<Optional<JsonNode>>() {}).get();
    assertEquals(1, node.get("value").intValue());

    // A bean with a "value" property.
    WithValue withValue =
        read(JACKSON, "{\"value\":\"v\"}", new TypeReference<Optional<WithValue>>() {}).get();
    assertEquals("v", withValue.value);
    // Gson's form of such an Optional fails rather than giving another value.
    assertThrows(
        DataConverterException.class,
        () ->
            read(
                JACKSON,
                "{\"value\":{\"value\":\"v\",\"other\":1}}",
                new TypeReference<Optional<WithValue>>() {}));

    // The content of a nested Optional is read by its own deserializer.
    assertEquals(
        Optional.of(Optional.of("x")),
        read(JACKSON, "{\"value\":\"x\"}", new TypeReference<Optional<Optional<String>>>() {}));

    // Other keys: not what Gson writes for an Optional.
    assertThrows(
        DataConverterException.class,
        () ->
            read(
                JACKSON,
                "{\"value\":\"x\",\"other\":1}",
                new TypeReference<Optional<String>>() {}));
  }

  /** Serializable is read as an untyped value, and Jackson writes a Map.Entry as an object. */
  @Test
  public void optionalsOfInterfacesKeepJacksonMeaning() {
    assertEquals(
        Optional.of(Collections.singletonMap("value", "x")),
        read(JACKSON, "{\"value\":\"x\"}", new TypeReference<Optional<Serializable>>() {}));

    Map.Entry<String, String> entry = new AbstractMap.SimpleEntry<>("value", "x");
    String json = write(JACKSON, Optional.of(entry));
    assertEquals("{\"value\":\"x\"}", json);
    assertEquals(
        Optional.of(entry),
        read(JACKSON, json, new TypeReference<Optional<Map.Entry<String, String>>>() {}));
  }

  /** Also when the application registers a JavaTimeModule and a Jdk8Module of its own. */
  @Test
  public void readsGsonShapesWithApplicationJavaTimeModule() {
    DataConverter converter =
        new JacksonDataConverter(
            mapper -> mapper.registerModule(new JavaTimeModule()).registerModule(new Jdk8Module()));
    Duration duration = Duration.ofSeconds(90, 5_000_000);
    assertEquals(duration, read(converter, "{\"seconds\":90,\"nanos\":5000000}", Duration.class));
    assertEquals(duration, read(converter, write(converter, duration), Duration.class));
    assertEquals(LOCAL_DATE, read(converter, GSON_LOCAL_DATE, LocalDate.class));
    assertEquals(
        OptionalInt.of(7), read(converter, "{\"isPresent\":true,\"value\":7}", OptionalInt.class));
    assertEquals(
        Optional.of("x"),
        read(converter, "{\"value\":\"x\"}", new TypeReference<Optional<String>>() {}));
  }

  @Test
  public void otherReferenceTypesAreNotWrapped() {
    assertEquals(
        "x", read(JACKSON, "\"x\"", new TypeReference<AtomicReference<String>>() {}).get());
    ReferenceType type =
        (ReferenceType)
            TypeFactory.defaultInstance()
                .constructType(new TypeReference<AtomicReference<String>>() {});
    JsonDeserializer<?> deserializer = StringDeserializer.instance;
    assertSame(
        deserializer,
        new GsonCompatibility.ReadModifier()
            .modifyReferenceDeserializer(null, type, null, deserializer));
  }

  @Test
  public void optionalHeuristicClassifiesContentTypes() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    DeserializationContext ctxt =
        ((DefaultDeserializationContext) mapper.getDeserializationContext())
            .createInstance(mapper.getDeserializationConfig(), mapper.createParser("{}"), null);
    TypeFactory types = mapper.getTypeFactory();
    assertTrue(GsonCompatibility.isUnambiguous(types.constructType(int.class), ctxt));
    assertTrue(GsonCompatibility.isUnambiguous(types.constructType(String.class), ctxt));
    assertTrue(GsonCompatibility.isUnambiguous(types.constructType(Color.class), ctxt));
    assertTrue(GsonCompatibility.isUnambiguous(types.constructType(int[].class), ctxt));
    assertTrue(GsonCompatibility.isUnambiguous(types.constructType(NoValue.class), ctxt));
    assertFalse(GsonCompatibility.isUnambiguous(types.constructType(Object.class), ctxt));
    assertFalse(GsonCompatibility.isUnambiguous(types.constructType(ObjectNode.class), ctxt));
    assertFalse(GsonCompatibility.isUnambiguous(types.constructType(WithValue.class), ctxt));
    assertFalse(
        GsonCompatibility.isUnambiguous(
            types.constructType(new TypeReference<Map<String, Integer>>() {}), ctxt));
    assertFalse(
        GsonCompatibility.isUnambiguous(
            types.constructType(new TypeReference<AtomicReference<String>>() {}), ctxt));
  }

  // ---------- What is not read ----------

  @Test
  public void nonGsonObjectsStillFail() {
    Object[][] cases = {
      // Other keys than Gson writes.
      {LocalDate.class, "{\"year\":2023,\"month\":11,\"day\":15,\"x\":1}"},
      {LocalDate.class, "{\"year\":2023,\"month\":11,\"dayOfMonth\":15}"},
      {LocalDate.class, "{}"},
      {Period.class, "{\"years\":1,\"months\":2}"},
      {OffsetTime.class, "{\"time\":" + GSON_LOCAL_TIME + "}"},
      {Calendar.class, "{\"year\":2023,\"month\":10,\"dayOfMonth\":15}"},
      {OptionalLong.class, "{\"isPresent\":true,\"value\":1,\"x\":1}"},
      // Values Gson does not write.
      {LocalDate.class, "{\"year\":2023,\"month\":11,\"day\":15.5}"},
      {LocalDate.class, "{\"year\":2023,\"month\":11,\"day\":\"15\"}"},
      {LocalDate.class, "{\"year\":3000000000,\"month\":11,\"day\":15}"},
      {Year.class, "{\"year\":1.5}"},
      {ZoneOffset.class, "{\"totalSeconds\":\"x\"}"},
      {LocalDateTime.class, "{\"date\":" + GSON_LOCAL_DATE + ",\"time\":\"03:43:20\"}"},
      {OffsetDateTime.class, "{\"dateTime\":\"x\",\"offset\":" + GSON_ZONE_OFFSET + "}"},
      {Instant.class, "{\"seconds\":92233720368547758070,\"nanos\":0}"},
      {Instant.class, "{\"seconds\":1.5,\"nanos\":0}"},
      {OptionalLong.class, "{\"isPresent\":true,\"value\":1.5}"},
      {OptionalInt.class, "{\"isPresent\":\"yes\",\"value\":1}"},
      {OptionalDouble.class, "{\"isPresent\":true,\"value\":\"x\"}"},
      // Invalid dates and times (DateTimeException).
      {LocalDate.class, "{\"year\":2023,\"month\":13,\"day\":15}"},
      {LocalTime.class, "{\"hour\":25,\"minute\":0,\"second\":0,\"nano\":0}"},
      {ZoneOffset.class, "{\"totalSeconds\":100000}"},
      {YearMonth.class, "{\"year\":2023,\"month\":0}"},
      {MonthDay.class, "{\"month\":2,\"day\":30}"},
      {Instant.class, "{\"seconds\":9223372036854775807,\"nanos\":0}"},
      // Out of range (ArithmeticException).
      {Instant.class, "{\"seconds\":9223372036854775807,\"nanos\":1000000000}"},
      // An object for a type that Gson writes as a string.
      {Date.class, "{\"year\":2023}"},
    };
    ObjectMapper jackson =
        new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module());
    for (Object[] c : cases) {
      Class<?> type = (Class<?>) c[0];
      String json = (String) c[1];
      String message = type.getSimpleName() + " " + json;
      DataConverterException e =
          assertThrows(message, DataConverterException.class, () -> read(JACKSON, json, type));
      // The error of Jackson's own deserializer.
      JsonProcessingException expected =
          assertThrows(message, JsonProcessingException.class, () -> jackson.readValue(json, type));
      assertEquals(message, expected.getClass(), e.getCause().getClass());
      assertEquals(
          message,
          expected.getOriginalMessage(),
          ((JsonProcessingException) e.getCause()).getOriginalMessage());
    }
    assertThrows(
        RuntimeException.class,
        () -> GsonCompatibility.fromGsonObject(String.class, jackson.createObjectNode()));
    assertThrows(
        RuntimeException.class, () -> GsonCompatibility.fromGsonObject(LocalDate.class, null));
  }

  @Test
  public void typesGsonCouldNotReadAreNotAccepted() {
    String zoned =
        "{\"dateTime\":"
            + GSON_LOCAL_DATE_TIME
            + ",\"offset\":{\"totalSeconds\":3600},\"zone\":{\"id\":\"Europe/Paris\"}}";
    assertThrows(DataConverterException.class, () -> read(JACKSON, zoned, ZonedDateTime.class));
    assertThrows(
        DataConverterException.class,
        () -> read(JACKSON, "{\"id\":\"Europe/Paris\"}", ZoneId.class));
    assertThrows(
        DataConverterException.class,
        () -> read(JACKSON, "{\"ID\":\"Europe/Paris\"}", TimeZone.class));
    // Jackson's own forms are read.
    assertEquals(
        ZonedDateTime.of(LOCAL_DATE_TIME, ZoneId.of("Europe/Paris")),
        read(
            JACKSON, "\"2023-11-15T03:43:20.000000005+01:00[Europe/Paris]\"", ZonedDateTime.class));
    assertEquals(ZoneId.of("Europe/Paris"), read(JACKSON, "\"Europe/Paris\"", ZoneId.class));
  }

  // ---------- Configuration of the application ----------

  /** Reads dd.MM.yyyy only. */
  private static final class DottedLocalDateDeserializer extends JsonDeserializer<LocalDate> {
    @Override
    public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (!p.hasToken(JsonToken.VALUE_STRING)) {
        return ctxt.reportInputMismatch(LocalDate.class, "dd.MM.yyyy expected");
      }
      return LocalDate.parse(p.getText(), DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }
  }

  /** Reads an OptionalInt from its decimal text only. */
  private static final class TextOptionalIntDeserializer extends JsonDeserializer<OptionalInt> {
    @Override
    public OptionalInt deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (!p.hasToken(JsonToken.VALUE_STRING)) {
        return ctxt.reportInputMismatch(OptionalInt.class, "decimal text expected");
      }
      return OptionalInt.of(Integer.parseInt(p.getText()));
    }
  }

  @Test
  public void applicationDeserializersAreNotWrapped() {
    DataConverter converter =
        new JacksonDataConverter(
            mapper ->
                mapper.registerModule(
                    new SimpleModule("application-deserializers")
                        .addDeserializer(LocalDate.class, new DottedLocalDateDeserializer())
                        .addDeserializer(OptionalInt.class, new TextOptionalIntDeserializer())));
    assertEquals(LOCAL_DATE, read(converter, "\"15.11.2023\"", LocalDate.class));
    DataConverterException e =
        assertThrows(
            DataConverterException.class, () -> read(converter, GSON_LOCAL_DATE, LocalDate.class));
    assertTrue(
        e.getCause().getMessage(), e.getCause().getMessage().contains("dd.MM.yyyy expected"));
    assertThrows(
        DataConverterException.class,
        () -> read(converter, "{\"localDate\":" + GSON_LOCAL_DATE + "}", Times.class));

    assertEquals(OptionalInt.of(7), read(converter, "\"7\"", OptionalInt.class));
    e =
        assertThrows(
            DataConverterException.class,
            () -> read(converter, "{\"isPresent\":true,\"value\":7}", OptionalInt.class));
    assertTrue(
        e.getCause().getMessage(), e.getCause().getMessage().contains("decimal text expected"));

    // The other types keep reading Gson's forms, and other converters are not affected.
    assertEquals(INSTANT, read(converter, GSON_INSTANT, Instant.class));
    assertEquals(LOCAL_DATE, read(JACKSON, GSON_LOCAL_DATE, LocalDate.class));
  }

  @Test
  public void jsonFormatStillApplies() {
    withDefaults(
        LOS_ANGELES,
        Locale.US,
        () -> {
          Formatted formatted =
              read(
                  JACKSON,
                  "{\"day\":\"15/11/2023\",\"when\":\"2023-11-15 03:43\"}",
                  Formatted.class);
          assertEquals(LOCAL_DATE, formatted.day);
          assertEquals(1700019780000L, formatted.when.getTime());
          assertEquals(
              "{\"day\":\"15/11/2023\",\"when\":\"2023-11-15 03:43\"}", write(JACKSON, formatted));

          // Gson's forms are still read for these fields.
          Formatted gson =
              read(
                  JACKSON,
                  "{\"day\":" + GSON_LOCAL_DATE + ",\"when\":\"Nov 15, 2023, 9:13:20 AM\"}",
                  Formatted.class);
          assertEquals(LOCAL_DATE, gson.day);
          assertEquals(epochMilli(LocalDateTime.of(2023, 11, 15, 9, 13, 20)), gson.when.getTime());

          // Other text still gives the error of the format.
          assertThrows(
              DataConverterException.class,
              () -> read(JACKSON, "{\"day\":\"2023-11-15\"}", Formatted.class));
        });
  }

  // ---------- Exceptions ----------

  @Test
  public void readsGsonWorkflowFailureException() {
    String workflowFailure =
        "{\"decisionTaskCompletedEventId\":5,\"execution\":{\"workflowId\":\"w\",\"runId\":\"r\"},"
            + "\"workflowType\":{\"value\":\"MyWorkflow\"},"
            + "\"detailMessage\":\"WorkflowType\\u003d\\\"MyWorkflow\\\", WorkflowID\\u003d\\\"w\\\"\","
            + "\"cause\":{\"detailMessage\":\"x\",\"stackTrace\":\"\",\"suppressedExceptions\":[],"
            + "\"class\":\"java.lang.IllegalStateException\"},"
            + "\"stackTrace\":\"\",\"suppressedExceptions\":[],"
            + "\"class\":\"com.uber.cadence.client.WorkflowFailureException\"}";
    WorkflowFailureException failure =
        read(JACKSON, workflowFailure, WorkflowFailureException.class);
    assertEquals(Optional.of("MyWorkflow"), failure.getWorkflowType());
    assertEquals(new WorkflowExecution().setWorkflowId("w").setRunId("r"), failure.getExecution());
    assertEquals(5, failure.getDecisionTaskCompletedEventId());
    assertEquals("WorkflowType=\"MyWorkflow\", WorkflowID=\"w\"", failure.getMessage());
    assertEquals(IllegalStateException.class, failure.getCause().getClass());
    assertEquals("x", failure.getCause().getMessage());

    String activityFailure =
        "{\"attempt\":3,\"backoff\":{\"seconds\":2,\"nanos\":500},\"activityType\":{\"name\":\"Act\"},"
            + "\"activityId\":\"a1\",\"eventId\":7,\"detailMessage\":\"boom\","
            + "\"cause\":{\"detailMessage\":\"boom\",\"stackTrace\":\"\",\"suppressedExceptions\":[],"
            + "\"class\":\"java.lang.IllegalStateException\"},"
            + "\"stackTrace\":\"\",\"suppressedExceptions\":[],"
            + "\"class\":\"com.uber.cadence.workflow.ActivityFailureException\"}";
    ActivityFailureException activity =
        read(JACKSON, activityFailure, ActivityFailureException.class);
    assertEquals(Duration.ofSeconds(2, 500), activity.getBackoff());
    assertEquals(3, activity.getAttempt());
    assertEquals("Act", activity.getActivityType().getName());
    assertEquals("a1", activity.getActivityId());
    assertEquals(7, activity.getEventId());
    assertEquals(IllegalStateException.class, activity.getCause().getClass());

    // Read as Throwable, it is restored as its own class.
    Throwable throwable = read(JACKSON, workflowFailure, Throwable.class);
    assertEquals(
        Optional.of("MyWorkflow"), ((WorkflowFailureException) throwable).getWorkflowType());
  }

  // ---------- Untyped numbers ----------

  @Test
  public void untypedNumbersDefaultToJacksonTypes() {
    assertEquals(42, read(JACKSON, "42", Object.class));
    assertEquals(9007199254740993L, read(JACKSON, "9007199254740993", Object.class));
    assertEquals(2.5, read(JACKSON, "2.5", Object.class));
    Map<String, Object> map =
        read(
            JACKSON,
            "{\"i\":1,\"big\":9007199254740993,\"d\":2.5}",
            new TypeReference<Map<String, Object>>() {});
    assertEquals(ImmutableMap.of("i", 1, "big", 9007199254740993L, "d", 2.5), map);
    assertEquals(
        Arrays.asList(1, 2.5), read(JACKSON, "[1,2.5]", new TypeReference<List<Object>>() {}));
  }

  @Test
  public void gsonCompatibleNumbersModuleDecodesDouble() {
    DataConverter converter =
        new JacksonDataConverter(
            mapper -> mapper.registerModule(JacksonDataConverter.gsonCompatibleNumbersModule()));
    assertEquals(42.0, read(converter, "42", Object.class));
    assertEquals(2.5, read(converter, "2.5", Object.class));
    assertEquals("x", read(converter, "\"x\"", Object.class));
    assertEquals(true, read(converter, "true", Object.class));
    assertNull(read(converter, "null", Object.class));

    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("k", 7.0);
    nested.put("n", null);
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("i", 1.0);
    expected.put("s", "x");
    expected.put("b", false);
    expected.put("o", nested);
    expected.put("l", Arrays.asList(1.0, "a", true, null, Collections.emptyMap()));
    String json =
        "{\"i\":1,\"s\":\"x\",\"b\":false,\"o\":{\"k\":7,\"n\":null},\"l\":[1,\"a\",true,null,{}]}";
    Object untyped = read(converter, json, Object.class);
    assertEquals(expected, untyped);
    assertEquals(LinkedHashMap.class, untyped.getClass());
    assertEquals(
        Arrays.asList("i", "s", "b", "o", "l"),
        Arrays.asList(((Map<?, ?>) untyped).keySet().toArray()));
    assertEquals(expected, read(converter, json, new TypeReference<Map<String, Object>>() {}));
    assertEquals(
        Arrays.asList(1.0, 2.5, Collections.singletonList(3.0)),
        read(converter, "[1,2.5,[3]]", new TypeReference<List<Object>>() {}));
    assertArrayEquals(new Object[] {1.0, "a"}, read(converter, "[1,\"a\"]", Object[].class));
    assertArrayEquals(
        new Object[] {1.0, Collections.singletonMap("a", 2.0)},
        converter.fromDataArray(
            bytes("[1,{\"a\":2}]"),
            Object.class,
            new TypeReference<Map<String, Object>>() {}.getType()));

    // Declared number types are not changed.
    Untyped fields =
        read(
            converter,
            "{\"any\":1,\"number\":1,\"count\":1,\"attributes\":{\"a\":1}}",
            Untyped.class);
    assertEquals(1.0, fields.any);
    assertEquals(1, fields.number);
    assertEquals(Integer.valueOf(1), fields.count);
    assertEquals(Collections.singletonMap("a", 1.0), fields.attributes);

    // Like JsonDataConverter, integers above 2^53 lose precision.
    assertEquals(9.007199254740992E15, read(converter, "9007199254740993", Object.class));
    // The client's own payloads are not affected.
    assertEquals(retryOptions(), read(converter, RETRY_OPTIONS_JSON, RetryOptions.class));
  }

  // ---------- Durations of the client's own payloads ----------

  @Test
  public void internalPayloadsWrittenInGsonShape() {
    RetryOptions retryOptions = retryOptions();
    assertEquals(RETRY_OPTIONS_JSON, write(JACKSON, retryOptions));
    assertEquals(retryOptions, read(JACKSON, RETRY_OPTIONS_JSON, RetryOptions.class));
    DataConverter gson = JsonDataConverter.getInstance();
    if (canWrite(gson, Duration.ZERO)) {
      assertEquals(write(gson, retryOptions), write(JACKSON, retryOptions));
      assertEquals(retryOptions, read(gson, write(JACKSON, retryOptions), RetryOptions.class));
    }

    // Also inside other values and in several values.
    WithRetryOptions holder = new WithRetryOptions();
    holder.retryOptions = retryOptions;
    holder.timeout = Duration.ofSeconds(90, 5_000_000);
    String holderJson = write(JACKSON, holder);
    assertEquals(
        "{\"retryOptions\":" + RETRY_OPTIONS_JSON + ",\"timeout\":90.005000000}", holderJson);
    WithRetryOptions readHolder = read(JACKSON, holderJson, WithRetryOptions.class);
    assertEquals(retryOptions, readHolder.retryOptions);
    assertEquals(holder.timeout, readHolder.timeout);
    assertEquals("[" + RETRY_OPTIONS_JSON + ",\"x\"]", write(JACKSON, retryOptions, "x"));
    assertArrayEquals(
        new Object[] {retryOptions, "x"},
        JACKSON.fromDataArray(
            bytes("[" + RETRY_OPTIONS_JSON + ",\"x\"]"), RetryOptions.class, String.class));

    LocalActivityMarkerData marker =
        new LocalActivityMarkerData.Builder()
            .setActivityId("a1")
            .setActivityType(new ActivityType().setName("Act"))
            .setReplayTimeMillis(1700019800123L)
            .setAttempt(2)
            .setBackoff(Duration.ofMillis(1500))
            .build();
    byte[] header = Iterables.getOnlyElement(marker.getHeader(JACKSON).getFields().values());
    assertEquals(
        "{\"activityId\":\"a1\",\"activityType\":\"ActivityType(name=Act)\","
            + "\"errReason\":null,"
            + "\"replayTimeMillis\":1700019800123,\"attempt\":2,"
            + "\"backoff\":{\"seconds\":1,\"nanos\":500000000},\"isCancelled\":false}",
        new String(header, StandardCharsets.UTF_8));
    LocalActivityMarkerData readMarker =
        LocalActivityMarkerData.fromEventAttributes(
            new MarkerRecordedEventAttributes().setHeader(marker.getHeader(JACKSON)), JACKSON);
    assertEquals(Duration.ofMillis(1500), readMarker.getBackoff());
    assertEquals(2, readMarker.getAttempt());

    // Jackson's decimal seconds are read too.
    assertEquals(
        retryOptions,
        read(
            JACKSON,
            RETRY_OPTIONS_JSON
                .replace("{\"seconds\":1,\"nanos\":0}", "1.000000000")
                .replace("{\"seconds\":10,\"nanos\":500000000}", "10.5"),
            RetryOptions.class));

    Class<?> headerClass = headerClassOf();
    assertTrue(ClientPayloads.hasGsonShapedDurations(RetryOptions.class));
    assertTrue(ClientPayloads.hasGsonShapedDurations(headerClass));
    assertFalse(ClientPayloads.hasGsonShapedDurations(WithRetryOptions.class));
    assertTrue(ClientPayloads.isClientPayload(RetryOptions.class));
    assertTrue(ClientPayloads.isClientPayload(headerClass));
    assertFalse(ClientPayloads.isClientPayload(CheckedExceptionWrapper.class));
    assertFalse(ClientPayloads.isClientPayload(WithRetryOptions.class));
  }

  @Test
  public void userDurationsKeepJacksonShape() {
    Duration duration = Duration.ofSeconds(90, 5_000_000);
    assertEquals("90.005000000", write(JACKSON, duration));
    WithRetryOptions holder = new WithRetryOptions();
    holder.timeout = duration;
    assertEquals("{\"retryOptions\":null,\"timeout\":90.005000000}", write(JACKSON, holder));
    assertEquals("[90.005000000,1]", write(JACKSON, duration, 1));
    // Both forms are read.
    assertEquals(duration, read(JACKSON, "90.005000000", Duration.class));
    assertEquals(duration, read(JACKSON, "\"PT1M30.005S\"", Duration.class));
    assertEquals(duration, read(JACKSON, "{\"seconds\":90,\"nanos\":5000000}", Duration.class));
  }

  /** Writes a Duration as ISO-8601 text. */
  private static final class IsoDurationSerializer extends JsonSerializer<Duration> {
    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeString(value.toString());
    }
  }

  @Test
  public void gsonDurationSerializerUsedEvenWithUserDurationModule() {
    DataConverter converter =
        new JacksonDataConverter(
            mapper ->
                mapper.registerModule(
                    new SimpleModule("iso-durations")
                        .addSerializer(Duration.class, new IsoDurationSerializer())));
    RetryOptions retryOptions = retryOptions();
    WithRetryOptions holder = new WithRetryOptions();
    holder.retryOptions = retryOptions;
    holder.timeout = Duration.ofSeconds(90, 5_000_000);

    assertEquals(RETRY_OPTIONS_JSON, write(converter, retryOptions));
    String json = write(converter, holder);
    assertEquals("{\"retryOptions\":" + RETRY_OPTIONS_JSON + ",\"timeout\":\"PT1M30.005S\"}", json);
    assertEquals(
        "[" + RETRY_OPTIONS_JSON + ",\"PT1M30.005S\"]",
        write(converter, retryOptions, holder.timeout));
    WithRetryOptions read = read(converter, json, WithRetryOptions.class);
    assertEquals(retryOptions, read.retryOptions);
    assertEquals(holder.timeout, read.timeout);
  }

  @Test
  public void clientPayloadRoutingIgnoresCustomization() {
    DataConverter converter =
        new JacksonDataConverter(
            mapper -> mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    RetryOptions retryOptions = retryOptions();
    assertEquals(RETRY_OPTIONS_JSON, write(converter, retryOptions));
    assertEquals(retryOptions, read(converter, RETRY_OPTIONS_JSON, RetryOptions.class));
    assertEquals(
        retryOptions, converter.fromDataArray(bytes(RETRY_OPTIONS_JSON), RetryOptions.class)[0]);

    SnakeCased snakeCased = new SnakeCased();
    snakeCased.someField = "x";
    assertEquals("{\"some_field\":\"x\"}", write(converter, snakeCased));
    assertEquals("x", read(converter, "{\"some_field\":\"x\"}", SnakeCased.class).someField);

    // Inside another value the customization applies, but the Durations keep Gson's form.
    WithRetryOptions holder = new WithRetryOptions();
    holder.retryOptions = retryOptions;
    String json = write(converter, holder);
    assertTrue(json, json.contains("\"initial_interval\":{\"seconds\":1,\"nanos\":0}"));
    assertEquals(retryOptions, read(converter, json, WithRetryOptions.class).retryOptions);
  }

  // ---------- Helpers ----------

  private static Times expectedTimes() {
    Times times = new Times();
    times.localDate = LOCAL_DATE;
    times.localTime = LOCAL_TIME;
    times.localDateTime = LOCAL_DATE_TIME;
    times.instant = INSTANT;
    times.offsetDateTime = OffsetDateTime.of(LOCAL_DATE_TIME, ZoneOffset.ofHours(5));
    times.offsetTime = OffsetTime.of(LOCAL_TIME, ZoneOffset.ofHours(-3));
    times.zoneOffset = ZoneOffset.ofHoursMinutes(5, 30);
    times.period = Period.of(1, 2, 3);
    times.year = Year.of(2023);
    times.yearMonth = YearMonth.of(2023, 11);
    times.monthDay = MonthDay.of(11, 15);
    times.duration = Duration.ofSeconds(90, 5_000_000);
    times.optionalInt = OptionalInt.of(7);
    times.optionalLong = OptionalLong.empty();
    times.optionalDouble = OptionalDouble.of(7.5);
    times.optional = Optional.of("x");
    times.dates = Arrays.asList(LOCAL_DATE, LocalDate.of(2023, 11, 16));
    times.instants = Collections.singletonMap("a", INSTANT);
    return times;
  }

  private static RetryOptions retryOptions() {
    return new RetryOptions.Builder()
        .setInitialInterval(Duration.ofSeconds(1))
        .setBackoffCoefficient(2.0)
        .setExpiration(Duration.ofMinutes(1))
        .setMaximumAttempts(3)
        .setMaximumInterval(Duration.ofMillis(10_500))
        .setDoNotRetry(IllegalStateException.class)
        .build();
  }

  private static Class<?> headerClassOf() {
    try {
      return Class.forName(
          "com.uber.cadence.internal.common.LocalActivityMarkerData$LocalActivityMarkerHeader");
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }

  private static long epochMilli(LocalDateTime inLosAngeles) {
    return inLosAngeles.atZone(LOS_ANGELES.toZoneId()).toInstant().toEpochMilli();
  }

  private static boolean canWrite(DataConverter converter, Object value) {
    try {
      converter.toData(value);
      return true;
    } catch (DataConverterException e) {
      // JDK 16+ without --add-opens for the package of the value.
      return false;
    }
  }

  /** Runs the body with the default time zone and locale set, and restores them. */
  private static void withDefaults(TimeZone timeZone, Locale locale, Runnable body) {
    TimeZone previousTimeZone = TimeZone.getDefault();
    Locale previousLocale = Locale.getDefault();
    Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
    Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
    try {
      TimeZone.setDefault(timeZone);
      Locale.setDefault(locale);
      body.run();
    } finally {
      TimeZone.setDefault(previousTimeZone);
      Locale.setDefault(previousLocale);
      Locale.setDefault(Locale.Category.FORMAT, previousFormat);
      Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
    }
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String write(DataConverter converter, Object... values) {
    return new String(converter.toData(values), StandardCharsets.UTF_8);
  }

  private static <T> T read(DataConverter converter, String json, Class<T> type) {
    return converter.fromData(bytes(json), type, type);
  }

  @SuppressWarnings("unchecked")
  private static <T> T read(DataConverter converter, String json, TypeReference<T> type) {
    Type valueType = type.getType();
    Class<T> raw = (Class<T>) TypeFactory.rawClass(valueType);
    return converter.fromData(bytes(json), raw, valueType);
  }
}
