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

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes and reads Gson's tree types (JsonElement, JsonObject, JsonArray, JsonPrimitive and
 * JsonNull) as the JSON they represent, as JsonDataConverter does.
 */
final class GsonJsonElementSerialization {

  private static final Pattern JSON_NUMBER =
      Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?");

  private GsonJsonElementSerialization() {}

  static final class Serializer extends StdSerializer<JsonElement> {
    private static final long serialVersionUID = 1L;

    Serializer() {
      super(JsonElement.class);
    }

    @Override
    public void serialize(JsonElement value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      write(value, gen);
    }

    /** Written as the JSON it represents, also with default typing. */
    @Override
    public void serializeWithType(
        JsonElement value, JsonGenerator gen, SerializerProvider provider, TypeSerializer typeSer)
        throws IOException {
      write(value, gen);
    }

    @Override
    public boolean isEmpty(SerializerProvider provider, JsonElement value) {
      return value == null || value.isJsonNull();
    }

    private static void write(JsonElement value, JsonGenerator gen) throws IOException {
      if (value == null || value.isJsonNull()) {
        gen.writeNull();
      } else if (value.isJsonObject()) {
        gen.writeStartObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
          gen.writeFieldName(entry.getKey());
          write(entry.getValue(), gen);
        }
        gen.writeEndObject();
      } else if (value.isJsonArray()) {
        gen.writeStartArray();
        for (JsonElement element : value.getAsJsonArray()) {
          write(element, gen);
        }
        gen.writeEndArray();
      } else {
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
          gen.writeBoolean(primitive.getAsBoolean());
        } else if (primitive.isNumber()) {
          writeNumber(primitive.getAsNumber(), gen);
        } else {
          gen.writeString(primitive.getAsString());
        }
      }
    }

    private static void writeNumber(Number number, JsonGenerator gen) throws IOException {
      if (number instanceof Integer
          || number instanceof Long
          || number instanceof Short
          || number instanceof Byte) {
        gen.writeNumber(number.longValue());
      } else if (number instanceof Double) {
        gen.writeNumber(number.doubleValue());
      } else if (number instanceof Float) {
        gen.writeNumber(number.floatValue());
      } else if (number instanceof BigInteger) {
        gen.writeNumber((BigInteger) number);
      } else if (number instanceof BigDecimal) {
        gen.writeNumber((BigDecimal) number);
      } else {
        // Such as the LazilyParsedNumber of a parsed JsonPrimitive, which keeps the text it was
        // read from. That text is written as is when it is a JSON number.
        String text = number.toString();
        if (!JSON_NUMBER.matcher(text).matches()) {
          gen.writeNumber(number.doubleValue());
        } else if (!(gen instanceof TokenBuffer)) {
          gen.writeNumber(text);
        } else if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) {
          // A TokenBuffer, as used by valueToTree, would keep the text as a floating point number.
          BigInteger integer = new BigInteger(text);
          if (integer.bitLength() < 64) {
            gen.writeNumber(integer.longValue());
          } else {
            gen.writeNumber(integer);
          }
        } else {
          gen.writeNumber(new BigDecimal(text));
        }
      }
    }
  }

  /** Routes JsonElement and its subclasses to {@link Deserializer}. */
  static final class Deserializers extends com.fasterxml.jackson.databind.deser.Deserializers.Base {
    @Override
    public JsonDeserializer<?> findBeanDeserializer(
        JavaType type, DeserializationConfig config, BeanDescription beanDesc) {
      Class<?> raw = type.getRawClass();
      return JsonElement.class.isAssignableFrom(raw) ? new Deserializer(raw) : null;
    }
  }

  static final class Deserializer extends StdDeserializer<JsonElement> {
    private static final long serialVersionUID = 1L;

    Deserializer(Class<?> type) {
      super(type);
    }

    @Override
    public JsonElement deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonElement result = read(p, ctxt);
      if (!handledType().isInstance(result)) {
        return (JsonElement) ctxt.handleUnexpectedToken(handledType(), p.currentToken(), p, null);
      }
      return result;
    }

    /** Read as the JSON it represents, also with default typing. */
    @Override
    public Object deserializeWithType(
        JsonParser p, DeserializationContext ctxt, TypeDeserializer typeDeserializer)
        throws IOException {
      return deserialize(p, ctxt);
    }

    /** A JSON null is JsonNull for a JsonElement, as in JsonDataConverter, otherwise null. */
    @Override
    public JsonElement getNullValue(DeserializationContext ctxt) {
      return handledType() == JsonElement.class || handledType() == JsonNull.class
          ? JsonNull.INSTANCE
          : null;
    }

    @Override
    public boolean isCachable() {
      return true;
    }

    private JsonElement read(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonToken token = p.currentToken();
      if (token == null) {
        return JsonNull.INSTANCE;
      }
      switch (token) {
        case START_OBJECT:
        case FIELD_NAME:
          {
            JsonObject object = new JsonObject();
            String name = token == JsonToken.START_OBJECT ? p.nextFieldName() : p.currentName();
            for (; name != null; name = p.nextFieldName()) {
              p.nextToken();
              object.add(name, read(p, ctxt));
            }
            return object;
          }
        case END_OBJECT:
          // The empty object a delegating creator is given.
          return new JsonObject();
        case START_ARRAY:
          {
            JsonArray array = new JsonArray();
            while (p.nextToken() != JsonToken.END_ARRAY) {
              array.add(read(p, ctxt));
            }
            return array;
          }
        case VALUE_STRING:
          return new JsonPrimitive(p.getText());
        case VALUE_NUMBER_INT:
        case VALUE_NUMBER_FLOAT:
          {
            // Parsed by Gson, so that the number keeps its digits as in JsonDataConverter.
            String text = p.getText();
            return JSON_NUMBER.matcher(text).matches()
                ? com.google.gson.JsonParser.parseString(text)
                : new JsonPrimitive(p.getNumberValue());
          }
        case VALUE_TRUE:
          return new JsonPrimitive(true);
        case VALUE_FALSE:
          return new JsonPrimitive(false);
        case VALUE_NULL:
          return JsonNull.INSTANCE;
        case VALUE_EMBEDDED_OBJECT:
          {
            Object embedded = p.getEmbeddedObject();
            if (embedded == null) {
              return JsonNull.INSTANCE;
            }
            if (embedded instanceof byte[]) {
              return new JsonPrimitive(
                  Base64Variants.getDefaultVariant().encode((byte[]) embedded));
            }
            return (JsonElement) ctxt.handleUnexpectedToken(handledType(), token, p, null);
          }
        default:
          return (JsonElement) ctxt.handleUnexpectedToken(handledType(), token, p, null);
      }
    }
  }
}
