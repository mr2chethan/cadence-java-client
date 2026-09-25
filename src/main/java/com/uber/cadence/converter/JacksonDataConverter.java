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
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Defaults;
import com.google.common.collect.ImmutableSet;
import com.uber.cadence.client.ApplicationFailureException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 *   <li>Exceptions are restored as their exact class with their exact message, stack trace, cause,
 *       suppressed exceptions and fields. Like {@link JsonDataConverter}, the no-arg constructor of
 *       the exception class is used when it has one. Otherwise, as with Java serialization, no
 *       constructor of the exception class runs. A JDK exception whose fields are not accessible to
 *       this code (JDK 16 and later without --add-opens for its package) is created with its
 *       (String) constructor, or else as an ApplicationFailureException, with its message.
 *   <li>An empty or whitespace-only payload is decoded as null (the first argument null and the
 *       remaining ones their default value), like {@link JsonDataConverter}.
 *   <li>An abstract {@link Set} is decoded as an insertion-ordered {@link LinkedHashSet}.
 *   <li>Jackson annotations on the classes of payloads apply, for example {@code @JsonIgnore},
 *       {@code @JsonProperty} or {@code @JsonTypeInfo}.
 * </ul>
 */
public final class JacksonDataConverter implements DataConverter {

  private static final Logger log = LoggerFactory.getLogger(JacksonDataConverter.class);

  private static final DataConverter INSTANCE = new JacksonDataConverter();
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

  private final ObjectMapper objectMapper;

  /**
   * Returns the singleton instance of this converter with a default-configured {@link
   * ObjectMapper}.
   */
  public static DataConverter getInstance() {
    return INSTANCE;
  }

  private JacksonDataConverter() {
    this(Function.identity());
  }

  /**
   * Constructs an instance giving an ability to override {@link ObjectMapper} initialization.
   *
   * @param mapperInterceptor function that intercepts {@link ObjectMapper} construction. The
   *     interceptor receives an already-configured ObjectMapper and must return the mapper to use
   *     (may be the same instance, mutated in-place, or a new one).
   */
  public JacksonDataConverter(Function<ObjectMapper, ObjectMapper> mapperInterceptor) {
    ObjectMapper mapper = newDefaultObjectMapper();
    this.objectMapper = mapperInterceptor.apply(mapper);
  }

  private static ObjectMapper newDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();

    // Register Java 8 date/time module
    mapper.registerModule(new JavaTimeModule());

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

    // Register custom module for Throwable, DataConverter, and Class handling
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
        String json = objectMapper.writeValueAsString(values[0]);
        return json.getBytes(StandardCharsets.UTF_8);
      }
      String json = objectMapper.writeValueAsString(values);
      return json.getBytes(StandardCharsets.UTF_8);
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
      JavaType javaType = objectMapper.getTypeFactory().constructType(valueType);
      return objectMapper.readValue(new String(content, StandardCharsets.UTF_8), javaType);
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
        JavaType javaType = objectMapper.getTypeFactory().constructType(valueTypes[0]);
        Object result =
            objectMapper.readValue(new String(content, StandardCharsets.UTF_8), javaType);
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
          node.set(field.getName(), mapper.valueToTree(value));
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
        && !RESERVED_THROWABLE_KEYS.contains(field.getName());
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
          field.set(result, readField(field, object.get(field.getName()), field.get(result), ctxt));
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

    CadenceModule(ObjectMapper mapper) {
      super("CadenceModule");
      addSerializer(DataConverter.class, new DataConverterSerializer());
      addDeserializer(DataConverter.class, new DataConverterDeserializer());
      addSerializer(Class.class, new ClassSerializer());
      addDeserializer(Class.class, new ClassDeserializer());
      // Serialize every Throwable (top-level, nested, suppressed) with the compact
      // {"class":..,"stackTrace":"..","cause":{..}} format, matching Gson's behavior.
      addSerializer(Throwable.class, new ThrowableSerializer(mapper));
      // HashSet iteration order depends on hash codes, which for enums and other classes without
      // a hashCode override differ between processes. Workflow code iterating a Set would then
      // behave differently on replay. JsonDataConverter uses LinkedHashSet as well.
      addAbstractTypeMapping(Set.class, LinkedHashSet.class);
      addAbstractTypeMapping(AbstractSet.class, LinkedHashSet.class);
    }

    @Override
    public void setupModule(SetupContext context) {
      super.setupModule(context);
      context.addDeserializers(new ThrowableDeserializers());
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

    @Override
    public boolean isCachable() {
      return true;
    }
  }
}
