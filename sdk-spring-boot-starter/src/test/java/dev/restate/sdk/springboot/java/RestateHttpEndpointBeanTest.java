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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restate.sdk.core.generated.manifest.EndpointManifestSchema;
import dev.restate.sdk.springboot.RestateEndpointConfiguration;
import dev.restate.sdk.springboot.RestateHttpConfiguration;
import dev.restate.sdk.springboot.RestateHttpEndpointBean;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = {
      RestateEndpointConfiguration.class,
      RestateHttpConfiguration.class,
      Greeter.class,
      ServicesConfiguration.class
    },
    properties = {
      "restate.sdk.http.port=0",
      "restate.components.greeter.journal-retention=PT48H",
      "greetingPrefix=Hello "
    })
public class RestateHttpEndpointBeanTest {

  @Autowired private RestateHttpEndpointBean restateHttpEndpointBean;

  private static RestClient restClient(HttpClient.Version version) {
    return RestClient.builder()
        .requestFactory(
            new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(version).build()))
        .build();
  }

  private EndpointManifestSchema discover(RestClient client) throws IOException {
    var body =
        client
            .get()
            .uri("http://localhost:{port}/discover", restateHttpEndpointBean.actualPort())
            .header(HttpHeaders.ACCEPT, "application/vnd.restate.endpointmanifest.v4+json")
            .retrieve()
            .body(String.class);
    return new ObjectMapper().readValue(body, EndpointManifestSchema.class);
  }

  @Test
  public void httpEndpointShouldBeRunning() throws IOException {
    assertThat(restateHttpEndpointBean.isRunning()).isTrue();
    assertThat(restateHttpEndpointBean.actualPort()).isPositive();

    // Check if discovery replies contain the Greeter service. Served over cleartext HTTP/2, so
    // bidirectional streaming is on.
    var endpointManifest = discover(restClient(HttpClient.Version.HTTP_2));

    assertThat(endpointManifest.getProtocolMode())
        .isEqualTo(EndpointManifestSchema.ProtocolMode.BIDI_STREAM);
    assertThat(endpointManifest.getServices())
        .extracting(
            dev.restate.sdk.core.generated.manifest.Service::getName,
            dev.restate.sdk.core.generated.manifest.Service::getDocumentation,
            dev.restate.sdk.core.generated.manifest.Service::getJournalRetention)
        .containsOnly(tuple("greeter", "blabla", Duration.ofDays(2).toMillis()));
  }

  @Test
  public void http11FallsBackToRequestResponse() throws IOException {
    var endpointManifest = discover(restClient(HttpClient.Version.HTTP_1_1));

    assertThat(endpointManifest.getProtocolMode())
        .isEqualTo(EndpointManifestSchema.ProtocolMode.REQUEST_RESPONSE);
  }

  @Test
  public void plainAppStaysNonWeb() {
    // The starter must not force a web server: without a web provider, a plain app stays non-web.
    assertThat(new SpringApplicationBuilder(Greeter.class).build().getWebApplicationType())
        .isEqualTo(WebApplicationType.NONE);
  }
}
