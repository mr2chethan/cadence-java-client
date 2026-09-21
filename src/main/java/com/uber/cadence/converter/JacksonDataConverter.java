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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
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
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
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
    SimpleModule cadenceModule = new SimpleModule("CadenceModule");
    cadenceModule.addSerializer(DataConverter.class, new DataConverterSerializer());
    cadenceModule.addDeserializer(DataConverter.class, new DataConverterDeserializer());
    cadenceModule.addSerializer(Class.class, new ClassSerializer());
    cadenceModule.addDeserializer(Class.class, new ClassDeserializer());
    // Serialize every Throwable (top-level, nested, suppressed) with the compact
    // {"class":..,"stackTrace":"..","cause":{..}} format, matching Gson's behavior.
    cadenceModule.addSerializer(Throwable.class, new ThrowableSerializer(mapper));
    cadenceModule.setDeserializerModifier(new ThrowableDeserializerModifier());
    mapper.registerModule(cadenceModule);

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
    if (content == null) {
      return null;
    }
    try {
      JavaType javaType = objectMapper.getTypeFactory().constructType(valueType);
      if (Throwable.class.isAssignableFrom(valueClass)) {
        return deserializeThrowable(content, javaType);
      }
      return objectMapper.readValue(new String(content, StandardCharsets.UTF_8), javaType);
    } catch (DataConverterException e) {
      throw e;
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
      if (valueTypes.length == 1) {
        JavaType javaType = objectMapper.getTypeFactory().constructType(valueTypes[0]);
        Object result;
        if (Throwable.class.isAssignableFrom(javaType.getRawClass())) {
          result = deserializeThrowable(content, javaType);
        } else {
          result = objectMapper.readValue(new String(content, StandardCharsets.UTF_8), javaType);
        }
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
          Type t = valueTypes[i];
          if (t instanceof Class) {
            result[i] = Defaults.defaultValue((Class<?>) t);
          } else {
            result[i] = null;
          }
        } else {
          JavaType javaType = objectMapper.getTypeFactory().constructType(valueTypes[i]);
          if (Throwable.class.isAssignableFrom(javaType.getRawClass())) {
            byte[] elementBytes =
                objectMapper.writeValueAsString(array.get(i)).getBytes(StandardCharsets.UTF_8);
            result[i] = deserializeThrowable(elementBytes, javaType);
          } else {
            result[i] = objectMapper.treeToValue(array.get(i), javaType);
          }
        }
      }
      return result;
    } catch (DataConverterException e) {
      throw e;
    } catch (Exception e) {
      throw new DataConverterException(content, valueTypes, e);
    }
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
    // Always include the message
    node.put("detailMessage", throwable.getMessage());

    // Serialize subclass-specific fields via reflection
    Class<?> clazz = throwable.getClass();
    while (clazz != null && clazz != Throwable.class && clazz != Object.class) {
      for (Field field : clazz.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
            || java.lang.reflect.Modifier.isTransient(field.getModifiers())
            || field.isSynthetic()) {
          continue;
        }
        try {
          field.setAccessible(true);
          Object value = field.get(throwable);
          node.set(field.getName(), mapper.valueToTree(value));
        } catch (Exception e) {
          log.warn("Failed to serialize field: " + field.getName(), e);
        }
      }
      clazz = clazz.getSuperclass();
    }

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

  @SuppressWarnings("unchecked")
  private <T> T deserializeThrowable(byte[] content, JavaType javaType) throws IOException {
    JsonNode rootNode = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
    if (!rootNode.isObject()) {
      throw new DataConverterException(
          content, new Type[] {javaType}, new IOException("Expected JSON object for Throwable"));
    }
    return (T) throwableFromJsonNode((ObjectNode) rootNode, objectMapper);
  }

  static Throwable throwableFromJsonNode(ObjectNode object, ObjectMapper mapper)
      throws IOException {
    JsonNode classElement = object.get("class");
    if (classElement == null) {
      throw new IOException("Missing 'class' field in Throwable JSON");
    }

    String className = classElement.asText();
    Class<?> classType;
    try {
      classType = Class.forName(className);
    } catch (ClassNotFoundException e) {
      classType = ApplicationFailureException.class;
    }
    if (!Throwable.class.isAssignableFrom(classType)) {
      throw new IOException("Expected type that extends Throwable: " + className);
    }

    StackTraceElement[] stackTrace = parseStackTrace(object);

    // Remove special fields before default deserialization
    object.remove("class");
    object.put("stackTrace", ""); // Clear so it doesn't interfere

    // Deserialize the cause separately if present
    JsonNode causeNode = object.remove("cause");

    Throwable result;
    try {
      result = (Throwable) mapper.treeToValue(object, classType);
    } catch (Exception e) {
      // Fallback: construct manually
      result = constructThrowable(classType, object);
    }

    // Restore subclass-specific fields via reflection.
    // Jackson's default deserialization may not populate final fields,
    // so we do it manually from the JSON node.
    restoreSubclassFields(result, object, mapper);

    result.setStackTrace(stackTrace);

    // Restore cause
    if (causeNode != null && causeNode.isObject()) {
      try {
        Throwable causeThrowable = throwableFromJsonNode((ObjectNode) causeNode, mapper);
        Field causeField = Throwable.class.getDeclaredField("cause");
        causeField.setAccessible(true);
        causeField.set(result, causeThrowable);
      } catch (Exception e) {
        log.warn("Failed to restore cause in deserialized throwable.", e);
      }
    }

    return result;
  }

  private static Throwable constructThrowable(Class<?> classType, ObjectNode object) {
    String message = null;
    JsonNode msgNode = object.get("detailMessage");
    if (msgNode != null) {
      message = msgNode.asText();
    }

    try {
      Constructor<?> constructor = classType.getConstructor(String.class);
      return (Throwable) constructor.newInstance(message);
    } catch (Exception e1) {
      try {
        Constructor<?> constructor = classType.getConstructor();
        return (Throwable) constructor.newInstance();
      } catch (Exception e2) {
        return new RuntimeException(message);
      }
    }
  }

  /**
   * Restores subclass-specific fields on a Throwable instance from the JSON object node. This
   * handles cases where Jackson cannot populate final fields through normal deserialization.
   */
  private static void restoreSubclassFields(
      Throwable result, ObjectNode object, ObjectMapper mapper) {
    Class<?> clazz = result.getClass();
    while (clazz != null && clazz != Throwable.class && clazz != Object.class) {
      for (Field field : clazz.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
            || java.lang.reflect.Modifier.isTransient(field.getModifiers())
            || field.isSynthetic()) {
          continue;
        }
        JsonNode valueNode = object.get(field.getName());
        if (valueNode != null && !valueNode.isNull()) {
          try {
            field.setAccessible(true);
            JavaType fieldType = mapper.getTypeFactory().constructType(field.getGenericType());
            Object value = mapper.treeToValue(valueNode, fieldType);
            field.set(result, value);
          } catch (Exception e) {
            log.debug("Failed to restore field: " + field.getName(), e);
          }
        }
      }
      clazz = clazz.getSuperclass();
    }
  }

  private static StackTraceElement[] parseStackTrace(ObjectNode object) {
    JsonNode jsonStackTrace = object.get("stackTrace");
    if (jsonStackTrace == null) {
      return new StackTraceElement[0];
    }
    String stackTrace = jsonStackTrace.asText();
    if (stackTrace == null || stackTrace.isEmpty()) {
      return new StackTraceElement[0];
    }
    try {
      @SuppressWarnings("StringSplitter")
      String[] lines = stackTrace.split("\r\n|\n");
      StackTraceElement[] result = new StackTraceElement[lines.length];
      for (int i = 0; i < lines.length; i++) {
        result[i] = parseStackTraceElement(lines[i]);
      }
      return result;
    } catch (Exception e) {
      if (log.isWarnEnabled()) {
        log.warn("Failed to parse stack trace: " + stackTrace);
      }
      return new StackTraceElement[0];
    }
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
   * Modifier that intercepts Throwable deserialization. Keeps the default (bean) deserializer as a
   * delegate so that subclass fields are preserved, falling back to constructor-based construction
   * only when the delegate cannot handle the type.
   */
  private static class ThrowableDeserializerModifier extends BeanDeserializerModifier {
    @Override
    public JsonDeserializer<?> modifyDeserializer(
        DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
      if (Throwable.class.isAssignableFrom(beanDesc.getBeanClass())) {
        return new ThrowableDeserializer(beanDesc.getBeanClass(), deserializer);
      }
      return deserializer;
    }
  }

  /**
   * Custom deserializer for Throwable types. Delegates to the default bean deserializer first so
   * that the concrete type and all declared fields are restored — matching the Gson path which uses
   * a reflective bean adapter. Falls back to constructor-based construction only when the delegate
   * cannot handle the type (e.g. package-private constructors).
   */
  private static class ThrowableDeserializer extends JsonDeserializer<Throwable>
      implements com.fasterxml.jackson.databind.deser.ResolvableDeserializer {
    private final Class<?> targetClass;
    private final JsonDeserializer<?> delegate;

    ThrowableDeserializer(Class<?> targetClass, JsonDeserializer<?> delegate) {
      this.targetClass = targetClass;
      this.delegate = delegate;
    }

    @Override
    public void resolve(DeserializationContext ctxt)
        throws com.fasterxml.jackson.databind.JsonMappingException {
      if (delegate instanceof com.fasterxml.jackson.databind.deser.ResolvableDeserializer) {
        ((com.fasterxml.jackson.databind.deser.ResolvableDeserializer) delegate).resolve(ctxt);
      }
    }

    @Override
    public Throwable deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = p.getCodec().readTree(p);

      if (node.isObject() && node.has("class")) {
        // Compact Cadence format: reuse the shared path so type, stack trace and cause survive.
        ObjectMapper active = (ObjectMapper) p.getCodec();
        return throwableFromJsonNode((ObjectNode) node, active);
      }

      String message = null;
      if (node.has("detailMessage")) {
        message = node.get("detailMessage").asText();
      }

      Throwable result = null;

      // Try delegate first to preserve subclass type and fields
      if (delegate != null) {
        try {
          JsonParser nodeParser = node.traverse(p.getCodec());
          nodeParser.nextToken();
          result = (Throwable) delegate.deserialize(nodeParser, ctxt);
        } catch (Exception e) {
          // Delegate failed — fall through to constructor-based construction
          log.debug("Delegate deserialization failed for {}, falling back.", targetClass, e);
        }
      }

      // Fall back to constructor-based construction
      if (result == null) {
        try {
          Constructor<?> constructor = targetClass.getDeclaredConstructor(String.class);
          constructor.setAccessible(true);
          result = (Throwable) constructor.newInstance(message);
        } catch (Exception e1) {
          try {
            Constructor<?> constructor = targetClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            result = (Throwable) constructor.newInstance();
          } catch (Exception e2) {
            result = new RuntimeException(message);
          }
        }
      }

      // Handle suppressed exceptions
      if (node.has("suppressedExceptions") && node.get("suppressedExceptions").isArray()) {
        for (JsonNode suppressed : node.get("suppressedExceptions")) {
          try {
            if (suppressed.has("detailMessage")) {
              result.addSuppressed(new RuntimeException(suppressed.get("detailMessage").asText()));
            }
          } catch (Exception e) {
            // ignore
          }
        }
      }

      return result;
    }
  }
}
