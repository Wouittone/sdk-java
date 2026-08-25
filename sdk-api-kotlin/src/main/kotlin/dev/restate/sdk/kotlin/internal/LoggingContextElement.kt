// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.kotlin.internal

import dev.restate.sdk.endpoint.definition.HandlerContext
import dev.restate.sdk.endpoint.definition.LoggingContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * [ThreadContextElement] that installs the Restate logging context (SLF4J MDC) each time the
 * coroutine resumes on a (possibly different) thread.
 *
 * A coroutine handler hops threads across suspensions, so the MDC cannot simply be set once. Doing
 * it on every resume also keeps `restateInvocationStatus` current for free: the status only changes
 * while the coroutine is suspended (the state machine advances on the core thread), so re-reading
 * it on resume is exactly the right granularity.
 */
internal class LoggingContextElement(private val handlerContext: HandlerContext) :
    ThreadContextElement<Unit> {

  companion object Key : CoroutineContext.Key<LoggingContextElement>

  override val key: CoroutineContext.Key<*> = Key

  override fun updateThreadContext(context: CoroutineContext) {
    LoggingContext.install(handlerContext)
  }

  override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
    LoggingContext.remove()
  }
}
