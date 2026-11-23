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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatReactiveWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.AbstractConfigurableWebServerFactory;

class RestateWebServerFactoriesTest {

  private static DefaultListableBeanFactory beanFactoryWithBeanDefinition(Class<?> beanType) {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition(
        beanType.getName(),
        BeanDefinitionBuilder.genericBeanDefinition(beanType).getBeanDefinition());
    return beanFactory;
  }

  @Test
  void followsServletTomcat() {
    DefaultListableBeanFactory beanFactory =
        beanFactoryWithBeanDefinition(TomcatServletWebServerFactory.class);

    assertThat(RestateWebServerFactories.create(beanFactory))
        .isInstanceOf(TomcatReactiveWebServerFactory.class);
  }

  @Test
  void followsReactiveNetty() {
    // Tomcat is also on the test classpath, but the app's own factory wins.
    DefaultListableBeanFactory beanFactory =
        beanFactoryWithBeanDefinition(NettyReactiveWebServerFactory.class);

    assertThat(RestateWebServerFactories.create(beanFactory))
        .isInstanceOf(NettyReactiveWebServerFactory.class);
  }

  @Test
  void classpathOrderWithoutBean() {
    // Empty bean factory: the test classpath has Tomcat and Reactor Netty, Tomcat comes first.
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    assertThat(RestateWebServerFactories.create(beanFactory))
        .isInstanceOf(TomcatReactiveWebServerFactory.class);
  }

  @Test
  void freshInstance() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerSingleton("nettyFactory", new NettyReactiveWebServerFactory());

    assertThat(RestateWebServerFactories.create(beanFactory))
        .isNotSameAs(beanFactory.getBean(NettyReactiveWebServerFactory.class));
  }

  @Test
  void http2Enabled() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    var factory = RestateWebServerFactories.create(beanFactory);

    assertThat(RestateWebServerFactories.servesH2c(factory)).isTrue();
    assertThat(((AbstractConfigurableWebServerFactory) factory).getHttp2().isEnabled()).isTrue();
  }
}
