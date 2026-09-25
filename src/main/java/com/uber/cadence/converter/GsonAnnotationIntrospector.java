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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.google.gson.annotations.SerializedName;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads Gson's {@link SerializedName} the way {@link JsonDataConverter} applies it: on fields that
 * Gson serializes (not static or transient) and on enum constants.
 *
 * <p>It is inserted before Jackson's own introspector, because Jackson 2.16 and later replace the
 * enum aliases found by an introspector that runs before theirs. It defers to Jackson annotations
 * itself: a non-empty {@code @JsonProperty} name takes precedence over {@code @SerializedName}, and
 * {@code @JsonAlias} names are kept.
 */
final class GsonAnnotationIntrospector extends NopAnnotationIntrospector {

  private static final long serialVersionUID = 1L;

  @Override
  public Version version() {
    return Version.unknownVersion();
  }

  private static SerializedName serializedName(Annotated annotated) {
    if (!(annotated instanceof AnnotatedField)) {
      return null;
    }
    AnnotatedField field = (AnnotatedField) annotated;
    int modifiers = field.getModifiers();
    // Gson excludes static and transient fields even when they are annotated.
    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
      return null;
    }
    return field.getAnnotation(SerializedName.class);
  }

  /** Through the Annotated, so that the annotation of a mix-in counts too. */
  private static boolean hasJacksonName(Annotated annotated) {
    return hasJacksonName(annotated.getAnnotation(JsonProperty.class));
  }

  private static boolean hasJacksonName(AnnotatedElement element) {
    return hasJacksonName(element.getAnnotation(JsonProperty.class));
  }

  private static boolean hasJacksonName(JsonProperty property) {
    return property != null && !property.value().isEmpty();
  }

  private static PropertyName name(Annotated a) {
    SerializedName name = serializedName(a);
    return name == null || hasJacksonName(a) ? null : PropertyName.construct(name.value());
  }

  @Override
  public PropertyName findNameForSerialization(Annotated a) {
    return name(a);
  }

  @Override
  public PropertyName findNameForDeserialization(Annotated a) {
    return name(a);
  }

  /**
   * The alternate names, the names of {@code @JsonAlias}, and the {@code @SerializedName} name when
   * a {@code @JsonProperty} name takes precedence, so that what JsonDataConverter wrote is read.
   */
  @Override
  public List<PropertyName> findPropertyAliases(Annotated a) {
    SerializedName name = serializedName(a);
    if (name == null) {
      return null;
    }
    Set<String> names = new LinkedHashSet<>();
    JsonAlias alias = a.getAnnotation(JsonAlias.class);
    if (alias != null) {
      Collections.addAll(names, alias.value());
    }
    if (hasJacksonName(a)) {
      names.add(name.value());
    }
    Collections.addAll(names, name.alternate());
    if (names.isEmpty()) {
      return null;
    }
    List<PropertyName> result = new ArrayList<>();
    for (String n : names) {
      result.add(PropertyName.construct(n));
    }
    return result;
  }

  // Jackson 2.16 and later call the variants with a MapperConfig, earlier versions the others.

  @Override
  public String[] findEnumValues(
      MapperConfig<?> config, AnnotatedClass annotatedClass, Enum<?>[] values, String[] names) {
    return enumValues(values, names);
  }

  @Override
  @SuppressWarnings("deprecation")
  public String[] findEnumValues(Class<?> enumType, Enum<?>[] values, String[] names) {
    return enumValues(values, names);
  }

  @Override
  public void findEnumAliases(
      MapperConfig<?> config, AnnotatedClass annotatedClass, Enum<?>[] values, String[][] aliases) {
    enumAliases(values, aliases);
  }

  @Override
  @SuppressWarnings("deprecation")
  public void findEnumAliases(Class<?> enumType, Enum<?>[] values, String[][] aliases) {
    enumAliases(values, aliases);
  }

  private static String[] enumValues(Enum<?>[] values, String[] names) {
    for (int i = 0; i < values.length; i++) {
      Field field = constantField(values[i]);
      SerializedName name = field == null ? null : field.getAnnotation(SerializedName.class);
      if (name != null && !hasJacksonName(field)) {
        names[i] = name.value();
      }
    }
    return names;
  }

  /**
   * Adds the alternate names and, as JsonDataConverter reads them too, the name and toString() of
   * the constant: JsonDataConverter writes the keys of a Map with toString().
   */
  private static void enumAliases(Enum<?>[] values, String[][] aliases) {
    for (int i = 0; i < values.length; i++) {
      Set<String> names = new LinkedHashSet<>();
      if (aliases[i] != null) {
        Collections.addAll(names, aliases[i]);
      }
      Field field = constantField(values[i]);
      SerializedName name = field == null ? null : field.getAnnotation(SerializedName.class);
      if (name != null) {
        if (hasJacksonName(field)) {
          names.add(name.value());
        }
        Collections.addAll(names, name.alternate());
        names.add(values[i].name());
      }
      try {
        names.add(values[i].toString());
      } catch (RuntimeException e) {
        // Not used as an alias.
      }
      aliases[i] = names.toArray(new String[0]);
    }
  }

  private static Field constantField(Enum<?> value) {
    try {
      return value.getDeclaringClass().getField(value.name());
    } catch (NoSuchFieldException | SecurityException e) {
      return null;
    }
  }
}
