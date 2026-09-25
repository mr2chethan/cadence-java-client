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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.ReferenceType;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TimeZone;

/**
 * Lets JacksonDataConverter read the JSON that JsonDataConverter (Gson) wrote, and write the
 * client's own payloads so that JsonDataConverter can read them.
 */
final class GsonCompatibility {

  private GsonCompatibility() {}

  // ---------- Reading the object shapes of JsonDataConverter ----------

  /**
   * Types Gson writes as a JSON object of their private fields, which Jackson writes as a scalar.
   */
  private static final ImmutableSet<Class<?>> OBJECT_SHAPED =
      ImmutableSet.<Class<?>>builder()
          .add(LocalDate.class, LocalTime.class, LocalDateTime.class, Instant.class, Duration.class)
          .add(OffsetDateTime.class, OffsetTime.class, ZoneOffset.class)
          .add(Period.class, Year.class, YearMonth.class, MonthDay.class)
          .add(Calendar.class, GregorianCalendar.class)
          .add(OptionalInt.class, OptionalLong.class, OptionalDouble.class)
          .build();

  /** Types Gson writes as a formatted date string that Jackson cannot parse. */
  private static final ImmutableSet<Class<?>> DATE_STRING =
      ImmutableSet.of(
          Date.class, java.sql.Timestamp.class, java.sql.Date.class, java.sql.Time.class);

  /** Not a JSON object Gson wrote for the type: the wrapped deserializer handles it. */
  private static final class NotGsonShape extends RuntimeException {
    NotGsonShape() {
      super(null, null, false, false);
    }
  }

  static final class ReadModifier extends BeanDeserializerModifier {
    @Override
    public JsonDeserializer<?> modifyDeserializer(
        DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
      Class<?> type = beanDesc.getBeanClass();
      if ((OBJECT_SHAPED.contains(type) || DATE_STRING.contains(type))
          && isJacksonDeserializer(deserializer)) {
        return new GsonShapeDeserializer(type, deserializer);
      }
      return deserializer;
    }

    @Override
    public JsonDeserializer<?> modifyReferenceDeserializer(
        DeserializationConfig config,
        ReferenceType type,
        BeanDescription beanDesc,
        JsonDeserializer<?> deserializer) {
      if (!isJacksonDeserializer(deserializer)) {
        return deserializer;
      }
      if (type.hasRawClass(Optional.class)) {
        return new GsonOptionalDeserializer(type, deserializer);
      }
      // Jdk8Module makes OptionalInt, OptionalLong and OptionalDouble reference types too.
      if (OBJECT_SHAPED.contains(type.getRawClass())) {
        return new GsonShapeDeserializer(type.getRawClass(), deserializer);
      }
      return deserializer;
    }
  }

  /** A deserializer registered by the application for one of these types is left alone. */
  private static boolean isJacksonDeserializer(JsonDeserializer<?> deserializer) {
    return deserializer.getClass().getName().startsWith("com.fasterxml.jackson.");
  }

  private static final class GsonShapeDeserializer extends DelegatingDeserializer {
    private final Class<?> type;

    GsonShapeDeserializer(Class<?> type, JsonDeserializer<?> delegate) {
      super(delegate);
      this.type = type;
    }

    @Override
    protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
      return new GsonShapeDeserializer(type, delegate);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (p.hasToken(JsonToken.START_OBJECT) && OBJECT_SHAPED.contains(type)) {
        JsonNode node = ctxt.readTree(p);
        try {
          return fromGsonObject(type, node);
        } catch (NotGsonShape | DateTimeException | ArithmeticException e) {
          return deserializeTree(_delegatee, node, p, ctxt);
        }
      }
      if (p.hasToken(JsonToken.VALUE_STRING) && DATE_STRING.contains(type)) {
        String text = p.getText();
        try {
          return _delegatee.deserialize(p, ctxt);
        } catch (IOException | RuntimeException e) {
          Date date = parseGsonDate(type, text);
          if (date == null) {
            throw e;
          }
          return toType(type, date);
        }
      }
      return _delegatee.deserialize(p, ctxt);
    }
  }

  private static Object deserializeTree(
      JsonDeserializer<?> deserializer, JsonNode node, JsonParser p, DeserializationContext ctxt)
      throws IOException {
    try (JsonParser tree = node.traverse(p.getCodec())) {
      tree.nextToken();
      return deserializer.deserialize(tree, ctxt);
    }
  }

  /** The JSON object Gson writes through reflection for these types, on JDK 15 and earlier. */
  static Object fromGsonObject(Class<?> type, JsonNode node) {
    if (type == LocalDate.class) {
      return localDate(node);
    } else if (type == LocalTime.class) {
      return localTime(node);
    } else if (type == LocalDateTime.class) {
      keys(node, "date", "time");
      return LocalDateTime.of(localDate(node.get("date")), localTime(node.get("time")));
    } else if (type == Instant.class) {
      keys(node, "seconds", "nanos");
      return Instant.ofEpochSecond(longOf(node, "seconds"), longOf(node, "nanos"));
    } else if (type == Duration.class) {
      keys(node, "seconds", "nanos");
      return Duration.ofSeconds(longOf(node, "seconds"), longOf(node, "nanos"));
    } else if (type == OffsetDateTime.class) {
      keys(node, "dateTime", "offset");
      JsonNode dateTime = node.get("dateTime");
      keys(dateTime, "date", "time");
      return OffsetDateTime.of(
          LocalDateTime.of(localDate(dateTime.get("date")), localTime(dateTime.get("time"))),
          zoneOffset(node.get("offset")));
    } else if (type == OffsetTime.class) {
      keys(node, "time", "offset");
      return OffsetTime.of(localTime(node.get("time")), zoneOffset(node.get("offset")));
    } else if (type == ZoneOffset.class) {
      return zoneOffset(node);
    } else if (type == Period.class) {
      keys(node, "years", "months", "days");
      return Period.of(intOf(node, "years"), intOf(node, "months"), intOf(node, "days"));
    } else if (type == Year.class) {
      keys(node, "year");
      return Year.of(intOf(node, "year"));
    } else if (type == YearMonth.class) {
      keys(node, "year", "month");
      return YearMonth.of(intOf(node, "year"), intOf(node, "month"));
    } else if (type == MonthDay.class) {
      keys(node, "month", "day");
      return MonthDay.of(intOf(node, "month"), intOf(node, "day"));
    } else if (type == Calendar.class || type == GregorianCalendar.class) {
      // Gson's own Calendar adapter: the fields in the JVM default time zone, no milliseconds.
      keys(node, "year", "month", "dayOfMonth", "hourOfDay", "minute", "second");
      return new GregorianCalendar(
          intOf(node, "year"),
          intOf(node, "month"),
          intOf(node, "dayOfMonth"),
          intOf(node, "hourOfDay"),
          intOf(node, "minute"),
          intOf(node, "second"));
    } else if (type == OptionalInt.class) {
      keys(node, "isPresent", "value");
      return present(node) ? OptionalInt.of(intOf(node, "value")) : OptionalInt.empty();
    } else if (type == OptionalLong.class) {
      keys(node, "isPresent", "value");
      return present(node) ? OptionalLong.of(longOf(node, "value")) : OptionalLong.empty();
    } else if (type == OptionalDouble.class) {
      keys(node, "isPresent", "value");
      JsonNode value = node.get("value");
      if (!value.isNumber()) {
        throw new NotGsonShape();
      }
      return present(node) ? OptionalDouble.of(value.doubleValue()) : OptionalDouble.empty();
    }
    throw new NotGsonShape();
  }

  private static LocalDate localDate(JsonNode node) {
    keys(node, "year", "month", "day");
    return LocalDate.of(intOf(node, "year"), intOf(node, "month"), intOf(node, "day"));
  }

  private static LocalTime localTime(JsonNode node) {
    keys(node, "hour", "minute", "second", "nano");
    return LocalTime.of(
        intOf(node, "hour"), intOf(node, "minute"), intOf(node, "second"), intOf(node, "nano"));
  }

  private static ZoneOffset zoneOffset(JsonNode node) {
    keys(node, "totalSeconds");
    return ZoneOffset.ofTotalSeconds(intOf(node, "totalSeconds"));
  }

  private static boolean present(JsonNode node) {
    JsonNode present = node.get("isPresent");
    if (!present.isBoolean()) {
      throw new NotGsonShape();
    }
    return present.booleanValue();
  }

  /** The node is an object with exactly these keys, as Gson writes every field (serializeNulls). */
  private static void keys(JsonNode node, String... names) {
    if (node == null || !node.isObject() || node.size() != names.length) {
      throw new NotGsonShape();
    }
    for (String name : names) {
      if (!node.has(name)) {
        throw new NotGsonShape();
      }
    }
  }

  private static int intOf(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new NotGsonShape();
    }
    return value.intValue();
  }

  private static long longOf(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new NotGsonShape();
    }
    return value.longValue();
  }

  // ---------- Reading the date strings of JsonDataConverter ----------

  /**
   * Parses a date as Gson 2.10 does on this JVM: java.util.Date and Timestamp with the US and
   * default locale DEFAULT date-time styles (whose pattern differs between JDK 8 and JDK 9+, so
   * both are tried), java.sql.Date with "MMM d, yyyy" and java.sql.Time with "hh:mm:ss a", all in
   * the JVM default time zone. Returns null when none of them parses the text.
   */
  static Date parseGsonDate(Class<?> type, String text) {
    // JDK 20+ (CLDR 42) writes a narrow no-break space before AM/PM.
    String normalized = text.replace('\u202F', ' ').replace('\u00A0', ' ');
    for (DateFormat format : gsonFormats(type)) {
      try {
        return format.parse(normalized);
      } catch (ParseException e) {
        // try the next format
      }
    }
    return null;
  }

  private static List<DateFormat> gsonFormats(Class<?> type) {
    ImmutableList.Builder<DateFormat> formats = ImmutableList.builder();
    if (type == java.sql.Date.class) {
      formats.add(
          new SimpleDateFormat("MMM d, yyyy"), new SimpleDateFormat("MMM d, yyyy", Locale.US));
    } else if (type == java.sql.Time.class) {
      formats.add(
          new SimpleDateFormat("hh:mm:ss a"), new SimpleDateFormat("hh:mm:ss a", Locale.US));
    } else {
      formats.add(
          DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.US));
      if (!Locale.getDefault().equals(Locale.US)) {
        formats.add(DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT));
      }
      // JDK 8 (COMPAT) and JDK 9+ (CLDR) US patterns, whichever JDK wrote it.
      formats.add(new SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US));
      formats.add(new SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.US));
    }
    List<DateFormat> result = formats.build();
    for (DateFormat format : result) {
      format.setTimeZone(TimeZone.getDefault());
    }
    return result;
  }

  private static Object toType(Class<?> type, Date date) {
    if (type == java.sql.Timestamp.class) {
      return new java.sql.Timestamp(date.getTime());
    } else if (type == java.sql.Date.class) {
      return new java.sql.Date(date.getTime());
    } else if (type == java.sql.Time.class) {
      return new java.sql.Time(date.getTime());
    }
    return date;
  }

  // ---------- Reading the Optional of JsonDataConverter, {"value":..} ----------

  private static final class GsonOptionalDeserializer extends DelegatingDeserializer {
    private final ReferenceType type;

    GsonOptionalDeserializer(ReferenceType type, JsonDeserializer<?> delegate) {
      super(delegate);
      this.type = type;
    }

    @Override
    protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
      return new GsonOptionalDeserializer(type, delegate);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (!p.hasToken(JsonToken.START_OBJECT)) {
        return _delegatee.deserialize(p, ctxt);
      }
      JsonNode node = ctxt.readTree(p);
      JsonNode value = node.get("value");
      if (node.size() == 1 && value != null && isUnambiguous(type.getContentType(), ctxt)) {
        if (value.isNull()) {
          return Optional.empty();
        }
        return Optional.ofNullable(ctxt.readTreeAsValue(value, type.getContentType()));
      }
      return deserializeTree(_delegatee, node, p, ctxt);
    }
  }

  /**
   * Whether {"value":x} can only be Gson's form of an Optional of this content type: its Jackson
   * form is never a JSON object with the single key "value". False for maps and untyped values (a
   * map with the single key "value" is written the same way) and for beans with a "value" property.
   */
  static boolean isUnambiguous(JavaType content, DeserializationContext ctxt) {
    Class<?> raw = content.getRawClass();
    if (raw == Object.class
        || content.isMapLikeType()
        || content.isReferenceType()
        || JsonNode.class.isAssignableFrom(raw)
        // Such as Serializable, read as an untyped value, or Map.Entry, written as an object.
        || (raw.isInterface() && !content.isContainerType())) {
      return false;
    }
    if (content.isContainerType()
        || content.isEnumType()
        || content.isPrimitive()
        || raw.getName().startsWith("java.")) {
      return true;
    }
    for (BeanPropertyDefinition property : ctxt.getConfig().introspect(content).findProperties()) {
      if (property.getName().equals("value")) {
        return false;
      }
    }
    return true;
  }

  // ---------- Reading untyped numbers as Double, opt-in ----------

  static Module untypedNumbersModule() {
    SimpleModule module =
        new SimpleModule("com.uber.cadence.converter.JacksonDataConverter.GsonUntypedNumbers");
    module.addDeserializer(Object.class, new GsonUntypedDeserializer());
    return module;
  }

  /** Like Gson's ObjectTypeAdapter: numbers are Double, objects insertion-ordered maps. */
  private static final class GsonUntypedDeserializer extends StdDeserializer<Object> {
    GsonUntypedDeserializer() {
      super(Object.class);
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      return toGsonValue(ctxt.readTree(p));
    }

    private static Object toGsonValue(JsonNode node) {
      switch (node.getNodeType()) {
        case OBJECT:
          Map<String, Object> map = new LinkedHashMap<>();
          for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            map.put(field.getKey(), toGsonValue(field.getValue()));
          }
          return map;
        case ARRAY:
          List<Object> list = new ArrayList<>(node.size());
          for (JsonNode element : node) {
            list.add(toGsonValue(element));
          }
          return list;
        case NUMBER:
          return node.doubleValue();
        case STRING:
          return node.textValue();
        case BOOLEAN:
          return node.booleanValue();
        default:
          return null;
      }
    }
  }

  // ---------- Writing client payloads that JsonDataConverter can read ----------

  /**
   * Writes the Duration fields of the client payloads that have some in the form of
   * JsonDataConverter, so that a worker using it can replay them, for example during a rollback or
   * in a fleet with both converters. It takes precedence over any Duration serializer of the
   * application.
   */
  static final class InternalPayloadWriteModifier extends BeanSerializerModifier {
    @Override
    public List<BeanPropertyWriter> changeProperties(
        SerializationConfig config, BeanDescription beanDesc, List<BeanPropertyWriter> properties) {
      if (ClientPayloads.hasGsonShapedDurations(beanDesc.getBeanClass())) {
        for (BeanPropertyWriter property : properties) {
          if (property.getType().hasRawClass(Duration.class)) {
            property.assignSerializer(GSON_DURATION);
          }
        }
      }
      return properties;
    }
  }

  /**
   * Reads the Duration fields of these client payloads in both forms, also when the application
   * registered a Duration deserializer of its own, which cannot read the form they are written in.
   */
  static final class InternalPayloadReadModifier extends BeanDeserializerModifier {
    @Override
    public BeanDeserializerBuilder updateBuilder(
        DeserializationConfig config, BeanDescription beanDesc, BeanDeserializerBuilder builder) {
      if (ClientPayloads.hasGsonShapedDurations(beanDesc.getBeanClass())) {
        List<SettableBeanProperty> durations = new ArrayList<>();
        for (Iterator<SettableBeanProperty> it = builder.getProperties(); it.hasNext(); ) {
          SettableBeanProperty property = it.next();
          if (property.getType().hasRawClass(Duration.class)) {
            durations.add(property);
          }
        }
        for (SettableBeanProperty property : durations) {
          builder.addOrReplaceProperty(
              property.withValueDeserializer(
                  new JacksonDataConverter.LenientDurationDeserializer()),
              true);
        }
      }
      return builder;
    }
  }

  @SuppressWarnings("unchecked")
  private static final JsonSerializer<Object> GSON_DURATION =
      (JsonSerializer<Object>) (JsonSerializer<?>) new GsonDurationSerializer();

  /** {"seconds":..,"nanos":..}, the reflective form JsonDataConverter reads and writes. */
  private static final class GsonDurationSerializer extends StdSerializer<Duration> {
    GsonDurationSerializer() {
      super(Duration.class);
    }

    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartObject();
      gen.writeNumberField("seconds", value.getSeconds());
      gen.writeNumberField("nanos", value.getNano());
      gen.writeEndObject();
    }
  }
}
