// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot.java;

import static org.assertj.core.api.Assertions.*;

import dev.restate.sdk.springboot.RestateEndpointConfiguration;
import dev.restate.sdk.springboot.RestateHttpConfiguration;
import dev.restate.sdk.springboot.RestateHttpEndpointBean;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = {
      RestateEndpointConfiguration.class,
      RestateHttpConfiguration.class,
      Greeter.class,
      ServicesConfiguration.class
    },
    properties = {"greetingPrefix=Hello "})
public class RestateHttpEndpointBeanPortInUseTest {

  private static final ServerSocket HELD_SOCKET;

  static {
    try {
      HELD_SOCKET = new ServerSocket(0);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void restatePort(DynamicPropertyRegistry registry) {
    registry.add("restate.sdk.http.port", () -> String.valueOf(HELD_SOCKET.getLocalPort()));
  }

  @Autowired private RestateHttpEndpointBean restateHttpEndpointBean;

  @AfterAll
  static void releasePort() throws IOException {
    HELD_SOCKET.close();
  }

  @Test
  public void portInUse() {
    // The app keeps starting, the error is logged, and no port is served.
    assertThat(restateHttpEndpointBean.isRunning()).isTrue();
    assertThat(restateHttpEndpointBean.actualPort()).isEqualTo(-1);
  }
}
