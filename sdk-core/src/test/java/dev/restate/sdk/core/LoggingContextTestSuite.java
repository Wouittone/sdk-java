// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.core;

import static dev.restate.sdk.core.legacy.ProtoUtils.*;

import com.google.protobuf.ByteString;
import dev.restate.sdk.core.TestDefinitions.TestDefinition;
import dev.restate.sdk.core.TestDefinitions.TestInvocationBuilder;
import dev.restate.sdk.core.TestDefinitions.TestSuite;
import java.util.stream.Stream;

/**
 * Verifies that the Restate logging context reaches the SLF4J MDC on the thread running the
 * handler, and that the invocation status is kept up to date there.
 *
 * <p>The status matters: it is the one context value that changes while the handler runs, and the
 * MDC can only hold plain strings, so it has to be re-read every time the handler resumes. A
 * regression here silently pins user log statements to the status the invocation had when the
 * handler started, which breaks the documented "drop log statements emitted during replay" filter.
 */
public abstract class LoggingContextTestSuite implements TestSuite {

  /**
   * Handler returning {@code invocationId;target;statusBeforeReplayedGet;statusAfterReplayedGet},
   * each value read from the SLF4J MDC on the handler's own thread.
   */
  protected abstract TestInvocationBuilder reportLoggingContext();

  @Override
  public Stream<TestDefinition> definitions() {
    String debugId = "my-debug-id";
    ByteString id = ByteString.copyFromUtf8(debugId);

    return Stream.of(
        reportLoggingContext()
            .withInput(
                startMessage(3).setDebugId(debugId).setId(id),
                inputCmd(),
                getLazyStateCmd(1, "STATE"),
                getLazyStateCompletion(1, "Francesco"))
            .onlyBidiStream()
            .expectingOutput(
                outputCmd("my-debug-id;ReportLoggingContext/run;REPLAYING;PROCESSING"), END_MESSAGE)
            .named(
                "Id and target are installed, and the status is refreshed when the handler resumes"));
  }
}
