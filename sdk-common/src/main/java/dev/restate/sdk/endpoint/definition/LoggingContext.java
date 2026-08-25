// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.endpoint.definition;

import dev.restate.sdk.common.HandlerRequest;
import dev.restate.sdk.common.InvocationId;
import org.slf4j.MDC;

/**
 * Propagates the Restate invocation context into the SLF4J {@link MDC}, so that log statements
 * emitted by handler code carry the {@code restateInvocation*} keys.
 *
 * <p>Handler code does not run on the thread that drives the state machine, so the MDC has to be
 * installed on whichever thread the handler is executing on. Implementations of {@link
 * HandlerRunner} are responsible for calling {@link #install(HandlerContext)} before entering user
 * code and {@link #remove()} on the way out.
 *
 * <p>{@link #INVOCATION_ID_KEY} and {@link #INVOCATION_TARGET_KEY} are fixed for the whole
 * invocation, but {@link #INVOCATION_STATUS_KEY} is not: it flips from {@code REPLAYING} to {@code
 * PROCESSING} while the handler runs. The MDC only holds plain strings, so it cannot be evaluated
 * lazily at log time — the status must instead be re-read whenever the handler could have observed
 * a change, which is every point at which it resumes from a suspension. That is what {@link
 * #refreshStatus()} is for; skipping it would pin every user log line to the status the invocation
 * had when the handler started, breaking the usual "drop log statements emitted during replay"
 * filter.
 */
public final class LoggingContext {

  /** Invocation identifier, e.g. {@code inv_1aiqX0vFEFvw3srUMHUZLNjkhGx2AAFq5U}. */
  public static final String INVOCATION_ID_KEY = "restateInvocationId";

  /** Invocation target, e.g. {@code counter.Counter/Add}. */
  public static final String INVOCATION_TARGET_KEY = "restateInvocationTarget";

  /** One of {@code WAITING_START}, {@code REPLAYING}, {@code PROCESSING}, {@code CLOSED}. */
  public static final String INVOCATION_STATUS_KEY = "restateInvocationStatus";

  private LoggingContext() {}

  /**
   * Install the logging context of {@code handlerContext} on the current thread, overwriting any
   * previously installed one. Safe to call repeatedly on the same thread.
   */
  public static void install(HandlerContext handlerContext) {
    HandlerRequest request = handlerContext.request();

    InvocationId invocationId = request.invocationId();
    if (invocationId != null) {
      MDC.put(INVOCATION_ID_KEY, invocationId.toString());
    }
    // serviceName/handlerName are nullable on HandlerRequest, so only report a complete target.
    if (request.serviceName() != null && request.handlerName() != null) {
      MDC.put(INVOCATION_TARGET_KEY, request.serviceName() + "/" + request.handlerName());
    }
    putStatus(handlerContext);
  }

  /**
   * Re-read the invocation status of the handler context bound to the current thread, if any. Cheap
   * enough to call on every suspension point: the status changes at most a handful of times per
   * invocation, so this is a map lookup and a comparison in the common case.
   */
  public static void refreshStatus() {
    HandlerContext handlerContext = HandlerRunner.HANDLER_CONTEXT_THREAD_LOCAL.get();
    if (handlerContext != null) {
      putStatus(handlerContext);
    }
  }

  /** Remove the Restate keys from the current thread's MDC. */
  public static void remove() {
    MDC.remove(INVOCATION_ID_KEY);
    MDC.remove(INVOCATION_TARGET_KEY);
    MDC.remove(INVOCATION_STATUS_KEY);
  }

  private static void putStatus(HandlerContext handlerContext) {
    String status = handlerContext.loggingInvocationStatus();
    // Avoid touching the MDC when nothing changed: MDC.put mutates a copy-on-write map on some
    // bindings, and this runs on every suspension point.
    if (status != null && !status.equals(MDC.get(INVOCATION_STATUS_KEY))) {
      MDC.put(INVOCATION_STATUS_KEY, status);
    }
  }
}
