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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.ValueInstantiators;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.DurationDeserializer;
import com.google.common.base.Defaults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.uber.cadence.client.ApplicationFailureException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements conversion through Jackson JSON processor. To extend use {@link
 * #JacksonDataConverter(Function)} constructor.
 *
 * <p>Advantages over {@link JsonDataConverter} (Gson-based):
 *
 * <ul>
 *   <li>Native support for Java 8 date/time types (LocalDate, LocalDateTime, Instant, etc.)
 *   <li>Better polymorphism support via Jackson's {@code @JsonTypeInfo} annotations
 *   <li>Generally better performance for larger payloads
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * WorkflowClient client = WorkflowClient.newInstance(
 *     service,
 *     WorkflowClientOptions.newBuilder()
 *         .setDomain("my-domain")
 *         .setDataConverter(JacksonDataConverter.getInstance())
 *         .build());
 * }</pre>
 *
 * <p>Behavior to be aware of:
 *
 * <ul>
 *   <li>A class that Jackson cannot instantiate, because it has no no-arg constructor, {@code
 *       JsonCreator} or {@code ConstructorProperties} (for example an immutable class with only an
 *       all-args constructor, a Lombok {@code @Value} class or a non-static inner class), is
 *       instantiated without running its constructors and field initializers, and its fields are
 *       set from the JSON; the enclosing instance of a non-static inner class is null. This is what
 *       {@link JsonDataConverter} does too. JDK classes and their subclasses, collections, maps,
 *       abstract types and throwables are excluded.
 *   <li>Exceptions are restored as their exact class with their exact message, stack trace, cause,
 *       suppressed exceptions and fields. Like {@link JsonDataConverter}, the no-arg constructor of
 *       the exception class is used when it has one. Otherwise, as with Java serialization, no
 *       constructor of the exception class runs. A JDK exception whose fields are not accessible to
 *       this code (JDK 16 and later without --add-opens for its package) is created with its
 *       (String) constructor, or else as an ApplicationFailureException, with its message.
 *   <li>An empty or whitespace-only payload is decoded as null (the first argument null and the
 *       remaining ones their default value), like {@link JsonDataConverter}.
 *   <li>An abstract {@link Set} is decoded as an insertion-ordered {@link LinkedHashSet}.
 *   <li>{@link Optional} values are written as the contained value, or null when empty.
 *   <li>Numbers in untyped values (Object, the values of a {@code Map<String, Object>}, the
 *       elements of a {@code List<Object>}) are decoded as Integer, Long, BigInteger or Double,
 *       where {@link JsonDataConverter} always gives Double. Register {@link
 *       #gsonCompatibleNumbersModule()} for Double.
 *   <li>Jackson annotations on the classes of payloads apply, for example {@code @JsonIgnore},
 *       {@code @JsonProperty} or {@code @JsonTypeInfo}.
 * </ul>
 *
 * <p>Gson annotations and types: like {@link JsonDataConverter}, it applies Gson's {@code
 * SerializedName} (the name and alternate names of fields, including fields of exceptions, and of
 * enum constants), and it writes and reads Gson's JsonElement, JsonObject, JsonArray and
 * JsonPrimitive as the JSON they represent. A non-empty name of a Jackson {@code JsonProperty}
 * takes precedence over the name of {@code SerializedName}, which is then still read. Gson's {@code
 * JsonAdapter} is not supported.
 *
 * <p>Migrating from {@link JsonDataConverter}:
 *
 * <ul>
 *   <li>Configure the same converter on all clients and workers of a domain.
 *   <li>Workflows that are open when a deployment switches converters are replayed with the new
 *       converter. Switch only for new domains or task lists, after the open workflows complete, or
 *       for new code paths behind {@code Workflow.getVersion}, unless you verified the replay of
 *       your open histories with this converter.
 *   <li>This converter reads what {@link JsonDataConverter} writes for java.time values (the form
 *       Gson writes on JDK 15 and earlier), java.util.Date, java.sql.Date, java.sql.Time and
 *       java.sql.Timestamp strings, Calendar, Duration, Optional (except when its content is a map,
 *       an interface, an untyped value or a class with a "value" property: such an Optional is read
 *       in the form this converter writes, so Gson's {@code {"value":x}} is then read as a map or
 *       fails), OptionalInt, OptionalLong, OptionalDouble and byte arrays. It does not read what
 *       Gson could not read either, such as ZonedDateTime. A deserializer of your own that you
 *       register for one of these types replaces this. Registering Jackson's own JavaTimeModule or
 *       Jdk8Module does not.
 *   <li>Code that casts untyped numbers to Double needs {@link #gsonCompatibleNumbersModule()}.
 *   <li>{@link JsonDataConverter} reads the data that the client records for itself with this
 *       converter, such as the headers of markers and the retry options of {@code Workflow.retry},
 *       on JDK 15 and earlier or with {@code --add-opens java.base/java.time}. It does not read
 *       this converter's java.time values, Optionals, byte arrays and Durations of your payloads,
 *       nor of the fields of exceptions (for example the backoff of an ActivityFailureException,
 *       the details of an ActivityTimeoutException and the workflow type of a
 *       WorkflowFailureException), so switching back to {@link JsonDataConverter} is not supported
 *       for open workflows whose payloads contain them.
 *   <li>Memos and search attributes are always written and read with {@link JsonDataConverter}.
 *   <li>Changing the configuration of the ObjectMapper, for example the naming of properties, on a
 *       deployment with open workflows is like switching converters.
 * </ul>
 *
 * <p>Default typing ({@code ObjectMapper.activateDefaultTyping}) is supported, also for the types
 * that this converter handles itself, such as exceptions, DataConverter fields and fields declared
 * as a Gson JsonElement type, and for several values, such as the arguments of a workflow method.
 * Enabling it on a domain with open workflows makes the untyped JSON they recorded unreadable for
 * non-final types.
 */
public final class JacksonDataConverter implements DataConverter {

  private static final Logger log = LoggerFactory.getLogger(JacksonDataConverter.class);

  private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
  private static final String TYPE_FIELD_NAME = "type";
  private static final String JSON_CONVERTER_TYPE = "JSON";
  private static final String CLASS_NAME_FIELD_NAME = "className";

  /** Used to parse a stack trace line. */
  private static final String TRACE_ELEMENT_REGEXP =
      "((?<className>.*)\\.((?<methodName>.*)))\\(((?<fileName>.*?)(:(?<lineNumber>\\d+))?)\\)";

  private static final Pattern TRACE_ELEMENT_PATTERN = Pattern.compile(TRACE_ELEMENT_REGEXP);

  /**
   * Stop emitting stack trace after this line. Makes serialized stack traces more readable and
   * compact as it omits most of framework level code.
   */
  private static final ImmutableSet<String> CUTOFF_METHOD_NAMES =
      ImmutableSet.of(
          "com.uber.cadence.internal.worker.POJOActivityImplementationFactory$POJOActivityImplementation.execute",
          "com.uber.cadence.internal.sync.POJODecisionTaskHandler$POJOWorkflowImplementation.execute");

  /** Keys of the JSON of an exception that fields of exception classes cannot use. */
  private static final ImmutableSet<String> RESERVED_THROWABLE_KEYS =
      ImmutableSet.of("detailMessage", "stackTrace", "cause", "suppressedExceptions", "class");

  /**
   * Whether an exception class can be restored as itself, which needs all the fields of its JSON to
   * be settable. That is not the case for JDK exceptions with fields in a package that is not open
   * to this code (JDK 16 and later without --add-opens for the package).
   */
  private static final ClassValue<Boolean> RESTORABLE =
      new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
          for (Class<?> c = type; c != Throwable.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
              if (isThrowableJsonField(field)) {
                try {
                  field.setAccessible(true);
                } catch (RuntimeException e) {
                  return false;
                }
              }
            }
          }
          return true;
        }
      };

  /**
   * Writes and reads the data that the client records for itself (see {@link ClientPayloads}), so
   * that no customization of the ObjectMapper can change or break it. Never exposed.
   */
  private static final ObjectMapper CLIENT_PAYLOAD_MAPPER = newDefaultObjectMapper();

  /** Holds the singleton, so that it is created after all static fields of this class. */
  private static final class InstanceHolder {
    static final DataConverter INSTANCE = new JacksonDataConverter();
  }

  private final ObjectMapper objectMapper;

  /**
   * Returns the singleton instance of this converter with a default-configured {@link
   * ObjectMapper}.
   */
  public static DataConverter getInstance() {
    return InstanceHolder.INSTANCE;
  }

  private JacksonDataConverter() {
    this(Function.identity());
  }

  /**
   * Constructs an instance with a customized {@link ObjectMapper}.
   *
   * <p>{@code mapperInterceptor} receives a new ObjectMapper that already has the configuration
   * this converter needs: its handling of exceptions, java.time and Optional values, Sets, Gson's
   * annotations and types, and classes without a usable constructor. The interceptor configures
   * that mapper, for example by registering modules or changing features, and returns it, or a
   * {@link ObjectMapper#copy() copy} of it. Modules it registers take precedence over the
   * configuration of this converter. It must not return another ObjectMapper, such as one shared by
   * the application: apply the settings of that mapper to the given one instead, for example by
   * registering the same modules. To keep the support of Gson's {@code SerializedName} when setting
   * an annotation introspector, add yours as the secondary one, which then applies where the
   * existing ones find nothing: {@code
   * mapper.setAnnotationIntrospector(AnnotationIntrospectorPair.pair(mapper.getSerializationConfig().getAnnotationIntrospector(),
   * yours))}.
   *
   * <p>The converter keeps its own copy of the returned mapper. Changes made to the mapper after
   * this constructor returns have no effect on the converter.
   *
   * <p>The customization applies to the values of workflows, activities, signals and queries and to
   * the fields of exceptions. It does not apply to the data the client records for itself, such as
   * the headers of version and local activity markers and the retry options of {@code
   * Workflow.retry}, which are always written and read with the default configuration. The
   * Durations of these retry options are always written as {@code {"seconds":..,"nanos":..}}, also
   * inside other values.
   *
   * <p>For example, to decode untyped numbers as Double like {@link JsonDataConverter}: {@code new
   * JacksonDataConverter(mapper -> mapper.registerModule(gsonCompatibleNumbersModule()))}.
   *
   * @param mapperInterceptor configures the given ObjectMapper and returns it, or a copy of it
   * @throws IllegalArgumentException if {@code mapperInterceptor} returns null or another
   *     ObjectMapper
   */
  public JacksonDataConverter(Function<ObjectMapper, ObjectMapper> mapperInterceptor) {
    ObjectMapper configured = mapperInterceptor.apply(newDefaultObjectMapper());
    if (configured == null || !configured.getRegisteredModuleIds().contains(CadenceModule.ID)) {
      throw new IllegalArgumentException(
          "mapperInterceptor must return the ObjectMapper it was given, or a copy() of it, after"
              + " configuring it. The returned ObjectMapper lacks the configuration of"
              + " JacksonDataConverter for exceptions, java.time and Optional values, Sets and"
              + " classes without a usable constructor. To use the settings of another"
              + " ObjectMapper, apply them to the given one, for example by registering the same"
              + " modules.");
    }
    // Changes made later through a reference kept by the interceptor do not affect this converter.
    this.objectMapper = configured.copy();
    if (!hasGsonAnnotationIntrospector(objectMapper)) {
      log.warn(
          "The ObjectMapper of this JacksonDataConverter no longer has the annotation introspector"
              + " that applies Gson's @SerializedName, so fields and enum constants renamed with it"
              + " are written and read under their Java names. To keep it, add your introspector"
              + " as the secondary one: AnnotationIntrospectorPair.pair(existing, yours).");
    }
  }

  private static boolean hasGsonAnnotationIntrospector(ObjectMapper mapper) {
    for (AnnotationIntrospector introspector :
        mapper.getDeserializationConfig().getAnnotationIntrospector().allIntrospectors()) {
      if (introspector instanceof GsonAnnotationIntrospector) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns a module that decodes the numbers of untyped values (Object, the values of a {@code
   * Map<String, Object>}, the elements of a {@code List<Object>}) as Double, like {@link
   * JsonDataConverter}, for code written for it. Register it through {@link
   * #JacksonDataConverter(Function)}.
   */
  public static Module gsonCompatibleNumbersModule() {
    return GsonCompatibility.untypedNumbersModule();
  }

  /** The mapper for a single value of the type: client payloads have their own. */
  private ObjectMapper mapperFor(Type type) {
    if (type == null) {
      return objectMapper;
    }
    Class<?> raw =
        type instanceof Class
            ? (Class<?>) type
            : objectMapper.getTypeFactory().constructType(type).getRawClass();
    return ClientPayloads.isClientPayload(raw) ? CLIENT_PAYLOAD_MAPPER : objectMapper;
  }

  private static ObjectMapper newDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();

    // Java 8 date/time types, and Optional, OptionalInt, OptionalLong and OptionalDouble. They are
    // registered under ids of their own, as Jackson ignores a module registered under an id it has
    // already seen: a JavaTimeModule or Jdk8Module registered by the mapper interceptor then still
    // applies.
    mapper.registerModule(new ModuleWithId(JavaTimeModule.class.getName(), new JavaTimeModule()));
    mapper.registerModule(new ModuleWithId(Jdk8Module.class.getName(), new Jdk8Module()));

    // Match Gson's behavior: serialize null fields
    mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);

    // Write dates as ISO strings, not timestamps
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // Keep the region zone id (e.g. [America/New_York]) so ZonedDateTime round-trips exactly
    mapper.enable(SerializationFeature.WRITE_DATES_WITH_ZONE_ID);

    // Preserve original timezone offset (e.g. +05:00) instead of normalizing to UTC
    mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    // Tolerate unknown properties during deserialization
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    // Use field visibility by default (similar to Gson)
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
    mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
    mapper.setVisibility(PropertyAccessor.SETTER, JsonAutoDetect.Visibility.NONE);

    // Registered after JavaTimeModule, so that its Duration deserializer takes precedence.
    mapper.registerModule(new CadenceModule(mapper));

    return mapper;
  }

  /**
   * When values is empty or it contains a single value and it is null then return empty blob. If a
   * single value do not wrap it into Json array. Exception stack traces are converted to a single
   * string stack trace to save space and make them more readable.
   */
  @Override
  public byte[] toData(Object... values) throws DataConverterException {
    if (values == null || values.length == 0) {
      return null;
    }
    try {
      if (values.length == 1) {
        // The registered ThrowableSerializer handles Throwables automatically,
        // so no special-casing needed here.
        Object value = values[0];
        String json = mapperFor(value == null ? null : value.getClass()).writeValueAsString(value);
        return json.getBytes(StandardCharsets.UTF_8);
      }
      // Each value is written on its own like a single value, so that with default typing the
      // array itself gets no type id: fromDataArray reads its elements by position.
      StringWriter json = new StringWriter();
      try (JsonGenerator generator = objectMapper.getFactory().createGenerator(json)) {
        generator.writeStartArray();
        for (Object value : values) {
          objectMapper.writeValue(generator, value);
        }
        generator.writeEndArray();
      }
      return json.toString().getBytes(StandardCharsets.UTF_8);
    } catch (DataConverterException e) {
      throw e;
    } catch (Throwable e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(byte[] content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    // An empty payload, for example the result of a void workflow, is null as in JsonDataConverter.
    if (content == null || isBlank(content)) {
      return null;
    }
    try {
      ObjectMapper mapper = mapperFor(valueType);
      JavaType javaType = mapper.getTypeFactory().constructType(valueType);
      return mapper.readValue(new String(content, StandardCharsets.UTF_8), javaType);
    } catch (Exception e) {
      throw new DataConverterException(content, new Type[] {valueType}, e);
    }
  }

  @Override
  public Object[] fromDataArray(byte[] content, Type... valueTypes) throws DataConverterException {
    try {
      if (content == null) {
        if (valueTypes.length == 0) {
          return EMPTY_OBJECT_ARRAY;
        }
        throw new DataConverterException(
            "Content doesn't match expected arguments", content, valueTypes);
      }
      if (isBlank(content)) {
        // Like JsonDataConverter, which reads an empty payload as a single JSON null: the first
        // argument is null and the remaining ones get their default values.
        Object[] result = new Object[valueTypes.length];
        for (int i = 1; i < valueTypes.length; i++) {
          result[i] = defaultValueOf(valueTypes[i]);
        }
        return result;
      }
      if (valueTypes.length == 1) {
        ObjectMapper mapper = mapperFor(valueTypes[0]);
        JavaType javaType = mapper.getTypeFactory().constructType(valueTypes[0]);
        Object result = mapper.readValue(new String(content, StandardCharsets.UTF_8), javaType);
        return new Object[] {result};
      }

      JsonNode rootNode = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
      ArrayNode array;
      if (rootNode.isArray()) {
        array = (ArrayNode) rootNode;
      } else {
        array = objectMapper.createArrayNode();
        array.add(rootNode);
      }

      Object[] result = new Object[valueTypes.length];
      for (int i = 0; i < valueTypes.length; i++) {
        if (i >= array.size()) { // Missing arguments => add defaults
          result[i] = defaultValueOf(valueTypes[i]);
        } else {
          JavaType javaType = objectMapper.getTypeFactory().constructType(valueTypes[i]);
          result[i] = objectMapper.treeToValue(array.get(i), javaType);
        }
      }
      return result;
    } catch (DataConverterException e) {
      throw e;
    } catch (Exception e) {
      throw new DataConverterException(content, valueTypes, e);
    }
  }

  /** True for a payload made only of JSON whitespace, including an empty one. */
  private static boolean isBlank(byte[] content) {
    for (byte b : content) {
      if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
        return false;
      }
    }
    return true;
  }

  private static Object defaultValueOf(Type type) {
    return type instanceof Class ? Defaults.defaultValue((Class<?>) type) : null;
  }

  // ---------- Throwable serialization (matches Gson CustomThrowableTypeAdapter behavior)
  // ----------

  /**
   * Converts a Throwable to a compact JSON node with "class", "stackTrace" (as string), and "cause"
   * fields. This is a static method so it can be used from both the instance methods and the
   * registered ThrowableSerializer.
   *
   * @param throwable the throwable to serialize
   * @param mapper the ObjectMapper to use for field serialization
   */
  static JsonNode throwableToJsonNode(Throwable throwable, ObjectMapper mapper) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    StackTraceElement[] trace = throwable.getStackTrace();
    for (StackTraceElement element : trace) {
      pw.println(element);
      String fullMethodName = element.getClassName() + "." + element.getMethodName();
      if (CUTOFF_METHOD_NAMES.contains(fullMethodName)) {
        break;
      }
    }

    // Separate cause from throwable for independent serialization
    Throwable cause = null;
    if (throwable.getCause() != null && throwable.getCause() != throwable) {
      try {
        cause = throwable.getCause();
        Field causeField = Throwable.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(throwable, null);
      } catch (Exception e) {
        log.warn("Failed to clear cause in original throwable.", e);
      }
    }

    ObjectNode object;
    try {
      // Temporarily disable the Throwable serializer to get raw field output.
      // We build the node manually so we need the plain bean fields.
      object = buildThrowableFieldsNode(throwable, mapper);
      object.put("class", throwable.getClass().getName());
      object.put("stackTrace", sw.toString());
    } catch (Throwable e) {
      DataConverterException ee =
          new DataConverterException("Failure serializing exception: " + throwable.toString(), e);
      if (cause != null) {
        ee.addSuppressed(cause);
        cause = null;
      }
      object = mapper.createObjectNode();
      JsonNode eeNode = throwableToJsonNode(ee, mapper);
      if (eeNode.isObject()) {
        object.setAll((ObjectNode) eeNode);
      }
    }

    if (cause != null) {
      try {
        object.set("cause", throwableToJsonNode(cause, mapper));
      } catch (Throwable e) {
        DataConverterException ee =
            new DataConverterException("Failure serializing exception: " + cause.toString(), e);
        ee.setStackTrace(cause.getStackTrace());
        object.set("cause", throwableToJsonNode(ee, mapper));
      }
    }

    return object;
  }

  /**
   * Builds a JSON object containing the throwable's fields (detailMessage, suppressedExceptions,
   * and any subclass-specific fields) without invoking the custom ThrowableSerializer.
   */
  private static ObjectNode buildThrowableFieldsNode(Throwable throwable, ObjectMapper mapper) {
    ObjectNode node = mapper.createObjectNode();
    // Always include the message. Set below, once it is known whether all fields were written.
    node.putNull("detailMessage");

    // Serialize subclass-specific fields via reflection. A field hidden by a field of the same
    // name in a subclass is skipped.
    Set<String> names = new HashSet<>();
    boolean allFieldsWritten = true;
    Class<?> clazz = throwable.getClass();
    while (clazz != null && clazz != Throwable.class && clazz != Object.class) {
      for (Field field : clazz.getDeclaredFields()) {
        if (!isThrowableJsonField(field) || !names.add(field.getName())) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(throwable);
          node.set(jsonNameOf(field), mapper.valueToTree(value));
        } catch (Exception e) {
          allFieldsWritten = false;
          log.warn("Failed to serialize field: " + field.getName(), e);
        }
      }
      clazz = clazz.getSuperclass();
    }
    node.put("detailMessage", messageOf(throwable, allFieldsWritten, !names.isEmpty()));

    // Suppressed exceptions
    Throwable[] suppressed = throwable.getSuppressed();
    if (suppressed != null && suppressed.length > 0) {
      ArrayNode suppressedArray = mapper.createArrayNode();
      for (Throwable s : suppressed) {
        suppressedArray.add(throwableToJsonNode(s, mapper));
      }
      node.set("suppressedExceptions", suppressedArray);
    } else {
      node.set("suppressedExceptions", mapper.createArrayNode());
    }

    return node;
  }

  /** Whether the field is written to, and read from, the JSON of an exception. */
  private static boolean isThrowableJsonField(Field field) {
    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers)
        && !Modifier.isTransient(modifiers)
        && !field.isSynthetic()
        && !RESERVED_THROWABLE_KEYS.contains(jsonNameOf(field));
  }

  /**
   * The key of a field in the JSON of an exception. Like in JsonDataConverter, whose
   * CustomThrowableTypeAdapter writes the fields with Gson, it is the name given by Gson's {@code
   * SerializedName}.
   */
  private static String jsonNameOf(Field field) {
    SerializedName name = field.getAnnotation(SerializedName.class);
    return name == null ? field.getName() : name.value();
  }

  /** The JSON of a field of an exception, under its name or one of its alternate names. */
  private static JsonNode fieldNode(ObjectNode object, Field field) {
    JsonNode node = object.get(jsonNameOf(field));
    SerializedName name = field.getAnnotation(SerializedName.class);
    if (node == null && name != null) {
      for (String alternate : name.alternate()) {
        node = object.get(alternate);
        if (node != null) {
          break;
        }
      }
    }
    return node;
  }

  /**
   * Returns the message to write. When all fields of the exception are written, that is the message
   * it was created with, as {@link Throwable#getMessage()} can be overridden to decorate it or to
   * compute it from these fields, and the exception is restored as its exact class with them.
   * Otherwise it is getMessage(), which is also the case for a null message and no fields, as the
   * message can be computed (like the helpful message of a NullPointerException) or come from
   * fields that could not be written, for example of a JDK exception whose package is not open to
   * this code.
   */
  private static String messageOf(
      Throwable throwable, boolean allFieldsWritten, boolean hasFields) {
    Field field = ThrowableFields.DETAIL_MESSAGE;
    if (allFieldsWritten && field != null) {
      try {
        String message = (String) field.get(throwable);
        if (message != null || hasFields) {
          return message;
        }
      } catch (IllegalAccessException | RuntimeException e) {
        // Use getMessage() below.
      }
    }
    return throwable.getMessage();
  }

  /**
   * Fields of Throwable, resolved on first use so that nothing is accessed reflectively unless
   * needed. Null when {@code java.lang} is not open to this code.
   */
  private static final class ThrowableFields {
    static final Field DETAIL_MESSAGE = accessibleField("detailMessage");
    static final Field CAUSE = accessibleField("cause");

    private static Field accessibleField(String name) {
      try {
        Field field = Throwable.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException | RuntimeException e) {
        return null;
      }
    }
  }

  // ---------- Throwable deserialization ----------

  /**
   * Restores a Throwable from its compact form as an instance of the exact class named in "class"
   * (see {@link #newThrowable}). Fields declared by the exception classes are then set from the
   * JSON, and the stack trace, cause and suppressed exceptions through the public Throwable API.
   */
  private static Throwable throwableFromJsonNode(ObjectNode object, DeserializationContext ctxt)
      throws IOException {
    JsonNode classNode = object.get("class");
    if (classNode == null || classNode.isNull()) {
      throw JsonMappingException.from(ctxt, "Missing 'class' field in Throwable JSON");
    }
    String className = classNode.asText();
    Class<?> classType;
    try {
      classType = Class.forName(className, false, JacksonDataConverter.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      classType = ApplicationFailureException.class;
    }
    if (!Throwable.class.isAssignableFrom(classType)) {
      throw JsonMappingException.from(ctxt, "Expected type that extends Throwable: " + className);
    }

    JsonNode messageNode = object.get("detailMessage");
    String message = messageNode == null || messageNode.isNull() ? null : messageNode.asText();
    Throwable result = newThrowable(classType, message);
    if (RESTORABLE.get(result.getClass())) {
      restoreFields(result, object, ctxt);
    }
    result.setStackTrace(parseStackTrace(object));

    JsonNode causeNode = object.get("cause");
    if (causeNode != null && causeNode.isObject()) {
      setCause(result, throwableFromJsonNode((ObjectNode) causeNode, ctxt));
    } else if (result.getCause() != null) {
      // Set by the constructor of the exception, while the original exception has no cause.
      setCauseField(result, result);
    }
    JsonNode suppressedNode = object.get("suppressedExceptions");
    if (suppressedNode != null && suppressedNode.isArray()) {
      for (JsonNode suppressed : suppressedNode) {
        if (suppressed.isObject()) {
          result.addSuppressed(throwableFromJsonNode((ObjectNode) suppressed, ctxt));
        }
      }
    }
    return result;
  }

  /**
   * Creates an instance of the exact exception class with the given message. As in
   * JsonDataConverter, the no-arg constructor of the class is used when it has one, so that its
   * field initializers run, and the message is then set directly. Otherwise the instance is created
   * without running any constructor of the class, as with Java serialization, so that exceptions
   * without a (String) or no-arg constructor keep their type.
   *
   * <p>A class whose fields cannot all be set (see {@link #RESTORABLE}) would lack its state that
   * way, so it is created with its (String) constructor, as is any class on a runtime without the
   * module jdk.unsupported. When that fails too, an ApplicationFailureException is returned.
   */
  private static Throwable newThrowable(Class<?> type, String message) {
    if (RESTORABLE.get(type)) {
      Throwable result = newThrowableWithNoArgConstructor(type, message);
      if (result != null) {
        return result;
      }
      Constructor<?> constructor = ConstructorBypass.constructorFor(type);
      if (constructor != null) {
        try {
          return (Throwable) constructor.newInstance(message);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
          log.debug("Failed to instantiate {} without its constructors", type.getName(), e);
        }
      }
    }
    try {
      Constructor<?> stringConstructor = type.getDeclaredConstructor(String.class);
      stringConstructor.setAccessible(true);
      return (Throwable) stringConstructor.newInstance(message);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      log.warn("Cannot instantiate {}, using ApplicationFailureException", type.getName(), e);
      return new ApplicationFailureException(message);
    }
  }

  private static Throwable newThrowableWithNoArgConstructor(Class<?> type, String message) {
    Field detailMessage = ThrowableFields.DETAIL_MESSAGE;
    if (detailMessage == null) {
      // The message could not be set.
      return null;
    }
    Constructor<?> constructor;
    try {
      constructor = type.getDeclaredConstructor();
    } catch (NoSuchMethodException e) {
      return null;
    }
    try {
      constructor.setAccessible(true);
      Throwable result = (Throwable) constructor.newInstance();
      detailMessage.set(result, message);
      return result;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      log.debug("Failed to instantiate {} with its no-arg constructor", type.getName(), e);
      return null;
    }
  }

  private static void setCause(Throwable throwable, Throwable cause) {
    try {
      throwable.initCause(cause);
    } catch (RuntimeException e) {
      // The constructor of the exception set a cause already. Replace it, as JsonDataConverter
      // does, or at least keep the cause visible.
      if (!setCauseField(throwable, cause)) {
        throwable.addSuppressed(cause);
      }
    }
  }

  /** Sets Throwable.cause directly. The throwable itself as cause means that it has none. */
  private static boolean setCauseField(Throwable throwable, Throwable cause) {
    Field field = ThrowableFields.CAUSE;
    if (field == null) {
      return false;
    }
    try {
      field.set(throwable, cause);
      return true;
    } catch (IllegalAccessException | RuntimeException e) {
      return false;
    }
  }

  /** Sets the fields declared by the classes between the throwable's class and Throwable. */
  private static void restoreFields(
      Throwable result, ObjectNode object, DeserializationContext ctxt) {
    Set<String> names = new HashSet<>();
    for (Class<?> clazz = result.getClass();
        clazz != Throwable.class;
        clazz = clazz.getSuperclass()) {
      for (Field field : clazz.getDeclaredFields()) {
        // As when writing, a field hidden by a field of the same name in a subclass is skipped.
        if (!isThrowableJsonField(field) || !names.add(field.getName())) {
          continue;
        }
        try {
          field.setAccessible(true);
          field.set(result, readField(field, fieldNode(object, field), field.get(result), ctxt));
        } catch (IllegalAccessException | RuntimeException e) {
          // For example a field of a JDK exception whose package is not open to this code.
          log.debug("Failed to restore field {} of {}", field.getName(), clazz.getName(), e);
        }
      }
    }
  }

  /**
   * Returns the value to set to the field. A field that is missing in the JSON or cannot be read
   * keeps the value it got when the exception was created. An Optional field is never left null:
   * callers such as WorkflowException.getWorkflowType() use it.
   */
  private static Object readField(
      Field field, JsonNode valueNode, Object currentValue, DeserializationContext ctxt) {
    Object value = currentValue;
    if (valueNode != null && valueNode.isNull()) {
      value = field.getType().isPrimitive() ? currentValue : null;
    } else if (valueNode != null) {
      try {
        JavaType type = ctxt.getTypeFactory().constructType(field.getGenericType());
        value = ctxt.readTreeAsValue(valueNode, type);
      } catch (IOException | RuntimeException e) {
        log.warn(
            "Failed to restore field {} of {}: {}",
            field.getName(),
            field.getDeclaringClass().getName(),
            e.toString());
      }
    }
    return value == null ? emptyValueOf(field.getType()) : value;
  }

  private static Object emptyValueOf(Class<?> type) {
    if (type == Optional.class) {
      return Optional.empty();
    } else if (type == OptionalInt.class) {
      return OptionalInt.empty();
    } else if (type == OptionalLong.class) {
      return OptionalLong.empty();
    } else if (type == OptionalDouble.class) {
      return OptionalDouble.empty();
    }
    return null;
  }

  private static StackTraceElement[] parseStackTrace(ObjectNode object) {
    JsonNode jsonStackTrace = object.get("stackTrace");
    if (jsonStackTrace == null || !jsonStackTrace.isTextual()) {
      return new StackTraceElement[0];
    }
    String stackTrace = jsonStackTrace.asText();
    List<StackTraceElement> result = new ArrayList<>();
    @SuppressWarnings("StringSplitter")
    String[] lines = stackTrace.split("\r\n|\n");
    for (String line : lines) {
      StackTraceElement element = parseStackTraceElement(line);
      // setStackTrace rejects null elements, so lines that are not stack frames are skipped.
      if (element != null) {
        result.add(element);
      }
    }
    return result.toArray(new StackTraceElement[0]);
  }

  private static StackTraceElement parseStackTraceElement(String line) {
    Matcher matcher = TRACE_ELEMENT_PATTERN.matcher(line);
    if (!matcher.matches()) {
      return null;
    }
    String declaringClass = matcher.group("className");
    String methodName = matcher.group("methodName");
    String fileName = matcher.group("fileName");
    int lineNumber = 0;
    String lns = matcher.group("lineNumber");
    if (lns != null && lns.length() > 0) {
      try {
        lineNumber = Integer.parseInt(matcher.group("lineNumber"));
      } catch (NumberFormatException e) {
        // ignore
      }
    }
    return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
  }

  // ---------- Custom Jackson serializers/deserializers ----------

  /**
   * Cadence specific (de)serialization. It is registered before the mapper interceptor runs, so
   * modules registered by the interceptor take precedence.
   */
  private static final class CadenceModule extends SimpleModule {

    /** A unique id: Jackson ignores a module registered under an id it has already seen. */
    static final String ID = "com.uber.cadence.converter.JacksonDataConverter.CadenceModule";

    CadenceModule(ObjectMapper mapper) {
      super(ID);
      addSerializer(DataConverter.class, new DataConverterSerializer());
      addDeserializer(DataConverter.class, new DataConverterDeserializer());
      addSerializer(Class.class, new ClassSerializer());
      addDeserializer(Class.class, new ClassDeserializer());
      // Serialize every Throwable (top-level, nested, suppressed) with the compact
      // {"class":..,"stackTrace":"..","cause":{..}} format, matching Gson's behavior.
      addSerializer(Throwable.class, new ThrowableSerializer(mapper));
      addDeserializer(Duration.class, new LenientDurationDeserializer());
      // HashSet iteration order depends on hash codes, which for enums and other classes without
      // a hashCode override differ between processes. Workflow code iterating a Set would then
      // behave differently on replay. JsonDataConverter uses LinkedHashSet as well.
      addAbstractTypeMapping(Set.class, LinkedHashSet.class);
      addAbstractTypeMapping(AbstractSet.class, LinkedHashSet.class);
      // Gson's tree types are written as the JSON they represent.
      addSerializer(JsonElement.class, new GsonJsonElementSerialization.Serializer());
    }

    /** The same id whichever way the Jackson version in use derives the id of a SimpleModule. */
    @Override
    public Object getTypeId() {
      return ID;
    }

    @Override
    public void setupModule(SetupContext context) {
      super.setupModule(context);
      context.addValueInstantiators(new ConstructorlessValueInstantiators());
      context.addDeserializers(new ThrowableDeserializers());
      context.addDeserializers(new GsonJsonElementSerialization.Deserializers());
      // Inserted as the primary introspector: Jackson 2.16 and later replace the enum aliases found
      // by an introspector that runs before their own.
      context.insertAnnotationIntrospector(new GsonAnnotationIntrospector());
      // Reads what JsonDataConverter wrote, and writes the Durations of the client's own data so
      // that JsonDataConverter can read them.
      context.addBeanDeserializerModifier(new GsonCompatibility.ReadModifier());
      context.addBeanDeserializerModifier(new GsonCompatibility.InternalPayloadReadModifier());
      context.addBeanSerializerModifier(new GsonCompatibility.InternalPayloadWriteModifier());
    }
  }

  /** Registers a module under another id. */
  private static final class ModuleWithId extends Module {
    private final String id;
    private final Module module;

    ModuleWithId(String module, Module delegate) {
      this.id = "com.uber.cadence.converter.JacksonDataConverter." + module;
      this.module = delegate;
    }

    @Override
    public String getModuleName() {
      return module.getModuleName();
    }

    @Override
    public Version version() {
      return module.version();
    }

    @Override
    public Object getTypeId() {
      return id;
    }

    @Override
    public void setupModule(SetupContext context) {
      module.setupModule(context);
    }
  }

  /** Serializes DataConverter fields to a simple {"type":"JSON"} object. */
  private static class DataConverterSerializer extends JsonSerializer<DataConverter> {
    @Override
    public void serialize(DataConverter value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeStartObject();
      gen.writeStringField(TYPE_FIELD_NAME, JSON_CONVERTER_TYPE);
      gen.writeEndObject();
    }

    /** Written without a type id, also with default typing. */
    @Override
    public void serializeWithType(
        DataConverter value,
        JsonGenerator gen,
        SerializerProvider serializers,
        TypeSerializer typeSer)
        throws IOException {
      serialize(value, gen, serializers);
    }
  }

  /** Deserializes DataConverter fields, returning the JacksonDataConverter singleton. */
  private static class DataConverterDeserializer extends JsonDeserializer<DataConverter> {
    @Override
    public DataConverter deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = p.getCodec().readTree(p);
      JsonNode typeNode = node.get(TYPE_FIELD_NAME);
      if (typeNode == null) {
        throw new IOException("Cannot deserialize DataConverter. Missing type field");
      }
      String value = typeNode.asText();
      if (!"JSON".equals(value)) {
        throw new IOException(
            "Cannot deserialize DataConverter. Expected type is JSON. Found " + value);
      }
      return JacksonDataConverter.getInstance();
    }

    /** Read without a type id, also with default typing. */
    @Override
    public Object deserializeWithType(
        JsonParser p, DeserializationContext ctxt, TypeDeserializer typeDeserializer)
        throws IOException {
      return deserialize(p, ctxt);
    }
  }

  /** Serializes Class fields to {"className":"fully.qualified.Name"}. */
  @SuppressWarnings("rawtypes")
  private static class ClassSerializer extends JsonSerializer<Class> {
    @Override
    public void serialize(Class value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      gen.writeStartObject();
      gen.writeStringField(CLASS_NAME_FIELD_NAME, value.getName());
      gen.writeEndObject();
    }
  }

  /** Deserializes Class fields from {"className":"fully.qualified.Name"}. */
  @SuppressWarnings("rawtypes")
  private static class ClassDeserializer extends JsonDeserializer<Class> {
    @Override
    public Class deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = p.getCodec().readTree(p);
      JsonNode classNameNode = node.get(CLASS_NAME_FIELD_NAME);
      if (classNameNode == null) {
        throw new IOException("Cannot deserialize class. Missing " + CLASS_NAME_FIELD_NAME);
      }
      String className = classNameNode.asText();
      try {
        return Class.forName(className);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Serializes every Throwable (top-level, nested, or suppressed) through {@link
   * #throwableToJsonNode} so the compact, class-tagged format is always used — matching the Gson
   * CustomThrowableTypeAdapter behavior.
   */
  private static class ThrowableSerializer extends JsonSerializer<Throwable> {
    private final ObjectMapper mapper;

    ThrowableSerializer(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    @Override
    public void serialize(Throwable value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      // Use the mapper backing the current generator so caller customizations apply.
      ObjectMapper active =
          (gen.getCodec() instanceof ObjectMapper) ? (ObjectMapper) gen.getCodec() : mapper;
      JsonNode node = throwableToJsonNode(value, active);
      gen.writeTree(node);
    }

    /** The "class" field identifies the exception class, also with default typing. */
    @Override
    public void serializeWithType(
        Throwable value, JsonGenerator gen, SerializerProvider provider, TypeSerializer typeSer)
        throws IOException {
      serialize(value, gen, provider);
    }
  }

  /**
   * Routes every Throwable type to {@link ThrowableDeserializer}, so that Jackson never builds a
   * bean deserializer for it, which would need reflective access to java.lang.Throwable.
   */
  private static final class ThrowableDeserializers extends Deserializers.Base {
    private static final ThrowableDeserializer DESERIALIZER = new ThrowableDeserializer();

    @Override
    public JsonDeserializer<?> findBeanDeserializer(
        JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
      return Throwable.class.isAssignableFrom(type.getRawClass()) ? DESERIALIZER : null;
    }
  }

  /** Deserializes the compact format written by {@link ThrowableSerializer}. */
  private static final class ThrowableDeserializer extends StdDeserializer<Throwable> {

    ThrowableDeserializer() {
      super(Throwable.class);
    }

    @Override
    public Throwable deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = ctxt.readTree(p);
      if (!node.isObject()) {
        return ctxt.reportInputMismatch(
            Throwable.class, "Expected JSON object for Throwable, found %s", node.getNodeType());
      }
      return throwableFromJsonNode((ObjectNode) node, ctxt);
    }

    /** The "class" field identifies the exception class, also with default typing. */
    @Override
    public Object deserializeWithType(
        JsonParser p, DeserializationContext ctxt, TypeDeserializer typeDeserializer)
        throws IOException {
      return deserialize(p, ctxt);
    }

    @Override
    public boolean isCachable() {
      return true;
    }
  }

  /**
   * Reads a Duration in the formats of Jackson and also in the {"seconds":..,"nanos":..} format of
   * JsonDataConverter, which is found in histories recorded with it, for example in local activity
   * markers and in the RetryOptions of Workflow.retry.
   */
  static final class LenientDurationDeserializer extends StdDeserializer<Duration>
      implements ContextualDeserializer {

    private final JsonDeserializer<?> delegate;

    LenientDurationDeserializer() {
      this(DurationDeserializer.INSTANCE);
    }

    private LenientDurationDeserializer(JsonDeserializer<?> delegate) {
      super(Duration.class);
      this.delegate = delegate;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property)
        throws JsonMappingException {
      return new LenientDurationDeserializer(
          DurationDeserializer.INSTANCE.createContextual(ctxt, property));
    }

    @Override
    public Duration deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      if (!p.hasToken(JsonToken.START_OBJECT)) {
        return (Duration) delegate.deserialize(p, ctxt);
      }
      JsonNode node = ctxt.readTree(p);
      JsonNode seconds = node.path("seconds");
      JsonNode nanos = node.path("nanos");
      if (!isLong(seconds) || !(nanos.isMissingNode() || isLong(nanos))) {
        return ctxt.reportInputMismatch(
            Duration.class, "Expected {\"seconds\":..,\"nanos\":..} for Duration, found %s", node);
      }
      return Duration.ofSeconds(seconds.longValue(), nanos.longValue());
    }

    private static boolean isLong(JsonNode node) {
      return node.isIntegralNumber() && node.canConvertToLong();
    }
  }

  /**
   * Lets Jackson deserialize classes that have no creator it can use (no no-arg constructor, {@code
   * JsonCreator} or {@code ConstructorProperties}), such as immutable classes with only an all-args
   * constructor, Lombok {@code @Value} classes and non-static inner classes. Such classes are
   * instantiated without running their constructors, as JsonDataConverter does, and their fields
   * are then set from the JSON.
   */
  private static final class ConstructorlessValueInstantiators extends ValueInstantiators.Base {

    private static final ImmutableList<String> JDK_PACKAGE_PREFIXES =
        ImmutableList.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    @Override
    public ValueInstantiator findValueInstantiator(
        DeserializationConfig config,
        BeanDescription beanDesc,
        ValueInstantiator defaultInstantiator) {
      Class<?> type = beanDesc.getBeanClass();
      if (hasCreator(defaultInstantiator) || !isEligible(type)) {
        return defaultInstantiator;
      }
      Constructor<?> constructor = ConstructorBypass.constructorFor(type);
      if (constructor == null) {
        return defaultInstantiator;
      }
      return new ConstructorlessValueInstantiator(defaultInstantiator, constructor);
    }

    private static boolean hasCreator(ValueInstantiator instantiator) {
      return instantiator.canCreateUsingDefault()
          || instantiator.canCreateFromObjectWith()
          || instantiator.canCreateUsingDelegate()
          || instantiator.canCreateUsingArrayDelegate();
    }

    // Arrays, primitive types and enums do not get here, nor do throwables, which
    // ThrowableDeserializers handles. Collections and maps do.
    private static boolean isEligible(Class<?> type) {
      // Collections and maps keep their elements in fields that their constructors initialize.
      if (Modifier.isAbstract(type.getModifiers())
          || Collection.class.isAssignableFrom(type)
          || Map.class.isAssignableFrom(type)) {
        return false;
      }
      // JDK classes, their subclasses and records keep Jackson's own handling.
      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
        if (isJdkClass(c)) {
          return false;
        }
      }
      return true;
    }

    private static boolean isJdkClass(Class<?> type) {
      for (String prefix : JDK_PACKAGE_PREFIXES) {
        if (type.getName().startsWith(prefix)) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class ConstructorlessValueInstantiator extends ValueInstantiator.Delegating {

    private final Constructor<?> constructor;

    ConstructorlessValueInstantiator(ValueInstantiator delegate, Constructor<?> constructor) {
      super(delegate);
      this.constructor = constructor;
    }

    @Override
    public ValueInstantiator createContextual(DeserializationContext ctxt, BeanDescription beanDesc)
        throws JsonMappingException {
      ValueInstantiator contextual = delegate().createContextual(ctxt, beanDesc);
      return contextual == delegate()
          ? this
          : new ConstructorlessValueInstantiator(contextual, constructor);
    }

    @Override
    public boolean canInstantiate() {
      return true;
    }

    @Override
    public boolean canCreateUsingDefault() {
      return true;
    }

    @Override
    public Object createUsingDefault(DeserializationContext ctxt) throws IOException {
      try {
        return constructor.newInstance();
      } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        return ctxt.handleInstantiationProblem(constructor.getDeclaringClass(), null, e);
      }
    }
  }
}
