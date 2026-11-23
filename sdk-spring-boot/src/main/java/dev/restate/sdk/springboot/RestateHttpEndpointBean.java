// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot;

import dev.restate.sdk.auth.signing.RestateRequestIdentityVerifier;
import dev.restate.sdk.endpoint.Endpoint;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.reactive.server.ConfigurableReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;

/**
 * Restate HTTP Endpoint serving {@link Endpoint} with the app's own web server (Tomcat, Jetty,
 * Undertow or Reactor Netty) through a Spring Boot factory.
 */
public class RestateHttpEndpointBean
    implements InitializingBean, SmartLifecycle, BeanFactoryAware {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private final RestateHttpServerProperties restateHttpServerProperties;

  private volatile boolean running;

  private @Nullable RestateHttpHandler handler;

  private volatile @Nullable Scheduler workerScheduler;

  private volatile @Nullable WebServer webServer;

  private volatile int actualPort = -1;

  private @Nullable ListableBeanFactory beanFactory;

  public RestateHttpEndpointBean(
      Endpoint endpoint, RestateHttpServerProperties restateHttpServerProperties) {
    this.restateHttpServerProperties = restateHttpServerProperties;
    initHandler(endpoint);
  }

  @Deprecated
  public RestateHttpEndpointBean(
      ApplicationContext applicationContext,
      RestateEndpointProperties restateEndpointProperties,
      RestateHttpServerProperties restateHttpServerProperties) {
    this.restateHttpServerProperties = restateHttpServerProperties;

    Map<String, Object> restateComponents =
        applicationContext.getBeansWithAnnotation(RestateComponent.class);

    if (restateComponents.isEmpty()) {
      logger.info("No @RestateComponent discovered");
      this.handler = null;
      // Don't start anything, if no service is registered
      return;
    }

    var builder = Endpoint.builder();
    for (var componentEntry : restateComponents.entrySet()) {
      // Get configurator, if any
      RestateComponent restateComponent =
          applicationContext.findAnnotationOnBean(componentEntry.getKey(), RestateComponent.class);
      RestateServiceConfigurator configurator = c -> {};
      if (restateComponent != null && !restateComponent.configuration().isEmpty()) {
        configurator =
            applicationContext.getBean(
                restateComponent.configuration(), RestateServiceConfigurator.class);
      }
      builder = builder.bind(componentEntry.getValue(), configurator);
    }

    if (restateEndpointProperties.isEnablePreviewContext()) {
      builder = builder.enablePreviewContext();
    }

    if (restateEndpointProperties.getIdentityKey() != null) {
      builder.withRequestIdentityVerifier(
          RestateRequestIdentityVerifier.fromKey(restateEndpointProperties.getIdentityKey()));
    }

    Endpoint endpoint = builder.build();
    initHandler(endpoint);
  }

  private void initHandler(Endpoint endpoint) {
    RestateHttpServerProperties props = this.restateHttpServerProperties;
    final Supplier<Scheduler.Worker> workerSupplier;
    if (props.isVirtualThreads()) {
      // One virtual thread per request.
      workerSupplier = RestateHttpHandler.virtualThreadWorkerSupplier();
    } else if (props.getWorkers() > 0) {
      Scheduler scheduler = Schedulers.newParallel("restate-http", props.getWorkers());
      this.workerScheduler = scheduler;
      workerSupplier = scheduler::createWorker;
    } else {
      workerSupplier = Schedulers.parallel()::createWorker;
    }
    this.handler =
        new RestateHttpHandler(
            endpoint, !props.isDisableBidirectionalStreaming(), workerSupplier);
  }

  @Override
  public void setBeanFactory(BeanFactory beanFactory) {
    if (beanFactory instanceof ListableBeanFactory listableBeanFactory) {
      this.beanFactory = listableBeanFactory;
    }
  }

  @Deprecated
  @Override
  public void afterPropertiesSet() {}

  @Override
  public void start() {
    RestateHttpHandler handler = this.handler;
    if (handler == null) {
      // No service registered, nothing to start.
      this.running = true;
      return;
    }
    try {
      ListableBeanFactory beanFactory = this.beanFactory;
      if (beanFactory == null) {
        // Not created as a Spring bean: fall back to the classpath.
        beanFactory = new DefaultListableBeanFactory();
      }
      ConfigurableReactiveWebServerFactory factory = RestateWebServerFactories.create(beanFactory);
      // Port 0 means a random port.
      factory.setPort(this.restateHttpServerProperties.getPort());
      if (!RestateWebServerFactories.servesH2c(factory)) {
        logger.warn(
            "{} does not serve cleartext HTTP/2 (h2c): the Restate endpoint on port {} falls back"
                + " to request/response mode",
            factory.getClass().getSimpleName(),
            this.restateHttpServerProperties.getPort());
      }
      WebServer webServer = factory.getWebServer(handler);
      webServer.start();
      this.webServer = webServer;
      this.actualPort = webServer.getPort();
      logger.info("Started Restate Spring HTTP server on port {}", this.actualPort);
    } catch (Exception e) {
      logger.error(
          "Error when starting Restate Spring HTTP server on port {}",
          this.restateHttpServerProperties.getPort(),
          e);
      this.actualPort = -1;
    }
    this.running = true;
  }

  @Override
  public void stop() {
    try {
      WebServer webServer = this.webServer;
      if (webServer != null) {
        webServer.stop();
        this.webServer = null;
      }
      Scheduler workerScheduler = this.workerScheduler;
      if (workerScheduler != null) {
        workerScheduler.dispose();
        this.workerScheduler = null;
      }
      logger.info("Stopped Restate Spring HTTP server");
    } catch (Exception e) {
      logger.error("Error when stopping the Restate Spring HTTP server", e);
    }
    this.actualPort = -1;
    this.running = false;
  }

  @Override
  public boolean isRunning() {
    return this.running;
  }

  public int actualPort() {
    if (!this.isRunning()) {
      return -1;
    }
    return this.actualPort;
  }
}
