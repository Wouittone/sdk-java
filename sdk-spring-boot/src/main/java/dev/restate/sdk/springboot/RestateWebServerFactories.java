// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot;

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
  private static final String JETTY_H2C_CLASS =
      "org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory";

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

  private RestateWebServerFactories() {}

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
    if (servesH2c(factory)) {
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
    String factoryClassName = factory.getClass().getName();
    if (factoryClassName.equals(TOMCAT_REACTIVE_FACTORY)
        || factoryClassName.equals(NETTY_REACTIVE_FACTORY)) {
      return true;
    }
    if (factoryClassName.equals(JETTY_REACTIVE_FACTORY)) {
      return ClassUtils.isPresent(
          JETTY_H2C_CLASS, RestateWebServerFactories.class.getClassLoader());
    }
    return false;
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
    if (factoryClassName.equals(TOMCAT_SERVLET_FACTORY)
        || factoryClassName.equals(TOMCAT_REACTIVE_FACTORY)) {
      return ClassUtils.isPresent(TOMCAT_CONTAINER_CLASS, classLoader)
          ? new TomcatReactiveWebServerFactory()
          : null;
    }
    if (factoryClassName.equals(JETTY_SERVLET_FACTORY)
        || factoryClassName.equals(JETTY_REACTIVE_FACTORY)) {
      return ClassUtils.isPresent(JETTY_CONTAINER_CLASS, classLoader)
          ? new JettyReactiveWebServerFactory()
          : null;
    }
    if (factoryClassName.equals(UNDERTOW_SERVLET_FACTORY)
        || factoryClassName.equals(UNDERTOW_REACTIVE_FACTORY)) {
      return ClassUtils.isPresent(UNDERTOW_CONTAINER_CLASS, classLoader)
          ? new UndertowReactiveWebServerFactory()
          : null;
    }
    if (factoryClassName.equals(NETTY_REACTIVE_FACTORY)) {
      return ClassUtils.isPresent(NETTY_CONTAINER_CLASS, classLoader)
          ? new NettyReactiveWebServerFactory()
          : null;
    }
    return null;
  }

  private static @Nullable ConfigurableReactiveWebServerFactory fromClasspath() {
    ClassLoader classLoader = RestateWebServerFactories.class.getClassLoader();
    if (ClassUtils.isPresent(TOMCAT_CONTAINER_CLASS, classLoader)) {
      return new TomcatReactiveWebServerFactory();
    }
    if (ClassUtils.isPresent(JETTY_CONTAINER_CLASS, classLoader)) {
      return new JettyReactiveWebServerFactory();
    }
    if (ClassUtils.isPresent(UNDERTOW_CONTAINER_CLASS, classLoader)) {
      return new UndertowReactiveWebServerFactory();
    }
    if (ClassUtils.isPresent(NETTY_CONTAINER_CLASS, classLoader)) {
      return new NettyReactiveWebServerFactory();
    }
    return null;
  }
}
