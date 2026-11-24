plugins {
  `kotlin-conventions`
  `library-publishing-conventions`
  alias(libs.plugins.ksp)
  alias(libs.plugins.spring.dependency.management)
}

description = "Restate SDK Spring Boot Kotlin starter"

configurations.all {
  // Gonna conflict with sdk-serde-kotlinx
  exclude(group = "dev.restate", module = "sdk-serde-jackson")
}

dependencies {
  compileOnly(libs.jspecify)

  // SDK dependencies
  api(project(":sdk-api-kotlin"))
  api(project(":client-kotlin"))
  api(project(":sdk-serde-kotlinx"))
  api(project(":sdk-spring-boot"))
  api(project(":sdk-interceptor-micrometer"))

  // Spring boot starter for kotlin dependencies brought in here for convenience. The web server
  // implementation is left to the application: an app without any web provider fails at startup
  // when the Restate endpoint looks for a container.
  api(libs.spring.boot.starter)
  implementation("org.jetbrains.kotlin:kotlin-reflect")

  // Testing
  kspTest(project(":sdk-api-kotlin-gen"))
  testImplementation(project(":sdk-testing"))
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.spring.boot.starter.test)
  // Web provider for the endpoint bean tests.
  testImplementation(libs.spring.boot.starter.reactor.netty)
  // Pin JUnit explicitly: the Spring Boot BOM manages an older junit-jupiter than the
  // junit-platform-launcher pinned below, which breaks the Gradle test executor.
  testImplementation(libs.junit.jupiter)
  testImplementation(project(":sdk-core"))
  testImplementation(libs.jackson.annotations)
  testImplementation(libs.jackson.databind)
  testImplementation(libs.micrometer.observation.test)
  testRuntimeOnly(libs.junit.platform.launcher)
}

ksp {
  val disabledClassesCodegen =
      listOf(
          "dev.restate.sdk.springboot.kotlin.GreeterNewApi",
      )
  arg("dev.restate.codegen.disabledClasses", disabledClassesCodegen.joinToString(","))
}
