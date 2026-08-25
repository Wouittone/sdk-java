// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.core.kotlinapi

import dev.restate.sdk.common.StateKey
import dev.restate.sdk.core.LoggingContextTestSuite
import dev.restate.sdk.core.TestDefinitions.TestInvocationBuilder
import dev.restate.sdk.core.TestSerdes
import dev.restate.sdk.core.kotlinapi.KotlinAPITests.Companion.testDefinitionForVirtualObject
import dev.restate.sdk.endpoint.definition.LoggingContext
import org.slf4j.MDC

class LoggingContextTest : LoggingContextTestSuite() {

  override fun reportLoggingContext(): TestInvocationBuilder =
      testDefinitionForVirtualObject("ReportLoggingContext") { ctx, _: Unit ->
        val statusBefore = MDC.get(LoggingContext.INVOCATION_STATUS_KEY)

        // This get is replayed from the journal, and it is the last entry: the state machine runs
        // out of entries to replay while this coroutine is suspended, so the status must have moved
        // on by the time it resumes here — possibly on a different thread, which is the whole point
        // of re-installing the MDC on every resume.
        ctx.get(StateKey.of("STATE", TestSerdes.STRING))

        val statusAfter = MDC.get(LoggingContext.INVOCATION_STATUS_KEY)

        listOf(
                MDC.get(LoggingContext.INVOCATION_ID_KEY),
                MDC.get(LoggingContext.INVOCATION_TARGET_KEY),
                statusBefore,
                statusAfter,
            )
            .joinToString(";")
      }
}
