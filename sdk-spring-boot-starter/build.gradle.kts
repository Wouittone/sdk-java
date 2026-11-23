import org.gradle.api.tasks.testing.JvmTestSuite

plugins {
  `java-conventions`
  `java-library`
  `library-publishing-conventions`
  alias(libs.plugins.spring.dependency.management)
}

description = "Restate SDK Spring Boot starter"

dependencies {
  compileOnly(libs.jspecify)

  val excludeJackson =
      fun ProjectDependency.() {
        // Let spring bring jackson in
        exclude(group = "com.fasterxml.jackson")
        exclude(group = "com.fasterxml.jackson.core")
        exclude(group = "com.fasterxml.jackson.datatype")
      }

  // SDK dependencies
  api(project(":sdk-spring-boot"))
  api(project(":sdk-api"), excludeJackson)
  api(project(":client"), excludeJackson)
  api(project(":sdk-serde-jackson"), excludeJackson)
  api(project(":sdk-interceptor-micrometer"))
  runtimeOnly(project(":bytebuddy-proxy-support")) { isTransitive = true }

  // Spring boot starter brought in here for convenience
  api(libs.spring.boot.starter)
  // So that an app with no web stack still has a server for the Restate endpoint port
  api(libs.spring.boot.starter.reactor.netty)

  // Testing
  testImplementation(libs.spring.boot.starter.test)
  testAnnotationProcessor(project(":sdk-api-gen"))
  // We need these for the deployment manifest
  testImplementation(project(":sdk-core"))
  testImplementation(libs.jackson.annotations)
  testImplementation(libs.jackson.databind)
  testImplementation(project(":sdk-serde-jackson"))
  testImplementation(project(":sdk-testing"))
  testImplementation(libs.micrometer.observation.test)
  testImplementation(libs.micrometer.java11)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// Spring MVC on Tomcat, end to end (needs Docker for the Restate server).
testing {
  suites {
    register<JvmTestSuite>("tomcatTest") {
      useJUnitJupiter(libs.versions.junit.get())
      // App and service classes shared with the other container suite
      sources { java.srcDir("src/commonTest/java") }
      dependencies {
        implementation(project())
        implementation(libs.spring.boot.starter.test)
        implementation(libs.spring.boot.starter.web)
        implementation(libs.testcontainers)
        implementation(project(":sdk-core"))
        implementation(project(":client"))
      }
    }
    register<JvmTestSuite>("webfluxTest") {
      useJUnitJupiter(libs.versions.junit.get())
      // App and service classes shared with the other container suite
      sources { java.srcDir("src/commonTest/java") }
      dependencies {
        implementation(project())
        implementation(libs.spring.boot.starter.test)
        implementation(libs.spring.boot.starter.webflux)
        implementation(project(":sdk-core"))
      }
    }
  }
}

tasks.named("check") {
  dependsOn(testing.suites.named("tomcatTest"))
  dependsOn(testing.suites.named("webfluxTest"))
}

tasks.withType<JavaCompile> {
  val disabledClassesCodegen = listOf("dev.restate.sdk.springboot.java.GreeterNewApi")

  options.compilerArgs.addAll(
      listOf(
          "-parameters",
          "-Adev.restate.codegen.disabledClasses=${disabledClassesCodegen.joinToString(",")}",
      )
  )
}
