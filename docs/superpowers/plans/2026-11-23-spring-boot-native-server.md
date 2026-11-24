# Spring Boot native server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Spring Boot integration serves the Restate endpoint on `restate.sdk.http.port` with a web
server built by Spring Boot's own factories, and no longer depends on Vert.x.

**Architecture:** One Spring `HttpHandler` (`RestateHttpHandler`) bridges Spring's reactive request
and response to the SDK core's `Flow` processor.
`RestateHttpEndpointBean` builds a `ConfigurableReactiveWebServerFactory` for the app's container
(`RestateWebServerFactories`), with cleartext HTTP/2, and runs the handler on it.
Tomcat and Jetty run the handler on a servlet through Spring's `ServletHttpHandlerAdapter`, so MVC
and WebFlux apps share the same code.

**Tech Stack:** Java 17, Spring Boot 3.5.16 (spring-web, reactor-core, reactor-netty, Tomcat), JUnit 5,
AssertJ, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-23-spring-boot-native-server-design.md`

## Global Constraints

- Spring Boot 3.x only, built and tested against `spring-boot = '3.5.16'` from `gradle/libs.versions.toml`.
- Do not modify `sdk-core` or `sdk-http-vertx`.
- `sdk-spring-boot` must not depend on `sdk-http-vertx` nor `vertx-core`, directly or transitively.
- Default port stays 9080; URL layout (`/discover`, `/invoke/...`, `/health`) unchanged.
- Public API kept: `RestateHttpEndpointBean(Endpoint, RestateHttpServerProperties)`, the deprecated
  `RestateHttpEndpointBean(ApplicationContext, RestateEndpointProperties, RestateHttpServerProperties)`,
  `actualPort()`, `isRunning()`, every `RestateHttpServerProperties` getter.
- `x-restate-server` response header value: `Version.X_RESTATE_SERVER`.
- License header on every new `.java`/`.kt` file; run `./gradlew spotlessApply` before each commit.
- Gradle needs JDK 21+ to configure (the project toolchain stays 17).
- Commit messages: Conventional Commits, `type(scope): summary`, no co-author trailer.

## Review Focus

1. `getNativeRequest` throws (`IllegalStateException` from Spring's mock request,
   `IllegalArgumentException` from a decorator with no native request): the handler must treat the
   HTTP version as unknown and answer in request/response mode, never 500. Test in Task 1.
2. The client drops the connection mid-stream: the per-request worker is disposed and the
   processor's subscription cancelled, so nothing leaks. Test in Task 1.
3. Pooled Netty request buffers: every incoming `DataBuffer` is released after its bytes are copied.
   Test in Task 1.
4. Logging context across requests sharing a `parallel()` thread: one request's
   `restateInvocationId` must not appear in another request's logs. Test in Task 1.
5. Restate port already in use: the app keeps starting, the error is logged, `actualPort()` returns
   -1. Test in Task 3.

---

### Task 1: `RestateHttpHandler`

**Files:**
- Create: `sdk-spring-boot/src/main/java/dev/restate/sdk/springboot/RestateHttpHandler.java`
- Modify: `sdk-spring-boot/build.gradle.kts` (add `implementation(project(":sdk-core"))`,
  `implementation(libs.reactor.core)`, `testImplementation(libs.reactor.netty.http)`)
- Modify: `gradle/libs.versions.toml` (add `reactor-core = { module = 'io.projectreactor:reactor-core' }`
  and `reactor-netty-http = { module = 'io.projectreactor.netty:reactor-netty-http' }` with no
  version: the Spring dependency-management plugin applied in these modules resolves them from the
  Boot BOM)
- Test: `sdk-spring-boot/src/test/java/dev/restate/sdk/springboot/RestateHttpHandlerTest.java`

**Interfaces:**
- Produces:
  - `final class RestateHttpHandler implements org.springframework.http.server.reactive.HttpHandler`
    (package-private)
  - `RestateHttpHandler(Endpoint endpoint, boolean enableBidirectionalStreaming)`
  - `RestateHttpHandler(Endpoint, boolean, Supplier<Scheduler.Worker> workerSupplier)`
    (package-private test seam; the public-facing constructor passes
    `Schedulers.parallel()::createWorker`)
  - `Mono<Void> handle(ServerHttpRequest, ServerHttpResponse)`

- [ ] **Step 1: Write the failing tests**

Use `MockServerHttpRequest` / `MockServerHttpResponse` (spring-test) and an `Endpoint` binding the
`Greeter` used in existing tests (or a one-handler service defined in the test with the reflection
API, `@Service` + `@Handler`).
Accept header for discovery: `application/vnd.restate.endpointmanifest.v4+json`.

```java
@Test void discoverWithoutNativeRequest() // mock request: getNativeRequest throws
  // handle(GET /discover).block()
  assertThat(response.getStatusCode().value()).isEqualTo(200);
  assertThat(response.getHeaders().getFirst("x-restate-server")).isEqualTo(Version.X_RESTATE_SERVER);
  // body parsed into EndpointManifestSchema
  assertThat(manifest.getProtocolMode()).isEqualTo(EndpointManifestSchema.ProtocolMode.REQUEST_RESPONSE);

@Test void unknownPath() // GET /nope
  assertThat(response.getStatusCode().value()).isEqualTo(404);
  assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);

@Test void releasesRequestBuffers() // POST /invoke/greeter/greet with a body made of
  // NettyDataBufferFactory buffers (several small chunks), then handle(...).block()
  assertThat(chunks).allSatisfy(b -> assertThat(((NettyDataBuffer) b).getNativeBuffer().refCnt()).isZero());

@Test void cancelDisposesWorker() // worker supplier wraps Schedulers.parallel().createWorker()
  // and records dispose(); subscribe to handle(POST /invoke/...) with a never-ending body
  // (Flux.never()), then dispose the subscription
  assertThat(worker.isDisposed()).isTrue();

@Test void logContextDoesNotLeak() // run a task through the request executor that sets
  // restateInvocationId via the LoggingContextSetter, then run a plain task on the same worker
  assertThat(ThreadContext.get("restateInvocationId")).isNull(); // inside the second task
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdk-spring-boot:test --tests '*RestateHttpHandlerTest'`
Expected: FAIL, `RestateHttpHandler` does not exist.

- [ ] **Step 3: Implement `RestateHttpHandler`**

Per request, in this order:
- Bidirectional streaming = `enableBidirectionalStreaming && isHttp2(request)`.
  `isHttp2`: `ServerHttpRequestDecorator.getNativeRequest(request)` inside a
  `try/catch (RuntimeException)` returning false; true when the native object is a
  `jakarta.servlet.http.HttpServletRequest` with `getProtocol()` equal to `"HTTP/2.0"`, or a
  `reactor.netty.http.server.HttpServerRequest` with `version()` equal to `HttpVersion.valueOf("HTTP/2.0")`.
  Check each type with `ClassUtils.isPresent` first; the servlet API and reactor-netty are optional.
- Executor: `Scheduler.Worker worker = workerSupplier.get()`; logging context is a per-request
  `Map<String, String>`; the `LoggingContextSetter` writes to the map and to `ThreadContext`; the
  executor wraps each task: `ThreadContext.putAll(map)` before, `ThreadContext.removeAll(map.keySet())`
  after, then `worker.schedule(wrapped)`.
- `processorForRequest(request.getPath().value(), headersAccessor, setter, executor, bidi)`;
  on `ProtocolException`: status `e.getCode()`, `text/plain`, `x-restate-server`, body `e.getMessage()`,
  dispose the worker.
- Otherwise set status `processor.statusCode()`, `Content-Type` `processor.responseContentType()`,
  `x-restate-server`.
- Input: `request.getBody()` mapped to `Slice.wrap(copy)` where `copy` is a heap `ByteBuffer` with the
  readable bytes, then `DataBufferUtils.release(buffer)`; subscribe the processor with
  `FlowAdapters.toPublisher` / `FlowAdapters.toFlowSubscriber`.
- Output: `Flux.from(FlowAdapters.toPublisher(processor))`, each `Slice` copied into
  `response.bufferFactory().allocateBuffer(n)` (a copy, so a native-memory slice can be freed
  safely), wrapped in `Mono.just`, passed to `response.writeAndFlushWith(...)`.
  `onErrorResume`: if `!response.isCommitted()`, set the status from
  `ExceptionUtils.findProtocolException(e)` or 500; log a warning; complete empty.
- `doFinally` on the returned `Mono`: `worker.dispose()`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdk-spring-boot:test --tests '*RestateHttpHandlerTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add gradle/libs.versions.toml sdk-spring-boot/build.gradle.kts \
  sdk-spring-boot/src/main/java/dev/restate/sdk/springboot/RestateHttpHandler.java \
  sdk-spring-boot/src/test/java/dev/restate/sdk/springboot/RestateHttpHandlerTest.java
git commit -m "feat(sdk-spring-boot): add a spring http handler for the restate endpoint"
```

### Task 2: `RestateWebServerFactories`

**Files:**
- Create: `sdk-spring-boot/src/main/java/dev/restate/sdk/springboot/RestateWebServerFactories.java`
- Modify: `sdk-spring-boot/build.gradle.kts` (`compileOnly` + `testImplementation`:
  `org.apache.tomcat.embed:tomcat-embed-core`, `io.projectreactor.netty:reactor-netty-http`;
  `compileOnly`: `org.eclipse.jetty:jetty-server`, `io.undertow:undertow-core`, only if the Boot
  factory calls below fail to compile without them)
- Test: `sdk-spring-boot/src/test/java/dev/restate/sdk/springboot/RestateWebServerFactoriesTest.java`

**Interfaces:**
- Produces:
  - `final class RestateWebServerFactories` (package-private, static methods only)
  - `static ConfigurableReactiveWebServerFactory create(ListableBeanFactory beanFactory)`: returns a
    new factory with HTTP/2 enabled where h2c is available; port not set.
  - `static boolean servesH2c(ConfigurableReactiveWebServerFactory factory)`: true for Tomcat and
    Netty, for Jetty only when `org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory` is
    present, false for Undertow.

- [ ] **Step 1: Write the failing tests**

Register bean definitions by class in a `DefaultListableBeanFactory` (no instantiation needed).

```java
@Test void followsServletTomcat()   // bean TomcatServletWebServerFactory
  assertThat(create(bf)).isInstanceOf(TomcatReactiveWebServerFactory.class);
@Test void followsReactiveNetty()   // bean NettyReactiveWebServerFactory, Tomcat also on classpath
  assertThat(create(bf)).isInstanceOf(NettyReactiveWebServerFactory.class);
@Test void classpathOrderWithoutBean() // empty bean factory; test classpath has Tomcat and Netty
  assertThat(create(bf)).isInstanceOf(TomcatReactiveWebServerFactory.class);
@Test void freshInstance()          // bean NettyReactiveWebServerFactory registered as a singleton
  assertThat(create(bf)).isNotSameAs(bf.getBean(NettyReactiveWebServerFactory.class));
@Test void http2Enabled()
  assertThat(((AbstractConfigurableWebServerFactory) create(bf)).getHttp2().isEnabled()).isTrue();
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdk-spring-boot:test --tests '*RestateWebServerFactoriesTest'`
Expected: FAIL, class does not exist.

- [ ] **Step 3: Implement `RestateWebServerFactories`**

- App container: `beanFactory.getBeanNamesForType(WebServerFactory.class, true, false)` then
  `beanFactory.getType(name)` (no eager init); map Tomcat, Jetty, Undertow servlet or reactive
  factory types to the matching reactive factory, `NettyReactiveWebServerFactory` to itself.
  Compare type names, or check `ClassUtils.isPresent` before any `instanceof`/`isAssignableFrom`
  involving a container class.
- No match: first present of `org.apache.catalina.startup.Tomcat`, `org.eclipse.jetty.server.Server`,
  `io.undertow.Undertow`, `reactor.netty.http.server.HttpServer`; none present:
  `IllegalStateException("No web server on the classpath to serve the Restate endpoint: add spring-boot-starter-reactor-netty")`.
- HTTP/2: `Http2 http2 = new Http2(); http2.setEnabled(true); factory.setHttp2(http2)` unless the
  factory is Jetty without `HTTP2CServerConnectionFactory`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdk-spring-boot:test --tests '*RestateWebServerFactoriesTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add sdk-spring-boot/build.gradle.kts \
  sdk-spring-boot/src/main/java/dev/restate/sdk/springboot/RestateWebServerFactories.java \
  sdk-spring-boot/src/test/java/dev/restate/sdk/springboot/RestateWebServerFactoriesTest.java
git commit -m "feat(sdk-spring-boot): pick the web server factory matching the app's container"
```

### Task 3: Serve the endpoint without Vert.x

**Files:**
- Modify: `sdk-spring-boot/src/main/java/dev/restate/sdk/springboot/RestateHttpEndpointBean.java`
  (replace the Vert.x server and `ServerVerticle`)
- Modify: `sdk-spring-boot/src/main/java/dev/restate/sdk/springboot/RestateHttpServerProperties.java`
  (`getEventLoops()`: `@Deprecated` + `@DeprecatedConfigurationProperty(reason = "The web server owns its threads; this value is ignored")`)
- Modify: `sdk-spring-boot/build.gradle.kts` (remove `project(":sdk-http-vertx")` and `libs.vertx.core`)
- Modify: `sdk-spring-boot-starter/build.gradle.kts`, `sdk-spring-boot-kotlin-starter/build.gradle.kts`
  (`api(libs.spring.boot.starter.reactor.netty)`)
- Modify: `gradle/libs.versions.toml` (add `spring-boot-starter-reactor-netty`,
  `spring-boot-starter-web` and `spring-boot-starter-webflux`, each with `ref = 'spring-boot'`, in the
  style of the existing `spring-boot-starter-*` entries)
- Test: `sdk-spring-boot-starter/src/test/java/dev/restate/sdk/springboot/java/RestateHttpEndpointBeanTest.java`
- Test: `sdk-spring-boot-starter/src/test/java/dev/restate/sdk/springboot/java/RestateHttpEndpointBeanPortInUseTest.java`

**Interfaces:**
- Consumes: `new RestateHttpHandler(Endpoint, boolean)` (Task 1);
  `RestateWebServerFactories.create(ListableBeanFactory)`, `RestateWebServerFactories.servesH2c(...)` (Task 2)
- Produces: `RestateHttpEndpointBean` also `implements BeanFactoryAware` to receive the
  `ListableBeanFactory` passed to `create`.

- [ ] **Step 1: Write the failing tests**

In `RestateHttpEndpointBeanTest` (no web stack: the server is the starter's Reactor Netty), add to
the existing test, after the manifest is parsed:

```java
assertThat(endpointManifest.getProtocolMode()).isEqualTo(EndpointManifestSchema.ProtocolMode.BIDI_STREAM);
```

and add:

```java
@Test void http11FallsBackToRequestResponse() // same /discover call with HttpClient.Version.HTTP_1_1
  assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
  assertThat(manifest.getProtocolMode()).isEqualTo(EndpointManifestSchema.ProtocolMode.REQUEST_RESPONSE);

@Test void plainAppStaysNonWeb()
  assertThat(new SpringApplicationBuilder(Greeter.class).build().getWebApplicationType())
      .isEqualTo(WebApplicationType.NONE);
```

`RestateHttpEndpointBeanPortInUseTest`: hold a `ServerSocket(0)` open in a static initializer,
register its port as `restate.sdk.http.port` with a `DynamicPropertyRegistrar` `@Bean`, same classes
as `RestateHttpEndpointBeanTest`:

```java
@Test void portInUse()
  assertThat(this.bean.isRunning()).isTrue();
  assertThat(this.bean.actualPort()).isEqualTo(-1);
```

- [ ] **Step 2: Run the tests on the Vert.x server**

Run: `./gradlew :sdk-spring-boot-starter:test --tests '*RestateHttpEndpointBean*'`
Expected: PASS. Vert.x already behaves this way: these tests pin the behaviour the rewrite must
keep, so they have to stay green after Step 3.

- [ ] **Step 3: Rewrite `RestateHttpEndpointBean`**

- Constructors keep their signatures; they build `RestateHttpHandler(endpoint, !props.isDisableBidirectionalStreaming())`
  instead of the Vert.x handler; the deprecated one keeps its "no `@RestateComponent`, no handler" path.
- `start()`: handler null: `running = true`, return. Otherwise
  `factory = RestateWebServerFactories.create(beanFactory)`; `factory.setPort(props.getPort())`
  (0 means random, unlike Vert.x's -1 trick); if `props.getEventLoops() > 0` log a warning that it is
  ignored; if `!servesH2c(factory)` log a warning naming the factory class and request/response mode;
  `webServer = factory.getWebServer(handler)`; `webServer.start()`; `actualPort = webServer.getPort()`;
  log the port. Catch `Exception`: log the error, keep `actualPort = -1`. Always end with `running = true`.
- `stop()`: `webServer.stop()` if non-null, then null it; `actualPort = -1`; `running = false`.

- [ ] **Step 4: Remove Vert.x from `sdk-spring-boot` and add Reactor Netty to the starters**

Run: `./gradlew :sdk-spring-boot-starter:dependencies --configuration runtimeClasspath | rg -i vertx`
Expected: no output.

- [ ] **Step 5: Run the starter tests**

Run: `./gradlew :sdk-spring-boot:test :sdk-spring-boot-starter:test :sdk-spring-boot-kotlin-starter:test`
Expected: PASS, including the unchanged `RestateHttpEndpointBeanDefaultsTest`, the Kotlin
`RestateHttpEndpointBeanTest` and `SdkTestingIntegrationTest` (needs Docker).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add sdk-spring-boot sdk-spring-boot-starter sdk-spring-boot-kotlin-starter gradle/libs.versions.toml
git commit -m "feat(sdk-spring-boot): serve the restate endpoint with spring's web server instead of vert.x"
```

### Task 4: Spring MVC on Tomcat, end to end

**Files:**
- Modify: `sdk-spring-boot-starter/build.gradle.kts` (register a `JvmTestSuite` named `tomcatTest`:
  `implementation(project())`, `implementation(libs.spring.boot.starter.test)`,
  `implementation(libs.spring.boot.starter.web)`,
  `implementation(libs.testcontainers)`, `implementation(project(":sdk-core"))` for the manifest
  classes; `tasks.check { dependsOn(testing.suites.named("tomcatTest")) }`)
- Create: `sdk-spring-boot-starter/src/tomcatTest/java/dev/restate/sdk/springboot/tomcat/TomcatApp.java`
  (`@SpringBootApplication @EnableRestate`, one `@RestateService` `Echo` with `@Handler String echo(String)`,
  reflection API, no annotation processor)
- Test: `sdk-spring-boot-starter/src/tomcatTest/java/dev/restate/sdk/springboot/tomcat/TomcatServerTest.java`

**Interfaces:**
- Consumes: `RestateHttpEndpointBean.actualPort()` (Task 3)

- [ ] **Step 1: Write the tests**

`@SpringBootTest(classes = TomcatApp.class, webEnvironment = RANDOM_PORT, properties = "restate.sdk.http.port=0")`:

```java
@Test void discoverOverH2c()   // HttpClient HTTP_2 on http://localhost:<actualPort>/discover
  assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
  assertThat(manifest.getProtocolMode()).isEqualTo(EndpointManifestSchema.ProtocolMode.BIDI_STREAM);
@Test void discoverOverHttp11()
  assertThat(manifest.getProtocolMode()).isEqualTo(EndpointManifestSchema.ProtocolMode.REQUEST_RESPONSE);
@Test void separatePort()      // @LocalServerPort is the MVC server
  assertThat(this.bean.actualPort()).isNotEqualTo(this.serverPort);
@Test @Timeout(60) void invokeThroughRestate()
  // Testcontainers.exposeHostPorts(actualPort) BEFORE starting
  // GenericContainer("ghcr.io/restatedev/restate:main") with exposed ports 8080 and 9070;
  // POST http://<host>:<9070 mapped>/deployments, JSON {"uri":"http://host.testcontainers.internal:<actualPort>"}
  assertThat(registerResponse.statusCode()).isBetween(200, 299);
  // Client.connect("http://<host>:<8080 mapped>").service(Echo.class).echo("hi")
  assertThat(result).isEqualTo("hi");
```

Restate dials `http://` deployments over HTTP/2 prior knowledge, so `invokeThroughRestate` goes
through Tomcat's h2c path and the servlet adapter in bidi mode.

- [ ] **Step 2: Run the suite**

Run: `./gradlew :sdk-spring-boot-starter:tomcatTest`
Expected: PASS (Docker required). A failure here is a bug in Tasks 1 to 3: fix it there and add the
missing unit test to that task's test class.

- [ ] **Step 3: Commit**

```bash
./gradlew spotlessApply
git add sdk-spring-boot-starter/build.gradle.kts sdk-spring-boot-starter/src/tomcatTest
git commit -m "test(sdk-spring-boot-starter): cover spring mvc on tomcat end to end"
```

### Task 5: WebFlux on Reactor Netty

**Files:**
- Modify: `sdk-spring-boot-starter/build.gradle.kts` (`JvmTestSuite` `webfluxTest`, as in Task 4
  with `libs.spring.boot.starter.webflux` instead of `libs.spring.boot.starter.web`, no Testcontainers)
- Create: `sdk-spring-boot-starter/src/webfluxTest/java/dev/restate/sdk/springboot/webflux/WebfluxApp.java`
- Test: `sdk-spring-boot-starter/src/webfluxTest/java/dev/restate/sdk/springboot/webflux/WebfluxServerTest.java`

**Interfaces:**
- Consumes: `RestateHttpEndpointBean.actualPort()` (Task 3)

- [ ] **Step 1: Write the tests**

`discoverOverH2c`, `discoverOverHttp11` and `separatePort` from Task 4, against `WebfluxApp`.

- [ ] **Step 2: Run the suite**

Run: `./gradlew :sdk-spring-boot-starter:webfluxTest`
Expected: PASS

- [ ] **Step 3: Full check and commit**

Run: `./gradlew spotlessApply build`
Expected: BUILD SUCCESSFUL

```bash
git add sdk-spring-boot-starter/build.gradle.kts sdk-spring-boot-starter/src/webfluxTest
git commit -m "test(sdk-spring-boot-starter): cover webflux on reactor netty"
```
