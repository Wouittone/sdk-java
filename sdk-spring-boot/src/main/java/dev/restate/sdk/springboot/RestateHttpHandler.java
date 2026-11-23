// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot;

import dev.restate.common.Slice;
import dev.restate.sdk.core.EndpointRequestHandler;
import dev.restate.sdk.core.ExceptionUtils;
import dev.restate.sdk.core.ProtocolException;
import dev.restate.sdk.core.RequestProcessor;
import dev.restate.sdk.endpoint.Endpoint;
import dev.restate.sdk.endpoint.HeadersAccessor;
import dev.restate.sdk.version.Version;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.logging.log4j.ThreadContext;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.ClassUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Spring {@link HttpHandler} serving the Restate {@link Endpoint}, playing the part of {@code
 * HttpEndpointRequestHandler} from {@code sdk-http-vertx}. It bridges Spring's reactive request and
 * response to the SDK core's {@link java.util.concurrent.Flow} processor, so it serves Spring MVC
 * apps (through {@code ServletHttpHandlerAdapter}) and WebFlux apps alike.
 */
final class RestateHttpHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(RestateHttpHandler.class);

  private static final String X_RESTATE_SERVER = "x-restate-server";

  private static final boolean SERVLET_API_PRESENT =
      ClassUtils.isPresent(
          "jakarta.servlet.http.HttpServletRequest", RestateHttpHandler.class.getClassLoader());
  private static final boolean REACTOR_NETTY_PRESENT =
      ClassUtils.isPresent(
          "reactor.netty.http.server.HttpServerRequest", RestateHttpHandler.class.getClassLoader());

  private final EndpointRequestHandler endpoint;
  private final boolean enableBidirectionalStreaming;
  private final Supplier<Scheduler.Worker> workerSupplier;

  RestateHttpHandler(Endpoint endpoint, boolean enableBidirectionalStreaming) {
    this(endpoint, enableBidirectionalStreaming, Schedulers.parallel()::createWorker);
  }

  RestateHttpHandler(
      Endpoint endpoint,
      boolean enableBidirectionalStreaming,
      Supplier<Scheduler.Worker> workerSupplier) {
    this.endpoint = EndpointRequestHandler.create(endpoint);
    this.enableBidirectionalStreaming = enableBidirectionalStreaming;
    this.workerSupplier = workerSupplier;
  }

  @Override
  public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
    Scheduler.Worker worker = workerSupplier.get();
    Map<String, String> logContext = new ConcurrentHashMap<>();
    EndpointRequestHandler.LoggingContextSetter loggingContextSetter =
        (key, value) -> {
          logContext.put(key, value);
          ThreadContext.put(key, value);
        };
    // The core executor must serialize the tasks it runs: a Reactor Scheduler.Worker does that.
    Executor coreExecutor =
        task ->
            worker.schedule(
                () -> {
                  ThreadContext.putAll(logContext);
                  try {
                    task.run();
                  } finally {
                    ThreadContext.removeAll(logContext.keySet());
                  }
                });

    RequestProcessor processor;
    try {
      processor =
          this.endpoint.processorForRequest(
              request.getPath().value(),
              headersAccessor(request.getHeaders()),
              loggingContextSetter,
              coreExecutor,
              this.enableBidirectionalStreaming && isHttp2(request));
    } catch (ProtocolException e) {
      LOG.warn("Error when handling the request", e);
      response.setStatusCode(HttpStatusCode.valueOf(e.getCode()));
      response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
      response.getHeaders().set(X_RESTATE_SERVER, Version.X_RESTATE_SERVER);
      String message = e.getMessage() == null ? "" : e.getMessage();
      return response
          .writeWith(
              Mono.just(response.bufferFactory().wrap(message.getBytes(StandardCharsets.UTF_8))))
          .doFinally(signal -> worker.dispose());
    }

    response.setStatusCode(HttpStatusCode.valueOf(processor.statusCode()));
    response.getHeaders().setContentType(MediaType.parseMediaType(processor.responseContentType()));
    response.getHeaders().set(X_RESTATE_SERVER, Version.X_RESTATE_SERVER);

    // Wire the request body into the processor, copying the bytes out of the (possibly pooled)
    // DataBuffers and releasing them right away.
    Flux<Slice> input =
        request
            .getBody()
            .map(
                buffer -> {
                  ByteBuffer copy = ByteBuffer.allocate(buffer.readableByteCount());
                  copy.put(buffer.asByteBuffer());
                  copy.flip();
                  DataBufferUtils.release(buffer);
                  return Slice.wrap(copy);
                });
    InputSubscription inputSubscription = new InputSubscription(processor);
    input.subscribe(inputSubscription);

    // Wire the processor output into the response, so each slice goes out as soon as it is
    // produced. The Slice is copied into a response buffer so a native-memory slice can be freed
    // safely.
    Flux<Publisher<DataBuffer>> output =
        Flux.from(FlowAdapters.toPublisher(processor))
            .map(
                slice -> {
                  DataBuffer buffer =
                      response.bufferFactory().allocateBuffer(slice.readableBytes());
                  buffer.write(slice.asReadOnlyByteBuffer());
                  return Mono.just(buffer);
                });

    return response
        .writeAndFlushWith(output)
        .onErrorResume(
            e -> {
              if (!response.isCommitted()) {
                Optional<ProtocolException> protocolException =
                    ExceptionUtils.findProtocolException(e);
                response.setStatusCode(
                    HttpStatusCode.valueOf(
                        protocolException.map(ProtocolException::getCode).orElse(500)));
              }
              LOG.warn("Error when handling the request", e);
              return Mono.empty();
            })
        .doFinally(
            signal -> {
              Subscription subscription = inputSubscription.subscription;
              if (subscription != null) {
                subscription.cancel();
              }
              worker.dispose();
            });
  }

  /**
   * A worker supplier running each request's worker on its own virtual thread, for {@code
   * restate.sdk.http.virtualThreads=true}. Loaded reflectively so the SDK still compiles and runs
   * on Java 17.
   */
  static Supplier<Scheduler.Worker> virtualThreadWorkerSupplier() {
    ThreadFactory threadFactory = virtualThreadFactory();
    return () -> {
      ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
      Scheduler.Worker delegate = Schedulers.fromExecutorService(executor).createWorker();
      return new Scheduler.Worker() {
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
          delegate.dispose();
          executor.shutdownNow();
        }

        @Override
        public boolean isDisposed() {
          return delegate.isDisposed();
        }
      };
    };
  }

  private static ThreadFactory virtualThreadFactory() {
    try {
      Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
      return (ThreadFactory) builder.getClass().getMethod("factory").invoke(builder);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "restate.sdk.http.virtualThreads=true requires Java 21+ (virtual threads)", e);
    }
  }

  private static HeadersAccessor headersAccessor(HttpHeaders headers) {
    return new HeadersAccessor() {
      @Override
      public Iterable<String> keys() {
        return headers.keySet();
      }

      @Override
      public @Nullable String get(String key) {
        return headers.getFirst(key);
      }
    };
  }

  /**
   * Spring's {@link ServerHttpRequest} does not expose the HTTP version. The native request does:
   * {@code HttpServletRequest#getProtocol()} on servlet containers, {@code
   * HttpServerRequest#version()} on Reactor Netty. When the version can't be found, bidirectional
   * streaming stays off and Restate falls back to request/response mode, which works on every HTTP
   * version.
   */
  private static boolean isHttp2(ServerHttpRequest request) {
    final Object nativeRequest;
    try {
      nativeRequest = ServerHttpRequestDecorator.getNativeRequest(request);
    } catch (RuntimeException e) {
      return false;
    }
    if (nativeRequest == null) {
      return false;
    }
    if (SERVLET_API_PRESENT
        && nativeRequest instanceof jakarta.servlet.http.HttpServletRequest servletRequest) {
      return "HTTP/2.0".equals(servletRequest.getProtocol());
    }
    if (REACTOR_NETTY_PRESENT
        && nativeRequest instanceof reactor.netty.http.server.HttpServerRequest nettyRequest) {
      Object version = nettyRequest.version();
      // reactor-netty reports H2 (or netty's HTTP/2.0) for HTTP/2 connections.
      return version != null
          && ("H2".equals(version.toString()) || "HTTP/2.0".equals(version.toString()));
    }
    return false;
  }

  /** Delegates to the processor as a reactive {@link Subscriber} and remembers the subscription. */
  private static final class InputSubscription implements Subscriber<Slice> {

    private final Subscriber<Slice> delegate;
    private volatile @Nullable Subscription subscription;

    private InputSubscription(RequestProcessor processor) {
      this.delegate = FlowAdapters.toSubscriber(processor);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
      delegate.onSubscribe(subscription);
    }

    @Override
    public void onNext(Slice slice) {
      delegate.onNext(slice);
    }

    @Override
    public void onError(Throwable throwable) {
      delegate.onError(throwable);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }
  }
}
