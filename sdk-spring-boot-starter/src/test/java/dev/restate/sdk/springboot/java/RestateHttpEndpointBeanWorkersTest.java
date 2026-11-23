// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restate.sdk.core.generated.manifest.EndpointManifestSchema;
import dev.restate.sdk.springboot.RestateEndpointConfiguration;
import dev.restate.sdk.springboot.RestateHttpConfiguration;
import dev.restate.sdk.springboot.RestateHttpEndpointBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = {
      RestateEndpointConfiguration.class,
      RestateHttpConfiguration.class,
      Greeter.class,
      ServicesConfiguration.class
    },
    properties = {
      "restate.sdk.http.port=0",
      "restate.sdk.http.workers=2",
      "greetingPrefix=Hello "
    })
public class RestateHttpEndpointBeanWorkersTest {

  @Autowired private RestateHttpEndpointBean restateHttpEndpointBean;

  @Test
  public void discoverWithCustomWorkerCount() throws Exception {
    assertThat(restateHttpEndpointBean.isRunning()).isTrue();
    assertThat(restateHttpEndpointBean.actualPort()).isPositive();

    var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    var response =
        client.send(
            HttpRequest.newBuilder()
                .GET()
                .version(HttpClient.Version.HTTP_2)
                .uri(
                    URI.create(
                        "http://localhost:" + restateHttpEndpointBean.actualPort() + "/discover"))
                .header("Accept", "application/vnd.restate.endpointmanifest.v4+json")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);

    var endpointManifest =
        new ObjectMapper().readValue(response.body(), EndpointManifestSchema.class);
    assertThat(endpointManifest.getProtocolMode())
        .isEqualTo(EndpointManifestSchema.ProtocolMode.BIDI_STREAM);
  }
}
