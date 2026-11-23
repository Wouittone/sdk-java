// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restate.sdk.core.generated.manifest.EndpointManifestSchema;
import dev.restate.sdk.springboot.RestateHttpEndpointBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    classes = WebfluxApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "restate.sdk.http.port=0")
public class WebfluxServerTest {

  @Autowired private RestateHttpEndpointBean bean;

  @LocalServerPort private int serverPort;

  private HttpResponse<String> discover(HttpClient.Version version) throws Exception {
    var client = HttpClient.newBuilder().version(version).build();
    return client.send(
        HttpRequest.newBuilder()
            .GET()
            .version(version)
            .uri(URI.create("http://localhost:" + bean.actualPort() + "/discover"))
            .header("Accept", "application/vnd.restate.endpointmanifest.v4+json")
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void discoverOverH2c() throws Exception {
    var response = discover(HttpClient.Version.HTTP_2);

    assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_2);
    assertThat(response.statusCode()).isEqualTo(200);
    var manifest = new ObjectMapper().readValue(response.body(), EndpointManifestSchema.class);
    assertThat(manifest.getProtocolMode())
        .isEqualTo(EndpointManifestSchema.ProtocolMode.BIDI_STREAM);
  }

  @Test
  public void discoverOverHttp11() throws Exception {
    var response = discover(HttpClient.Version.HTTP_1_1);

    assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    assertThat(response.statusCode()).isEqualTo(200);
    var manifest = new ObjectMapper().readValue(response.body(), EndpointManifestSchema.class);
    assertThat(manifest.getProtocolMode())
        .isEqualTo(EndpointManifestSchema.ProtocolMode.REQUEST_RESPONSE);
  }

  @Test
  public void separatePort() {
    assertThat(bean.actualPort()).isPositive();
    assertThat(bean.actualPort()).isNotEqualTo(serverPort);
  }
}
