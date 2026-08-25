plugins {
  `java-conventions`
  `kotlin-conventions`
  `java-library`
  `library-publishing-conventions`
}

description = "Restate SDK HTTP implementation based on Vert.x"

dependencies {
  compileOnly(libs.jspecify)

  api(project(":sdk-common"))
  implementation(project(":sdk-core"))

  // Vert.x
  implementation(libs.vertx.core)

  // Observability
  implementation(libs.opentelemetry.api)
  api(libs.slf4j.api)
  // Facade only: log4j-core/logback are `optional` in its POM, so no backend is pulled in here.
  // Users pick the implementation (see the logging section of the README).
  api(libs.reactiverse.contextual.logging) {
    // Version of vertx is pinned above
    exclude(group = "io.vertx")
  }
}
