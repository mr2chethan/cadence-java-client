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
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Lets JacksonDataConverter write the client's own payloads so that JsonDataConverter can read
 * them.
 */
final class GsonCompatibility {

  private GsonCompatibility() {}

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
