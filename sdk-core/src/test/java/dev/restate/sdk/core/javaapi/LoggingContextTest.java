// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.core.javaapi;

import static dev.restate.sdk.core.javaapi.JavaAPITests.testDefinitionForVirtualObject;

import dev.restate.sdk.common.StateKey;
import dev.restate.sdk.core.LoggingContextTestSuite;
import dev.restate.sdk.core.TestDefinitions.TestInvocationBuilder;
import dev.restate.sdk.core.TestSerdes;
import dev.restate.sdk.endpoint.definition.LoggingContext;
import dev.restate.serde.Serde;
import org.slf4j.MDC;

public class LoggingContextTest extends LoggingContextTestSuite {

  @Override
  protected TestInvocationBuilder reportLoggingContext() {
    return testDefinitionForVirtualObject(
        "ReportLoggingContext",
        Serde.VOID,
        TestSerdes.STRING,
        (ctx, unused) -> {
          String statusBefore = MDC.get(LoggingContext.INVOCATION_STATUS_KEY);

          // This get is replayed from the journal, and it is the last entry: the state machine runs
          // out of entries to replay while this thread is parked inside the await, so the status
          // must have moved on by the time we resume here.
          ctx.get(StateKey.of("STATE", String.class));

          String statusAfter = MDC.get(LoggingContext.INVOCATION_STATUS_KEY);

          return String.join(
              ";",
              MDC.get(LoggingContext.INVOCATION_ID_KEY),
              MDC.get(LoggingContext.INVOCATION_TARGET_KEY),
              statusBefore,
              statusAfter);
        });
  }
}
