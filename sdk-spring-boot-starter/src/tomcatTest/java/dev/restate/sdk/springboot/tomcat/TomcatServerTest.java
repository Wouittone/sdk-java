// Copyright (c) 2023 - Restate Software, Inc., Restate GmbH
//
// This file is part of the Restate Java SDK,
// which is released under the MIT license.
//
// You can find a copy of the license in file LICENSE in the root
// directory of this repository or package, or at
// https://github.com/restatedev/sdk-java/blob/main/LICENSE
package dev.restate.sdk.springboot.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.restate.client.Client;
import dev.restate.sdk.core.generated.manifest.EndpointManifestSchema;
import dev.restate.sdk.springboot.RestateHttpEndpointBean;
import dev.restate.sdk.springboot.testapp.Echo;
import dev.restate.sdk.springboot.testapp.RestateTestApp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = RestateTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "restate.sdk.http.port=0")
public class TomcatServerTest {

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

  @Test
  @Timeout(60)
  public void invokeThroughRestate() throws Exception {
    // The Restate server runs in a container and must reach the endpoint on the host.
    Testcontainers.exposeHostPorts(bean.actualPort());
    try (var restate =
        new GenericContainer<>(DockerImageName.parse("ghcr.io/restatedev/restate:main"))
            .withExposedPorts(8080, 9070)) {
      restate.start();

      var registerResponse =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder()
                      .POST(
                          HttpRequest.BodyPublishers.ofString(
                              "{\"uri\":\"http://host.testcontainers.internal:"
                                  + bean.actualPort()
                                  + "\"}"))
                      .uri(
                          URI.create(
                              "http://localhost:" + restate.getMappedPort(9070) + "/deployments"))
                      .header("Content-Type", "application/json")
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(registerResponse.statusCode())
          .as("register response: %s", registerResponse.body())
          .isBetween(200, 299);

      // Restate dials http:// deployments over HTTP/2 prior knowledge, so the invocation goes
      // through Tomcat's h2c path and the servlet adapter in bidirectional streaming mode.
      Client client = Client.connect("http://localhost:" + restate.getMappedPort(8080));
      Echo echo = client.service(Echo.class);
      // The deployment is discovered asynchronously: retry until it is ready. Each attempt gets
      // its own timeout so a hung invocation fails fast instead of blocking the test worker.
      var attempts = Executors.newCachedThreadPool();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (true) {
          try {
            assertThat(attempts.submit(() -> echo.echo("hi")).get(10, TimeUnit.SECONDS))
                .isEqualTo("hi");
            return;
          } catch (Exception e) {
            if (System.nanoTime() > deadline) {
              throw e;
            }
            Thread.sleep(500);
          }
        }
      } finally {
        attempts.shutdownNow();
      }
    }
  }
}
