// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot;

import java.util.EnumSet;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.jetty.reactive.JettyReactiveWebServerFactory;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.tomcat.reactive.TomcatReactiveWebServerFactory;
import org.springframework.boot.undertow.reactive.UndertowReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ConfigurableReactiveWebServerFactory;
import org.springframework.boot.web.server.Http2;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.util.ClassUtils;

/**
 * Picks the {@link ConfigurableReactiveWebServerFactory} that serves the Restate endpoint on its
 * own port, following the app's own container: when the context holds a {@link WebServerFactory}
 * bean, the matching container is used; otherwise the first container found on the classpath, in
 * this order: Tomcat, Jetty, Undertow, Reactor Netty. A fresh factory is always built: reusing the
 * app's factory bean would mean changing the port of the bean the main server was built from.
 *
 * <p>The container classes are only referenced through {@link ClassUtils#isPresent} checks, so a
 * missing container never throws {@link NoClassDefFoundError}.
 */
final class RestateWebServerFactories {

  private static final String TOMCAT_CONTAINER_CLASS = "org.apache.catalina.startup.Tomcat";
  private static final String JETTY_CONTAINER_CLASS = "org.eclipse.jetty.server.Server";
  private static final String UNDERTOW_CONTAINER_CLASS = "io.undertow.Undertow";
  private static final String NETTY_CONTAINER_CLASS = "reactor.netty.http.server.HttpServer";

  private static final String TOMCAT_SERVLET_FACTORY =
      "org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory";
  private static final String TOMCAT_REACTIVE_FACTORY =
      "org.springframework.boot.tomcat.reactive.TomcatReactiveWebServerFactory";
  private static final String JETTY_SERVLET_FACTORY =
      "org.springframework.boot.jetty.servlet.JettyServletWebServerFactory";
  private static final String JETTY_REACTIVE_FACTORY =
      "org.springframework.boot.jetty.reactive.JettyReactiveWebServerFactory";
  private static final String UNDERTOW_SERVLET_FACTORY =
      "org.springframework.boot.undertow.servlet.UndertowServletWebServerFactory";
  private static final String UNDERTOW_REACTIVE_FACTORY =
      "org.springframework.boot.undertow.reactive.UndertowReactiveWebServerFactory";
  private static final String NETTY_REACTIVE_FACTORY =
      "org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory";
  private static final String JETTY_H2C_CLASS =
      "org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory";

  private RestateWebServerFactories() {}

  /**
   * The web containers the endpoint can be served with, in classpath priority order. Each entry
   * keeps the container class name, the app factory class names (servlet and reactive) and the
   * cleartext HTTP/2 support, next to each other.
   */
  private enum Container {
    TOMCAT(TOMCAT_CONTAINER_CLASS, TOMCAT_SERVLET_FACTORY, TOMCAT_REACTIVE_FACTORY, true, null),
    JETTY(
        JETTY_CONTAINER_CLASS, JETTY_SERVLET_FACTORY, JETTY_REACTIVE_FACTORY, false,
        JETTY_H2C_CLASS),
    UNDERTOW(
        UNDERTOW_CONTAINER_CLASS, UNDERTOW_SERVLET_FACTORY, UNDERTOW_REACTIVE_FACTORY, false, null),
    NETTY(NETTY_CONTAINER_CLASS, null, NETTY_REACTIVE_FACTORY, true, null);

    /** Spring Boot version that introduced the reactive factory, for legacy compatibility. */
    static final String REACTIVE_FACTORY_SINCE_BOOT = "2.0.0";

    final String containerClassName;
    final @Nullable String servletFactoryClassName;
    final String reactiveFactoryClassName;
    final boolean h2cAlways;
    final @Nullable String h2cClassName;

    Container(
        String containerClassName,
        @Nullable String servletFactoryClassName,
        String reactiveFactoryClassName,
        boolean h2cAlways,
        @Nullable String h2cClassName) {
      this.containerClassName = containerClassName;
      this.servletFactoryClassName = servletFactoryClassName;
      this.reactiveFactoryClassName = reactiveFactoryClassName;
      this.h2cAlways = h2cAlways;
      this.h2cClassName = h2cClassName;
    }

    boolean containerPresent(ClassLoader classLoader) {
      return ClassUtils.isPresent(containerClassName, classLoader);
    }

    /** Whether this container serves cleartext HTTP/2 (h2c). */
    boolean servesH2c(ClassLoader classLoader) {
      return h2cAlways || (h2cClassName != null && ClassUtils.isPresent(h2cClassName, classLoader));
    }

    ConfigurableReactiveWebServerFactory newReactiveFactory() {
      return switch (this) {
        case TOMCAT -> new TomcatReactiveWebServerFactory();
        case JETTY -> new JettyReactiveWebServerFactory();
        case UNDERTOW -> new UndertowReactiveWebServerFactory();
        case NETTY -> new NettyReactiveWebServerFactory();
      };
    }

    static @Nullable Container fromFactoryClassName(String factoryClassName) {
      for (Container container : values()) {
        if (factoryClassName.equals(container.reactiveFactoryClassName)
            || (container.servletFactoryClassName != null
                && factoryClassName.equals(container.servletFactoryClassName))) {
          return container;
        }
      }
      return null;
    }
  }

  /**
   * Returns a new factory for the app's container (or the first one on the classpath), with
   * cleartext HTTP/2 enabled where the container serves h2c. The port is not set.
   */
  static ConfigurableReactiveWebServerFactory create(ListableBeanFactory beanFactory) {
    ConfigurableReactiveWebServerFactory factory = fromAppContainer(beanFactory);
    if (factory == null) {
      factory = fromClasspath();
    }
    if (factory == null) {
      throw new IllegalStateException(
          "No web server on the classpath to serve the Restate endpoint:"
              + " add spring-boot-starter-reactor-netty");
    }
    Container container = Container.fromFactoryClassName(factory.getClass().getName());
    if (container != null
        && container.servesH2c(RestateWebServerFactories.class.getClassLoader())) {
      Http2 http2 = new Http2();
      http2.setEnabled(true);
      factory.setHttp2(http2);
    }
    return factory;
  }

  /**
   * Whether the factory serves cleartext HTTP/2 (h2c): true for Tomcat and Reactor Netty, for
   * Jetty only when {@code jetty-http2-server} is present, false for Undertow.
   */
  static boolean servesH2c(ConfigurableReactiveWebServerFactory factory) {
    Container container = Container.fromFactoryClassName(factory.getClass().getName());
    return container != null
        && container.servesH2c(RestateWebServerFactories.class.getClassLoader());
  }

  private static @Nullable ConfigurableReactiveWebServerFactory fromAppContainer(
      ListableBeanFactory beanFactory) {
    ClassLoader classLoader = RestateWebServerFactories.class.getClassLoader();
    for (String beanName : beanFactory.getBeanNamesForType(WebServerFactory.class, true, false)) {
      Class<?> beanType = beanFactory.getType(beanName);
      if (beanType == null) {
        continue;
      }
      ConfigurableReactiveWebServerFactory factory =
          reactiveFactoryFor(beanType.getName(), classLoader);
      if (factory != null) {
        return factory;
      }
    }
    return null;
  }

  private static @Nullable ConfigurableReactiveWebServerFactory reactiveFactoryFor(
      String factoryClassName, ClassLoader classLoader) {
    final Container container;
    switch (factoryClassName) {
      case TOMCAT_SERVLET_FACTORY:
      case TOMCAT_REACTIVE_FACTORY:
        container = Container.TOMCAT;
        break;
      case JETTY_SERVLET_FACTORY:
      case JETTY_REACTIVE_FACTORY:
        container = Container.JETTY;
        break;
      case UNDERTOW_SERVLET_FACTORY:
      case UNDERTOW_REACTIVE_FACTORY:
        container = Container.UNDERTOW;
        break;
      case NETTY_REACTIVE_FACTORY:
        container = Container.NETTY;
        break;
      default:
        return null;
    }
    return container.containerPresent(classLoader) ? container.newReactiveFactory() : null;
  }

  private static @Nullable ConfigurableReactiveWebServerFactory fromClasspath() {
    ClassLoader classLoader = RestateWebServerFactories.class.getClassLoader();
    // Load the available containers once, then pick the first one in priority order.
    EnumSet<Container> present = EnumSet.noneOf(Container.class);
    for (Container container : Container.values()) {
      if (container.containerPresent(classLoader)) {
        present.add(container);
      }
    }
    for (Container container : Container.values()) {
      if (present.contains(container)) {
        return container.newReactiveFactory();
      }
    }
    return null;
  }
}
