plugins {
  `java-conventions`
  `java-library`
  `library-publishing-conventions`
  alias(libs.plugins.spring.dependency.management)
}

description = "Restate SDK Spring Boot integration"

dependencies {
  compileOnly(libs.jspecify)

  // This generates the metadata needed for the configuration properties
  annotationProcessor(libs.spring.boot.configuration.processor)

  val excludeJackson =
      fun ProjectDependency.() {
        // Let spring bring jackson in
        exclude(group = "com.fasterxml.jackson")
        exclude(group = "com.fasterxml.jackson.core")
        exclude(group = "com.fasterxml.jackson.datatype")
      }

  // SDK deps
  implementation(project(":sdk-common"), excludeJackson)
  implementation(project(":client"), excludeJackson)
  implementation(project(":sdk-core"), excludeJackson)
  implementation(project(":sdk-request-identity"), excludeJackson)

  implementation(libs.spring.boot)
  implementation(libs.spring.boot.starter.logging)
  // Versions of spring-web/reactor-core come from the Spring Boot BOM imported below.
  implementation(libs.spring.web)
  implementation(libs.reactor.core)

  // Optional web containers: only referenced behind ClassUtils.isPresent checks, so a missing
  // container never throws NoClassDefFoundError. tomcat-embed-core also provides the servlet API.
  compileOnly(libs.tomcat.embed.core)
  compileOnly(libs.reactor.netty.http)

  // Optional dependencies to load tracing-related stuff.
  compileOnly(project(":sdk-api"))
  compileOnly(project(":sdk-api-kotlin"))
  compileOnly(libs.micrometer.java11)

  // In principle kotlin won't need this, but it's needed by the SDK core anyway, so we just import
  // this in.
  implementation(libs.spring.boot.starter.json)

  testImplementation(libs.spring.boot.starter)
  testImplementation(libs.spring.boot.starter.json)
  testImplementation(libs.spring.boot.starter.test)
  // Pin JUnit explicitly: the Spring Boot BOM manages an older junit-jupiter than the
  // junit-platform-launcher pinned below, which breaks the Gradle test executor.
  testImplementation(libs.junit.jupiter)
  testImplementation(project(":sdk-api"))
  testImplementation(libs.reactor.netty.http)
  testImplementation(libs.tomcat.embed.core)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// Supplies the versions of the catalog entries that have none (spring-web, reactor-core, ...).
dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
  }
}

tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }

tasks.withType<Javadoc> { isFailOnError = false }
