// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restate.sdk.Context;
import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.annotation.Name;
import dev.restate.sdk.annotation.Service;
import dev.restate.sdk.core.EndpointRequestHandler;
import dev.restate.sdk.core.generated.manifest.EndpointManifestSchema;
import dev.restate.sdk.endpoint.Endpoint;
import dev.restate.sdk.version.Version;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

class RestateHttpHandlerTest {

  @Service
  @Name("greeter")
  public static class Greeter {

    @Handler
    public String greet(Context ctx, String person) {
      return "Hello " + person;
    }
  }

  private final Endpoint endpoint = Endpoint.builder().bind(new Greeter()).build();

  @Test
  void discoverWithoutNativeRequest() throws Exception {
    RestateHttpHandler handler = new RestateHttpHandler(endpoint, true);
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/discover")
            .header("accept", "application/vnd.restate.endpointmanifest.v4+json")
            .build();
    MockServerHttpResponse response = new MockServerHttpResponse();

    handler.handle(request, response).block();

    // The mock request has no native request, so the HTTP version is unknown: request/response.
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getFirst("x-restate-server"))
        .isEqualTo(Version.X_RESTATE_SERVER);
    var manifest =
        new ObjectMapper().readValue(response.getBodyAsString(), EndpointManifestSchema.class);
    assertThat(manifest.getProtocolMode())
        .isEqualTo(EndpointManifestSchema.ProtocolMode.REQUEST_RESPONSE);
    assertThat(manifest.getServices())
        .extracting(dev.restate.sdk.core.generated.manifest.Service::getName)
        .containsExactly("greeter");
  }

  @Test
  void unknownPath() {
    RestateHttpHandler handler = new RestateHttpHandler(endpoint, true);
    MockServerHttpRequest request = MockServerHttpRequest.get("/nope").build();
    MockServerHttpResponse response = new MockServerHttpResponse();

    handler.handle(request, response).block();

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
    assertThat(response.getHeaders().getFirst("x-restate-server"))
        .isEqualTo(Version.X_RESTATE_SERVER);
  }

  @Test
  void releasesRequestBuffers() {
    RestateHttpHandler handler = new RestateHttpHandler(endpoint, true);
    NettyDataBufferFactory bufferFactory =
        new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
    List<NettyDataBuffer> chunks =
        List.of(
            (NettyDataBuffer)
                bufferFactory.wrap(Unpooled.wrappedBuffer("Wor".getBytes(StandardCharsets.UTF_8))),
            (NettyDataBuffer)
                bufferFactory.wrap(Unpooled.wrappedBuffer("ld".getBytes(StandardCharsets.UTF_8))));
    MockServerHttpRequest request =
        MockServerHttpRequest.post("/invoke/greeter/greet").body(Flux.fromIterable(chunks)).build();
    MockServerHttpResponse response = new MockServerHttpResponse();

    handler.handle(request, response).block();

    assertThat(chunks).allSatisfy(b -> assertThat(b.getNativeBuffer().refCnt()).isZero());
  }

  @Test
  void cancelDisposesWorker() {
    AtomicBoolean disposed = new AtomicBoolean(false);
    Scheduler.Worker delegate = Schedulers.parallel().createWorker();
    Scheduler.Worker recordingWorker =
        new Scheduler.Worker() {
          @Override
          public Disposable schedule(Runnable task) {
            return delegate.schedule(task);
          }

          @Override
          public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            return delegate.schedule(task, delay, unit);
          }

          @Override
          public Disposable schedulePeriodically(
              Runnable task, long initialDelay, long period, TimeUnit unit) {
            return delegate.schedulePeriodically(task, initialDelay, period, unit);
          }

          @Override
          public void dispose() {
            disposed.set(true);
            delegate.dispose();
          }

          @Override
          public boolean isDisposed() {
            return delegate.isDisposed();
          }
        };
    RestateHttpHandler handler = new RestateHttpHandler(endpoint, true, () -> recordingWorker);
    MockServerHttpRequest request =
        MockServerHttpRequest.post("/invoke/greeter/greet").body(Flux.never()).build();
    MockServerHttpResponse response = new MockServerHttpResponse();

    Disposable subscription = handler.handle(request, response).subscribe();
    subscription.dispose();

    assertThat(disposed).isTrue();
  }

  @Test
  void logContextDoesNotLeakAcrossTasksOnTheSameWorker() throws Exception {
    Scheduler.Worker worker = Schedulers.parallel().createWorker();
    try {
      RestateHttpHandler handler = new RestateHttpHandler(endpoint, true, () -> worker);
      NettyDataBufferFactory bufferFactory =
          new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
      MockServerHttpRequest request =
          MockServerHttpRequest.post("/invoke/greeter/greet")
              .header("x-restate-invocation-id", "inv-1")
              .body(
                  Flux.just(
                      bufferFactory.wrap(
                          Unpooled.wrappedBuffer("World".getBytes(StandardCharsets.UTF_8)))))
              .build();

      handler.handle(request, new MockServerHttpResponse()).block();

      // A plain task on the same worker thread must not see the previous request's context.
      AtomicReference<String> seen = new AtomicReference<>("unset");
      CountDownLatch latch = new CountDownLatch(1);
      worker.schedule(
          () -> {
            seen.set(
                ThreadContext.get(EndpointRequestHandler.LoggingContextSetter.INVOCATION_ID_KEY));
            latch.countDown();
          });
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(seen.get()).isNull();
    } finally {
      worker.dispose();
    }
  }
}
