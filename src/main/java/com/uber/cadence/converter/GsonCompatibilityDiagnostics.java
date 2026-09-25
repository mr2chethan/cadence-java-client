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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerBuilder;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes visible where JacksonDataConverter silently gives other results than JsonDataConverter:
 * properties of a payload that are skipped because the class has no field for them, and classes
 * whose JSON Jackson annotations or the ObjectMapper configuration change. Each message is logged
 * once per JVM, however many converters and mappers there are, with the logger {@code
 * com.uber.cadence.converter.JacksonDataConverter.GsonCompatibility}.
 */
final class GsonCompatibilityDiagnostics {

  static final Logger log =
      LoggerFactory.getLogger(JacksonDataConverter.class.getName() + ".GsonCompatibility");

  private static final int MAX_LOGGED = 1000;
  private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

  private GsonCompatibilityDiagnostics() {}

  /** True the first time a key is seen, up to MAX_LOGGED keys. */
  private static boolean firstTime(String key) {
    return LOGGED.size() < MAX_LOGGED && LOGGED.add(key);
  }

  /** Field name to the name JsonDataConverter uses for it. */
  private static Map<String, String> gsonNames(Class<?> type) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field field : c.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers)
            || Modifier.isTransient(modifiers)
            || field.isSynthetic()) {
          continue;
        }
        SerializedName name = field.getAnnotation(SerializedName.class);
        result.putIfAbsent(field.getName(), name == null ? field.getName() : name.value());
      }
    }
    return result;
  }

  private static boolean isChecked(Class<?> type) {
    String name = type.getName();
    return !(name.startsWith("java.")
        || name.startsWith("javax.")
        || name.startsWith("com.google.gson.")
        || ClientPayloads.isClientPayload(type));
  }

  /** Logs a skipped property once per class and property. Does not handle it. */
  static final class UnknownProperties extends DeserializationProblemHandler {
    @Override
    public boolean handleUnknownProperty(
        DeserializationContext ctxt,
        JsonParser p,
        JsonDeserializer<?> deserializer,
        Object beanOrClass,
        String propertyName) {
      if (ctxt.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)) {
        // Reading fails anyway.
        return false;
      }
      Class<?> type =
          beanOrClass instanceof Class ? (Class<?>) beanOrClass : beanOrClass.getClass();
      if (isChecked(type)
          && firstTime("unknown\u0000" + type.getName() + '\u0000' + propertyName)) {
        log.warn(
            "JacksonDataConverter skipped property \"{}\" of a payload read as {}, which has no"
                + " field for it (a @SerializedName name, a renamed or a removed field)."
                + " @JsonIgnoreProperties on the class silences this. Logged once.",
            propertyName,
            type.getName());
      }
      return false;
    }
  }

  static final class Writing extends BeanSerializerModifier {
    private static final long serialVersionUID = 1L;

    @Override
    public BeanSerializerBuilder updateBuilder(
        SerializationConfig config, BeanDescription beanDesc, BeanSerializerBuilder builder) {
      Class<?> type = beanDesc.getBeanClass();
      List<BeanPropertyWriter> writers = builder.getProperties();
      if (!isChecked(type) || writers == null) {
        return builder;
      }
      List<String> adapters = new ArrayList<>();
      if (type.isAnnotationPresent(JsonAdapter.class)) {
        adapters.add(type.getSimpleName());
      }
      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
        for (Field field : c.getDeclaredFields()) {
          if (field.isAnnotationPresent(JsonAdapter.class)) {
            adapters.add(field.getName());
          }
        }
      }
      if (!adapters.isEmpty() && firstTime("adapter\u0000" + type.getName())) {
        log.warn(
            "Gson @JsonAdapter of {} on {} is not used by JacksonDataConverter.",
            type.getName(),
            adapters);
      }
      Map<String, String> gson = gsonNames(type);
      Set<String> written = new HashSet<>();
      for (BeanPropertyWriter writer : writers) {
        written.add(writer.getName());
      }
      // Field name to the name it is written as, also when it is written through a getter.
      Map<String, String> jackson = new LinkedHashMap<>();
      for (BeanPropertyDefinition property : beanDesc.findProperties()) {
        if (written.contains(property.getName())) {
          jackson.put(
              property.hasField() ? property.getField().getName() : property.getInternalName(),
              property.getName());
        }
      }
      List<String> missing = new ArrayList<>();
      List<String> renamed = new ArrayList<>();
      for (Map.Entry<String, String> e : gson.entrySet()) {
        String name = jackson.get(e.getKey());
        if (name == null) {
          missing.add(e.getKey());
        } else if (!name.equals(e.getValue())) {
          renamed.add(
              e.getKey() + " as \"" + name + "\" (JsonDataConverter: \"" + e.getValue() + "\")");
        }
      }
      if ((!missing.isEmpty() || !renamed.isEmpty()) && firstTime("write\u0000" + type.getName())) {
        log.info(
            "JacksonDataConverter writes {} differently from JsonDataConverter because of"
                + " Jackson annotations or ObjectMapper configuration. Not written: {}."
                + " Renamed: {}.",
            type.getName(),
            missing,
            renamed);
      }
      return builder;
    }
  }

  static final class Reading extends BeanDeserializerModifier {
    private static final long serialVersionUID = 1L;

    @Override
    public BeanDeserializerBuilder updateBuilder(
        DeserializationConfig config, BeanDescription beanDesc, BeanDeserializerBuilder builder) {
      Class<?> type = beanDesc.getBeanClass();
      if (!isChecked(type)
          || builder.getValueInstantiator() == null
          || builder.getAnySetter() != null) {
        return builder;
      }
      Set<String> accepted = new HashSet<>();
      for (Iterator<SettableBeanProperty> it = builder.getProperties(); it.hasNext(); ) {
        SettableBeanProperty property = it.next();
        accepted.add(property.getName());
        for (PropertyName alias : property.findAliases(config)) {
          accepted.add(alias.getSimpleName());
        }
      }
      SettableBeanProperty[] creatorProperties =
          builder.getValueInstantiator().getFromObjectArguments(config);
      if (creatorProperties != null) {
        for (SettableBeanProperty property : creatorProperties) {
          accepted.add(property.getName());
        }
      }
      List<String> notRead = new ArrayList<>();
      for (Map.Entry<String, String> e : gsonNames(type).entrySet()) {
        if (!accepted.contains(e.getValue())) {
          notRead.add(e.getKey() + " (\"" + e.getValue() + "\")");
        }
      }
      if (!notRead.isEmpty() && firstTime("read\u0000" + type.getName())) {
        log.info(
            "JacksonDataConverter does not read fields {} of {} from what JsonDataConverter"
                + " writes, because of Jackson annotations or ObjectMapper configuration.",
            notRead,
            type.getName());
      }
      return builder;
    }
  }
}
