// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot.test;

import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.annotation.Name;
import dev.restate.sdk.springboot.EnableRestate;
import dev.restate.sdk.springboot.RestateService;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Shared app for the container test suites: the container comes from each suite's classpath. */
@SpringBootApplication
@EnableRestate
@Import(RestateTestApp.Echo.class)
public class RestateTestApp {

  @RestateService
  @Name("echo")
  public static class Echo {

    @Handler
    public String echo(String input) {
      return input;
    }
  }
}
