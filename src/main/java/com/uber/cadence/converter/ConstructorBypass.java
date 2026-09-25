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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides constructors that create an instance without running any constructor or field
 * initializer of the instantiated class, as Java serialization does. {@link JsonDataConverter}
 * creates such instances without running their constructors too (Gson uses {@code
 * sun.misc.Unsafe}). Only a constructor of a superclass runs: {@code Throwable(String)} for
 * throwables, so that the message and the stack trace, cause and suppressed exceptions are properly
 * initialized. Other classes are not supported.
 *
 * <p>Uses {@code sun.reflect.ReflectionFactory} (module jdk.unsupported, like {@code
 * sun.misc.Unsafe}), which is reached reflectively because a compile time reference to an internal
 * API fails the build.
 */
final class ConstructorBypass {

  private static final Logger log = LoggerFactory.getLogger(ConstructorBypass.class);

  private static final Object REFLECTION_FACTORY;
  private static final Method NEW_CONSTRUCTOR_FOR_SERIALIZATION;
  private static final Constructor<Throwable> THROWABLE_CONSTRUCTOR;

  static {
    Object factory = null;
    Method newConstructor = null;
    Constructor<Throwable> throwableConstructor = null;
    try {
      Class<?> factoryClass = Class.forName("sun.reflect.ReflectionFactory");
      factory = factoryClass.getMethod("getReflectionFactory").invoke(null);
      newConstructor =
          factoryClass.getMethod("newConstructorForSerialization", Class.class, Constructor.class);
      throwableConstructor = Throwable.class.getConstructor(String.class);
    } catch (Exception | LinkageError e) {
      log.warn(
          "sun.reflect.ReflectionFactory is not available. Exceptions without a usable constructor "
              + "cannot be deserialized by JacksonDataConverter.",
          e);
      factory = null;
    }
    REFLECTION_FACTORY = factory;
    NEW_CONSTRUCTOR_FOR_SERIALIZATION = newConstructor;
    THROWABLE_CONSTRUCTOR = throwableConstructor;
  }

  // Each call to newConstructorForSerialization generates a new accessor class, so the result is
  // cached per class for the lifetime of that class.
  private static final ClassValue<Constructor<?>> CONSTRUCTORS =
      new ClassValue<Constructor<?>>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
          // Interfaces are abstract too. Callers never pass array or primitive types.
          if (REFLECTION_FACTORY == null || Modifier.isAbstract(type.getModifiers())) {
            return null;
          }
          try {
            return (Constructor<?>)
                NEW_CONSTRUCTOR_FOR_SERIALIZATION.invoke(
                    REFLECTION_FACTORY, type, THROWABLE_CONSTRUCTOR);
          } catch (Exception | LinkageError e) {
            log.debug("Cannot create a constructor bypassing ones of {}", type.getName(), e);
            return null;
          }
        }
      };

  private ConstructorBypass() {}

  /**
   * Returns a constructor that creates an instance of {@code type} without running its
   * constructors, or null if that is not possible (interface, abstract class, or runtime without
   * {@code sun.reflect.ReflectionFactory}). For a throwable the constructor takes the message as
   * its only argument; other types are not supported.
   */
  static Constructor<?> constructorFor(Class<?> type) {
    return CONSTRUCTORS.get(type);
  }
}
