import com.github.jk1.license.render.ReportRenderer

plugins {
  alias(libs.plugins.dependency.license.report)
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.dokka)

  // https://github.com/gradle/gradle/issues/20084#issuecomment-1060822638
  id(libs.plugins.spotless.get().pluginId) apply false
}

val restateVersion = libs.versions.restate.get()

allprojects {
  apply(plugin = "com.diffplug.spotless")
  apply(plugin = "com.github.jk1.dependency-license-report")

  group = "dev.restate"
  version = restateVersion

  // Spotless provisions its formatters in ephemeral "spotless*" configurations. Resolution rules
  // from dependency-management plugins/BOMs (e.g. the Spring Boot BOM) leak into them and can pin
  // kotlin-compiler-embeddable below 2.0.20, which breaks ktfmt >= 0.55's Parser
  // (https://github.com/diffplug/spotless/issues/2646). Register this rule last (afterEvaluate)
  // so it wins and forces the Kotlin version ktfmt ships with.
  afterEvaluate {
    configurations.configureEach {
      if (name.startsWith("spotless")) {
        resolutionStrategy.eachDependency {
          if (requested.group == "org.jetbrains.kotlin") {
            useVersion("2.2.0")
            because("ktfmt >= 0.55 requires Kotlin >= 2.0.20")
          }
        }
      }
    }
  }

  configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlinGradle { ktfmt() }

    format("properties") {
      target("**/*.properties")
      trimTrailingWhitespace()
    }
  }

  tasks.named("check") { dependsOn("checkLicense") }

  licenseReport {
    renderers = arrayOf<ReportRenderer>(com.github.jk1.license.render.CsvReportRenderer())

    excludeBoms = true

    excludes =
        arrayOf(
            "io.vertx:vertx-stack-depchain", // Vertx bom file
            "com.google.guava:guava-parent", // Guava bom
            // kotlinx dependencies are APL 2, but somehow the plugin doesn't recognize that.
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains.kotlinx:kotlinx-serialization-core",
            "org.jetbrains.kotlinx:kotlinx-serialization-json",
        )

    allowedLicensesFile = file("$rootDir/config/allowed-licenses.json")
    filters =
        arrayOf(
            com.github.jk1.license.filter.LicenseBundleNormalizer(
                "$rootDir/config/license-normalizer-bundle.json",
                true,
            )
        )
  }
}

// Dokka configuration (Dokka Gradle plugin v2). The root project is the aggregator: each
// documented module applies the Dokka plugin and is declared as a `dokka(project(...))`
// dependency, then `./gradlew :dokkaGenerate` produces the aggregated HTML under build/dokka/html.
val dokkaDocumentedProjects =
    subprojects.filter {
      it.name !in
          setOf(
              "sdk-api",
              "sdk-api-gen",
              "sdk-fake-api",
              "examples",
              "sdk-aggregated-javadocs",
              "admin-client",
              "integration-client",
              "test-services",
          )
    }

dokkaDocumentedProjects.forEach { p -> p.plugins.apply("org.jetbrains.dokka") }

dependencies { dokkaDocumentedProjects.forEach { add("dokka", project(it.path)) } }

nexusPublishing {
  repositories {
    sonatype {
      nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
      snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))

      username.set(System.getenv("MAVEN_CENTRAL_USERNAME") ?: return@sonatype)
      password.set(System.getenv("MAVEN_CENTRAL_TOKEN") ?: return@sonatype)
    }
  }
}
