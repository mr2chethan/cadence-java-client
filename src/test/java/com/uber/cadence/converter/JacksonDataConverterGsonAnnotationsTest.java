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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/** JacksonDataConverter applies Gson's annotations like JsonDataConverter. */
public class JacksonDataConverterGsonAnnotationsTest {

  private static final DataConverter GSON = JsonDataConverter.getInstance();
  private static final DataConverter JACKSON = JacksonDataConverter.getInstance();

  // ---------- Field names ----------

  public static class Party {
    @SerializedName("party_id")
    String partyId;
  }

  public static class Line {
    @SerializedName("sku_code")
    String sku;

    int units;
  }

  public static class Order extends Party {
    @SerializedName("order_id")
    String orderId;

    @SerializedName(
      value = "qty",
      alternate = {"quantity", "count"}
    )
    int quantity;

    String note;
    Line line;
    List<Line> lines;

    @SerializedName("tr")
    transient String transientNote;

    @SerializedName("st")
    static String staticNote = "static";
  }

  /** Has no constructor that Jackson can use. */
  public static final class Item {
    @SerializedName("item_id")
    final String id;

    @SerializedName(value = "n", alternate = "count")
    final int count;

    Item(String id, int count) {
      this.id = id;
      this.count = count;
    }
  }

  private static Line line(String sku, int units) {
    Line line = new Line();
    line.sku = sku;
    line.units = units;
    return line;
  }

  private static Order newOrder() {
    Order order = new Order();
    order.partyId = "p1";
    order.orderId = "o1";
    order.quantity = 3;
    order.note = "n";
    order.line = line("s1", 1);
    order.lines = Collections.singletonList(line("s2", 2));
    return order;
  }

  private static void assertOrder(Order order) {
    assertEquals("p1", order.partyId);
    assertEquals("o1", order.orderId);
    assertEquals(3, order.quantity);
    assertEquals("n", order.note);
    assertEquals("s1", order.line.sku);
    assertEquals(1, order.line.units);
    assertEquals(1, order.lines.size());
    assertEquals("s2", order.lines.get(0).sku);
    assertEquals(2, order.lines.get(0).units);
  }

  @Test
  public void serializedNameFieldsBothDirections() {
    Order order = newOrder();
    byte[] jackson = JACKSON.toData(order);
    byte[] gson = GSON.toData(order);
    JsonElement expected =
        tree(
            "{\"party_id\":\"p1\",\"order_id\":\"o1\",\"qty\":3,\"note\":\"n\","
                + "\"line\":{\"sku_code\":\"s1\",\"units\":1},"
                + "\"lines\":[{\"sku_code\":\"s2\",\"units\":2}]}");
    assertEquals(expected, tree(gson));
    assertEquals(expected, tree(jackson));

    assertOrder(JACKSON.fromData(gson, Order.class, Order.class));
    assertOrder(GSON.fromData(jackson, Order.class, Order.class));
    assertOrder(JACKSON.fromData(jackson, Order.class, Order.class));
  }

  @Test
  public void serializedNameAlternatesAreRead() {
    for (DataConverter converter : Arrays.asList(GSON, JACKSON)) {
      assertEquals(4, read(converter, "{\"qty\":4}", Order.class).quantity);
      assertEquals(5, read(converter, "{\"quantity\":5}", Order.class).quantity);
      assertEquals(6, read(converter, "{\"count\":6}", Order.class).quantity);
      assertEquals(7, read(converter, "{\"n\":7}", Item.class).count);
      assertEquals(8, read(converter, "{\"count\":8}", Item.class).count);
    }
  }

  @Test
  public void staticAndTransientFieldsAreIgnoredLikeGson() {
    Order order = newOrder();
    order.transientNote = "not written";
    for (DataConverter converter : Arrays.asList(GSON, JACKSON)) {
      JsonObject json = tree(converter.toData(order)).getAsJsonObject();
      for (String key : Arrays.asList("tr", "transientNote", "st", "staticNote")) {
        assertFalse(key, json.has(key));
      }
      Order read =
          read(
              converter,
              "{\"order_id\":\"o\",\"tr\":\"a\",\"transientNote\":\"b\",\"st\":\"c\"}",
              Order.class);
      assertEquals("o", read.orderId);
      assertNull(read.transientNote);
      assertEquals("static", Order.staticNote);
    }
  }

  @Test
  public void classWithoutUsableConstructorUsesSerializedName() {
    Item item = new Item("i1", 7);
    byte[] jackson = JACKSON.toData(item);
    assertEquals("{\"item_id\":\"i1\",\"n\":7}", text(jackson));
    assertEquals(text(GSON.toData(item)), text(jackson));

    Item fromGson = JACKSON.fromData(GSON.toData(item), Item.class, Item.class);
    assertEquals("i1", fromGson.id);
    assertEquals(7, fromGson.count);
    Item fromJackson = GSON.fromData(jackson, Item.class, Item.class);
    assertEquals("i1", fromJackson.id);
    assertEquals(7, fromJackson.count);
  }

  // ---------- Jackson annotations next to Gson's ----------

  public static class Precedence {
    @SerializedName("a")
    @JsonProperty("b")
    String renamedByBoth;

    @SerializedName(value = "c", alternate = "c2")
    @JsonProperty("d")
    @JsonAlias("e")
    String aliased;

    @SerializedName("f")
    @JsonProperty
    String unnamedJsonProperty;

    @SerializedName("g")
    @JsonProperty("g")
    String sameName;
  }

  @Test
  public void jsonPropertyTakesPrecedence() {
    Precedence value = new Precedence();
    value.renamedByBoth = "1";
    value.aliased = "2";
    value.unnamedJsonProperty = "3";
    value.sameName = "4";

    assertEquals(
        tree("{\"b\":\"1\",\"d\":\"2\",\"f\":\"3\",\"g\":\"4\"}"), tree(JACKSON.toData(value)));
    byte[] gson = GSON.toData(value);
    assertEquals(tree("{\"a\":\"1\",\"c\":\"2\",\"f\":\"3\",\"g\":\"4\"}"), tree(gson));

    Precedence fromGson = JACKSON.fromData(gson, Precedence.class, Precedence.class);
    assertEquals("1", fromGson.renamedByBoth);
    assertEquals("2", fromGson.aliased);
    assertEquals("3", fromGson.unnamedJsonProperty);
    assertEquals("4", fromGson.sameName);

    for (String key : Arrays.asList("a", "b")) {
      assertEquals(
          key, read(JACKSON, "{\"" + key + "\":\"" + key + "\"}", Precedence.class).renamedByBoth);
    }
    for (String key : Arrays.asList("c", "c2", "d", "e")) {
      assertEquals(
          key, read(JACKSON, "{\"" + key + "\":\"" + key + "\"}", Precedence.class).aliased);
    }
  }

  public static class MixedIn {
    @SerializedName("gson")
    String value;
  }

  abstract static class MixedInMixin {
    @JsonProperty("mixed")
    String value;
  }

  /** Also the @JsonProperty name of a mix-in. */
  @Test
  public void mixInJsonPropertyTakesPrecedence() {
    DataConverter converter =
        new JacksonDataConverter(mapper -> mapper.addMixIn(MixedIn.class, MixedInMixin.class));
    MixedIn value = new MixedIn();
    value.value = "v";
    assertEquals("{\"mixed\":\"v\"}", text(converter.toData(value)));
    assertEquals("v", read(converter, "{\"mixed\":\"v\"}", MixedIn.class).value);
    // What JsonDataConverter wrote is still read.
    assertEquals("v", converter.fromData(GSON.toData(value), MixedIn.class, MixedIn.class).value);
  }

  // ---------- Enum constants ----------

  public enum Color {
    @SerializedName("red")
    RED,
    @SerializedName(
      value = "grn",
      alternate = {"green", "verde"}
    )
    GREEN,
    BLUE,
    @SerializedName("gson_violet")
    @JsonProperty("jackson_violet")
    VIOLET,
    @SerializedName("gold")
    @JsonAlias("golden")
    GOLD
  }

  /** JsonDataConverter writes the keys of maps with toString(). */
  public enum Priority {
    @SerializedName("lo")
    LOW,
    HIGH;

    @Override
    public String toString() {
      return "priority-" + name().toLowerCase(Locale.ROOT);
    }
  }

  public enum Swapped {
    @SerializedName("B")
    A,
    @SerializedName("A")
    B
  }

  public enum Unprintable {
    @SerializedName("one")
    ONE;

    @Override
    public String toString() {
      throw new UnsupportedOperationException("no text");
    }
  }

  public static class Palette {
    Color primary;
    List<Color> colors;
    EnumSet<Color> set;
    Map<Color, String> byColor;
    Map<Priority, Integer> byPriority;
  }

  private static Palette newPalette() {
    Palette palette = new Palette();
    palette.primary = Color.GREEN;
    palette.colors = Arrays.asList(Color.RED, Color.GREEN, Color.BLUE, Color.GOLD);
    palette.set = EnumSet.of(Color.RED, Color.GOLD);
    palette.byColor = new LinkedHashMap<>();
    palette.byColor.put(Color.RED, "r");
    palette.byColor.put(Color.BLUE, "b");
    palette.byPriority = new LinkedHashMap<>();
    palette.byPriority.put(Priority.LOW, 1);
    palette.byPriority.put(Priority.HIGH, 2);
    return palette;
  }

  private static void assertPalette(Palette expected, Palette actual) {
    assertEquals(expected.primary, actual.primary);
    assertEquals(expected.colors, actual.colors);
    assertEquals(expected.set, actual.set);
    assertEquals(expected.byColor, actual.byColor);
    assertEquals(expected.byPriority, actual.byPriority);
  }

  @Test
  public void enumConstantsBothDirections() {
    Palette palette = newPalette();
    byte[] jackson = JACKSON.toData(palette);
    assertEquals(
        tree(
            "{\"primary\":\"grn\",\"colors\":[\"red\",\"grn\",\"BLUE\",\"gold\"],"
                + "\"set\":[\"red\",\"gold\"],\"byColor\":{\"red\":\"r\",\"BLUE\":\"b\"},"
                + "\"byPriority\":{\"lo\":1,\"HIGH\":2}}"),
        tree(jackson));
    byte[] gson = GSON.toData(palette);
    assertEquals(
        tree(
            "{\"primary\":\"grn\",\"colors\":[\"red\",\"grn\",\"BLUE\",\"gold\"],"
                + "\"set\":[\"red\",\"gold\"],\"byColor\":{\"RED\":\"r\",\"BLUE\":\"b\"},"
                + "\"byPriority\":{\"priority-low\":1,\"priority-high\":2}}"),
        tree(gson));

    assertPalette(palette, JACKSON.fromData(gson, Palette.class, Palette.class));
    assertPalette(palette, GSON.fromData(jackson, Palette.class, Palette.class));
    assertPalette(palette, JACKSON.fromData(jackson, Palette.class, Palette.class));
  }

  @Test
  public void enumNamesAndAliasesAreRead() {
    String[][] cases = {
      {"red", "RED"},
      {"RED", "RED"},
      {"grn", "GREEN"},
      {"green", "GREEN"},
      {"verde", "GREEN"},
      {"GREEN", "GREEN"},
      {"BLUE", "BLUE"},
      {"gson_violet", "VIOLET"},
      {"jackson_violet", "VIOLET"},
      {"VIOLET", "VIOLET"},
      {"gold", "GOLD"},
      {"golden", "GOLD"},
      {"GOLD", "GOLD"}
    };
    for (String[] c : cases) {
      assertEquals(c[0], Color.valueOf(c[1]), read(JACKSON, "\"" + c[0] + "\"", Color.class));
    }

    // The name of a Jackson @JsonProperty is written.
    assertEquals("\"jackson_violet\"", text(JACKSON.toData(Color.VIOLET)));
    assertEquals(
        Color.VIOLET, JACKSON.fromData(GSON.toData(Color.VIOLET), Color.class, Color.class));

    // Names are matched before aliases, so swapped names are read as JsonDataConverter reads them.
    List<Swapped> swapped = Arrays.asList(Swapped.A, Swapped.B);
    assertEquals("[\"B\",\"A\"]", text(JACKSON.toData(swapped)));
    assertEquals(text(GSON.toData(swapped)), text(JACKSON.toData(swapped)));
    for (DataConverter converter : Arrays.asList(GSON, JACKSON)) {
      assertArrayEquals(
          new Swapped[] {Swapped.B, Swapped.A},
          converter.fromData(utf8("[\"A\",\"B\"]"), Swapped[].class, Swapped[].class));
    }
  }

  @Test
  public void enumWithFailingToStringIsRead() {
    assertSame(Unprintable.ONE, read(JACKSON, "\"one\"", Unprintable.class));
    assertSame(Unprintable.ONE, read(JACKSON, "\"ONE\"", Unprintable.class));
  }

  /** Jackson 2.15 and earlier call the variants that take the class of the enum. */
  @Test
  public void enumApiOfEarlierJacksonVersions() {
    GsonAnnotationIntrospector introspector = new GsonAnnotationIntrospector();
    assertEquals(Version.unknownVersion(), introspector.version());

    Color[] values = Color.values();
    String[] names = introspector.findEnumValues(Color.class, values, new String[values.length]);
    assertArrayEquals(new String[] {"red", "grn", null, null, "gold"}, names);

    String[][] aliases = new String[values.length][];
    aliases[Color.GOLD.ordinal()] = new String[] {"golden"};
    introspector.findEnumAliases(Color.class, values, aliases);
    assertArrayEquals(new String[] {"RED"}, aliases[Color.RED.ordinal()]);
    assertArrayEquals(new String[] {"green", "verde", "GREEN"}, aliases[Color.GREEN.ordinal()]);
    assertArrayEquals(new String[] {"BLUE"}, aliases[Color.BLUE.ordinal()]);
    assertArrayEquals(new String[] {"gson_violet", "VIOLET"}, aliases[Color.VIOLET.ordinal()]);
    assertArrayEquals(new String[] {"golden", "GOLD"}, aliases[Color.GOLD.ordinal()]);

    String[][] unprintable = new String[1][];
    introspector.findEnumAliases(Unprintable.class, Unprintable.values(), unprintable);
    assertArrayEquals(new String[] {"ONE"}, unprintable[0]);
  }

  // ---------- Fields of exceptions ----------

  public static class RenamedFieldsException extends RuntimeException {
    @SerializedName(
      value = "error_code",
      alternate = {"code", "errorCode"}
    )
    int errorCode;

    // Its Java name is a key of the JSON of exceptions, its JSON name is not.
    @SerializedName("trace_id")
    String stackTrace;

    RenamedFieldsException(String message, int errorCode, String stackTrace) {
      super(message);
      this.errorCode = errorCode;
      this.stackTrace = stackTrace;
    }
  }

  public static class ReservedNameException extends RuntimeException {
    @SerializedName("cause")
    String reason;

    @SerializedName("detailMessage")
    String text;

    ReservedNameException(String message, Throwable cause, String reason, String text) {
      super(message, cause);
      this.reason = reason;
      this.text = text;
    }
  }

  private static void assertRenamedFields(Throwable result, int errorCode) {
    assertEquals(RenamedFieldsException.class, result.getClass());
    assertEquals("failed", result.getMessage());
    RenamedFieldsException exception = (RenamedFieldsException) result;
    assertEquals(errorCode, exception.errorCode);
    assertEquals("t-1", exception.stackTrace);
  }

  @Test
  public void exceptionFieldsHonourSerializedName() {
    RenamedFieldsException original = new RenamedFieldsException("failed", 42, "t-1");
    byte[] jackson = JACKSON.toData(original);
    byte[] gson = GSON.toData(original);
    JsonObject json = tree(jackson).getAsJsonObject();
    assertEquals(42, json.get("error_code").getAsInt());
    assertEquals("t-1", json.get("trace_id").getAsString());
    assertFalse(json.has("errorCode"));
    assertTrue(
        json.get("stackTrace").getAsString().contains("exceptionFieldsHonourSerializedName"));
    JsonObject gsonJson = tree(gson).getAsJsonObject();
    assertEquals(gsonJson.get("error_code"), json.get("error_code"));
    assertEquals(gsonJson.get("trace_id"), json.get("trace_id"));

    assertRenamedFields(JACKSON.fromData(gson, Throwable.class, Throwable.class), 42);
    assertRenamedFields(GSON.fromData(jackson, Throwable.class, Throwable.class), 42);
    assertRenamedFields(JACKSON.fromData(jackson, Throwable.class, Throwable.class), 42);

    for (String alternate : Arrays.asList("code", "errorCode")) {
      JsonObject renamed = tree(jackson).getAsJsonObject();
      renamed.remove("error_code");
      renamed.addProperty(alternate, 7);
      byte[] data = utf8(renamed.toString());
      assertRenamedFields(JACKSON.fromData(data, Throwable.class, Throwable.class), 7);
      assertRenamedFields(GSON.fromData(data, Throwable.class, Throwable.class), 7);
    }

    // Under none of its names: the field keeps its default value.
    JsonObject missing = tree(jackson).getAsJsonObject();
    missing.remove("error_code");
    byte[] data = utf8(missing.toString());
    assertRenamedFields(JACKSON.fromData(data, Throwable.class, Throwable.class), 0);
    assertRenamedFields(GSON.fromData(data, Throwable.class, Throwable.class), 0);
  }

  @Test
  public void exceptionFieldsWithReservedJsonNamesAreSkipped() {
    ReservedNameException original =
        new ReservedNameException(
            "failed", new IllegalStateException("real cause"), "a reason", "a text");
    byte[] data = JACKSON.toData(original);
    JsonObject json = tree(data).getAsJsonObject();
    assertEquals("failed", json.get("detailMessage").getAsString());
    assertEquals(
        IllegalStateException.class.getName(),
        json.get("cause").getAsJsonObject().get("class").getAsString());

    Throwable result = JACKSON.fromData(data, Throwable.class, Throwable.class);
    assertEquals(ReservedNameException.class, result.getClass());
    assertEquals("failed", result.getMessage());
    assertEquals(IllegalStateException.class, result.getCause().getClass());
    assertEquals("real cause", result.getCause().getMessage());
    assertNull(((ReservedNameException) result).reason);
    assertNull(((ReservedNameException) result).text);
  }

  // ---------- A customized annotation introspector ----------

  public static class IntrospectorFixture {
    @SerializedName(value = "renamed_value", alternate = "old_value")
    String value;

    Color color;
  }

  private static IntrospectorFixture newIntrospectorFixture() {
    IntrospectorFixture value = new IntrospectorFixture();
    value.value = "v";
    value.color = Color.RED;
    return value;
  }

  @Test
  public void replacedAnnotationIntrospectorIsWarned() {
    DataConverter converter;
    try (LogCapture log = new LogCapture(JacksonDataConverter.class.getName())) {
      converter =
          new JacksonDataConverter(
              mapper -> mapper.setAnnotationIntrospector(new JacksonAnnotationIntrospector()));
      List<String> warnings = log.messages(Level.WARN, "AnnotationIntrospectorPair");
      assertEquals(warnings.toString(), 1, warnings.size());
      assertTrue(warnings.get(0).contains("@SerializedName"));
    }

    // Gson's names are no longer applied.
    IntrospectorFixture value = newIntrospectorFixture();
    assertEquals(tree("{\"value\":\"v\",\"color\":\"RED\"}"), tree(converter.toData(value)));
    assertNull(read(converter, "{\"renamed_value\":\"v\"}", IntrospectorFixture.class).value);
    assertThrows(DataConverterException.class, () -> read(converter, "\"red\"", Color.class));
  }

  @Test
  public void pairedIntrospectorKeepsSerializedName() {
    DataConverter converter;
    try (LogCapture log = new LogCapture(JacksonDataConverter.class.getName())) {
      converter =
          new JacksonDataConverter(
              mapper ->
                  mapper.setAnnotationIntrospector(
                      AnnotationIntrospectorPair.pair(
                          mapper.getSerializationConfig().getAnnotationIntrospector(),
                          new JacksonAnnotationIntrospector())));
      assertEquals(Collections.emptyList(), log.messages(Level.WARN, "AnnotationIntrospectorPair"));
    }

    IntrospectorFixture value = newIntrospectorFixture();
    byte[] data = converter.toData(value);
    assertEquals(tree("{\"renamed_value\":\"v\",\"color\":\"red\"}"), tree(data));
    IntrospectorFixture fromGson =
        converter.fromData(
            GSON.toData(value), IntrospectorFixture.class, IntrospectorFixture.class);
    assertEquals("v", fromGson.value);
    assertEquals(Color.RED, fromGson.color);
    assertEquals("o", read(converter, "{\"old_value\":\"o\"}", IntrospectorFixture.class).value);
    // The enum aliases are kept as well.
    for (String alias : new String[] {"\"grn\"", "\"green\"", "\"verde\"", "\"GREEN\""}) {
      assertEquals(alias, Color.GREEN, read(converter, alias, Color.class));
    }
    assertEquals(Color.VIOLET, read(converter, "\"gson_violet\"", Color.class));
  }

  // ---------- Helpers ----------

  private static byte[] utf8(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] data) {
    return new String(data, StandardCharsets.UTF_8);
  }

  private static JsonElement tree(String json) {
    return JsonParser.parseString(json);
  }

  private static JsonElement tree(byte[] data) {
    return tree(text(data));
  }

  private static <T> T read(DataConverter converter, String json, Class<T> type) {
    return converter.fromData(utf8(json), type, type);
  }

  /** Collects what a logger logs while it is open. */
  private static final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    LogCapture(String name) {
      logger = (Logger) LoggerFactory.getLogger(name);
      previousLevel = logger.getLevel();
      logger.setLevel(Level.INFO);
      appender.start();
      logger.addAppender(appender);
    }

    /** The messages of this logger with the given level that contain the text. */
    List<String> messages(Level level, String text) {
      List<ILoggingEvent> events;
      synchronized (appender) {
        events = new ArrayList<>(appender.list);
      }
      List<String> result = new ArrayList<>();
      for (ILoggingEvent event : events) {
        String message = event.getFormattedMessage();
        if (logger.getName().equals(event.getLoggerName())
            && level.equals(event.getLevel())
            && message.contains(text)) {
          result.add(message);
        }
      }
      return result;
    }

    @Override
    public void close() {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(previousLevel);
    }
  }
}
