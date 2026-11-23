# Spring Boot starter: serve the Restate endpoint with Spring's own server

Issue: https://github.com/restatedev/sdk-java/issues/555

## Intent

The Spring Boot integration starts a Vert.x HTTP server to serve the Restate endpoint.
Spring apps then carry Vert.x and Netty only for Restate, beside the server they already run.
We replace Vert.x in the Spring integration with Spring's own web server machinery, so a Spring app
serves Restate with the container it already uses (Tomcat, Jetty, Undertow or Reactor Netty).
One implementation serves both Spring MVC and WebFlux apps.

## Goals

- `sdk-spring-boot` no longer depends on `sdk-http-vertx` nor on `vertx-core`.
- A single Restate adapter, written against Spring's `HttpHandler` contract (spring-web), serves
  MVC apps and WebFlux apps alike.
- The endpoint keeps its own port, `restate.sdk.http.port` (default 9080), so existing deployment
  URLs keep working.
- Bidirectional streaming over cleartext HTTP/2 keeps working, without the user enabling HTTP/2 on
  their main server port.
- Spring Boot 3.x, built and tested against the version in `gradle/libs.versions.toml` (3.5.16).

## Non-goals

- Spring Boot 4 support: it waits on Jackson 3 (issue #569).
  The factory selection sits in one class to keep that later move small.
- Mounting the endpoint on the app's main port and path.
- Changes to `sdk-http-vertx`: non-Spring users and `sdk-testing` keep using it.
- Running Spring Security or the app's servlet filters on the Restate port; Vert.x did not either,
  and the endpoint checks requests with `restate.sdk.endpoint.identity-key`.

## Anti-goals

- Changing the default port or the URL layout (`/discover`, `/invoke/...`, `/health`).
- Breaking apps that use the starter without any web stack.
- A thread per request, or any per-request resource that outlives the request.

## Constraints

- `sdk-core` is the sensitive module: the change must not touch it.
  `EndpointRequestHandler.processorForRequest(path, headers, loggingContextSetter, coreExecutor,
  supportsBidirectionalStreaming)` stays the integration point, and its `coreExecutor` must
  serialize the tasks it runs.
- Spring's `ServerHttpRequest` does not expose the HTTP version.
  `ServerHttpRequestDecorator.getNativeRequest(request)` returns the container's request:
  `HttpServletRequest#getProtocol()` on Tomcat, Jetty and Undertow, and reactor-netty's
  `HttpServerRequest#version()`, which returns `H2` for HTTP/2 (checked in reactor-netty 1.3.8).
- Java 17 baseline, Spotless formatting, license headers.

## Approaches

1. **One `HttpHandler` on a second web server built by a Spring Boot factory (chosen).**
   The integration builds a `ReactiveWebServerFactory` for the container on the classpath and calls
   `getWebServer(handler)` on the Restate port.
   Tomcat's and Jetty's reactive factories run the `HttpHandler` on a servlet through Spring's
   `ServletHttpHandlerAdapter`, so MVC apps need no spring-webflux.
   Trade-off: the Restate port has no app filters or Security, as today with Vert.x.
2. **A child application context on the Restate port, as Actuator does for
   `management.server.port`.**
   It brings a whole `DispatcherServlet` or WebFlux stack, the app's filters and Security to that
   port.
   Rejected: much more wiring to move the same bytes, and the SDK would fight the app's Security
   setup.
3. **A native `Servlet` for MVC plus a separate WebFlux handler.**
   Rejected: two implementations of the same adapter.

The user picked approach 1.

## Design

### `RestateHttpHandler` (new, `sdk-spring-boot`)

Implements `org.springframework.http.server.reactive.HttpHandler`; it plays the part of
`HttpEndpointRequestHandler` and its two flow adapters in `sdk-http-vertx`.

- Builds an `EndpointRequestHandler` once from the `Endpoint` bean.
- Per request:
  - bidirectional streaming is on when it is enabled in the properties and the native request
    reports HTTP/2; when the version can't be found, it is off, and Restate falls back to
    request/response mode, which works on every HTTP version;
  - calls `processorForRequest` with a `HeadersAccessor` over `request.getHeaders()`, the thread
    local logging context setter, and a serializing executor (below);
  - on `ProtocolException`, answers with its status code, `text/plain` and the message;
  - otherwise sets the status code, `content-type` from the processor and `x-restate-server`,
    then wires the request body `Flux<DataBuffer>` into the processor and the processor output into
    `response.writeAndFlushWith(...)`, so each slice goes out as soon as it is produced.
- `DataBuffer` to `Slice`: copy the readable bytes into a `ByteBuffer`, then release the buffer
  (`DataBufferUtils.release`), so pooled Netty buffers don't leak.
- `Flow` and Reactive Streams: `org.reactivestreams.FlowAdapters` (reactive-streams jar, already a
  dependency of reactor-core).
- Failures from the processor after the head is written end the response; before, they set the
  status from `ExceptionUtils.findProtocolException`, or 500, as the Vert.x adapter does.

### Serializing executor

A Reactor `Scheduler.Worker` serializes the tasks scheduled on it.
Per request, take `Schedulers.parallel().createWorker()`, pass `worker::schedule` as the executor,
and dispose the worker when the response completes, fails or is cancelled.
Core tasks don't block, since `HandlerRunner` in `sdk-api` runs user handler code on its own service
executor.

### `RestateHttpEndpointBean` (rewritten)

Keeps its name, its `SmartLifecycle` role, its public constructor
`(Endpoint, RestateHttpServerProperties)` and `actualPort()`, since tests and users reference them.
The deprecated `ApplicationContext` constructor stays as is, apart from the server it starts.

- `start()`: build the factory (below), set its port (0 stays a random port), enable HTTP/2,
  call `getWebServer(new RestateHttpHandler(...))`, then `start()`; log the actual port.
  A failed bind is logged and `running` still becomes true, as today.
- `stop()`: stop the web server without a graceful-shutdown wait; `actualPort()` returns -1 when
  stopped.
- The deprecated constructor's "no `@RestateComponent`, start nothing" path stays.

### Factory selection (one class)

Follow the app's own server: when the context holds a `ServletWebServerFactory` or
`ReactiveWebServerFactory` bean, use the container of that bean's type (Tomcat, Jetty, Undertow or
Netty).
Without one (an app with no web stack), pick the first container on the classpath, in this order:
Tomcat, Jetty, Undertow, Reactor Netty.
A WebFlux app on Netty that also carries `tomcat-embed-core` through another library then still
serves Restate with Netty.
Always build a fresh factory: reusing the app's factory bean would mean changing the port of the
bean the main server was built from.
The container classes are `compileOnly` in `sdk-spring-boot` and every reference to them sits behind
a `ClassUtils.isPresent` check, so a missing container never throws `NoClassDefFoundError`.
(Builder check, settled by Claude.)

HTTP/2: `factory.setHttp2(http2)` with `enabled = true`.
Without SSL, Boot sets up cleartext HTTP/2 (h2c) on Tomcat and Reactor Netty.
Jetty needs `jetty-http2-server` for h2c: enable HTTP/2 on Jetty only when that class is present,
otherwise serve HTTP/1.1, which falls back to request/response mode.
Undertow and Jetty without that jar may serve only HTTP/1.1: invocations still work in
request/response mode, and `start()` logs a warning naming the container.
Only Tomcat and Reactor Netty must prove h2c in tests.
(Builder check, settled by Claude.)

### Dependencies

- `sdk-spring-boot`: remove `sdk-http-vertx` and `vertx-core`; add `sdk-core` (until now it came
  through `sdk-http-vertx`), `spring-web` (already there through `spring-boot-starter-json`) and
  `reactor-core`.
- Starters (Java and Kotlin): add `spring-boot-starter-reactor-netty`, so an app with no web stack
  still has a server for port 9080.
  This does not turn a plain app into a web app: Boot deduces the application type from
  `DispatcherServlet`, `DispatcherHandler` and the servlet API, and reactor-netty brings none of
  them.
  Tomcat, Jetty and Undertow come before Netty in the selection, so MVC apps don't end up running
  two containers.

### Properties

- `port`, `disableBidirectionalStreaming`: unchanged.
- `eventLoops`: deprecated and ignored, with a warning in the log when set; the container owns its
  threads now.

### Tests

The existing tests stay and must pass unchanged:
`RestateHttpEndpointBeanTest`, `RestateHttpEndpointBeanDefaultsTest` (Java starter) and
`RestateHttpEndpointBeanTest` (Kotlin starter).
They call `/discover` over HTTP on `actualPort()`.
New tests in the Java starter:

- a test app with `spring-boot-starter-web` (Tomcat), calling `/discover` over HTTP/2 prior
  knowledge (`HttpClient` with `Version.HTTP_2` on `http://`) and asserting that the manifest's
  `protocolMode` is `BIDI_STREAM`;
  `EndpointRequestHandler` computes that field per request from the detected HTTP version, so the
  assertion fails if version detection breaks and the endpoint silently falls back;
- the same call over HTTP/1.1 asserting `REQUEST_RESPONSE`;
- a test app with `spring-boot-starter-webflux` (Netty), with the same checks;
- an end-to-end test: a Restate server in Testcontainers registers the Spring endpoint's port
  (`Testcontainers.exposeHostPorts`, then a deployment through the admin API) and a client invokes a
  handler.
  `SdkTestingIntegrationTest` does not cover this, because `@RestateTest` serves the `@BindService`
  beans through its own Vert.x server (`RestateRunner`), never through `RestateHttpEndpointBean`.

## Left to the builder

- Package-private or public visibility of `RestateHttpHandler`: public would let users mount it
  themselves, which nobody asked for, so package-private unless a test needs more.
- How the new test apps get their own classpath (a Gradle test suite per stack, or a separate
  test-only module).
- The exact log messages.
