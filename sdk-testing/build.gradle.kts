plugins {
  `java-conventions`
  `kotlin-conventions`
  `java-library`
  `library-publishing-conventions`
}

description = "Restate SDK testing tools"

dependencies {
  api(project(":sdk-common"))
  api(project(":client"))
  api(libs.junit.api)
  api(libs.testcontainers)

  implementation(project(":admin-client"))
  implementation(project(":sdk-http-vertx"))
  api(libs.slf4j.api)
  implementation(libs.vertx.core)

  testImplementation(project(":sdk-api"))
  testAnnotationProcessor(project(":sdk-api-gen"))
  testImplementation(project(":sdk-serde-jackson"))
  testImplementation(libs.assertj)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.log4j.slf4j2.impl)
  testRuntimeOnly(libs.log4j.core)
  testRuntimeOnly(libs.junit.platform.launcher)
}
