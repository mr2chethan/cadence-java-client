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

import com.uber.cadence.common.RetryOptions;

/**
 * The payloads that the client records for itself with the configured data converter, such as the
 * headers of version, mutable side effect and local activity markers, the retry options of {@code
 * Workflow.retry} and the data of the shadowing activity.
 */
final class ClientPayloads {

  private ClientPayloads() {}

  /** Whether values of the type are client payloads. Exceptions are not. */
  static boolean isClientPayload(Class<?> type) {
    return type == RetryOptions.class
        || (type.getName().startsWith("com.uber.cadence.internal.")
            && !Throwable.class.isAssignableFrom(type));
  }
}
