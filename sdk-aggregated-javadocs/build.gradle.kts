plugins {
  alias(libs.plugins.aggregate.javadoc)
  alias(libs.plugins.spring.dependency.management)
}

// Supplies the versions of catalog entries that have none (e.g. reactor-core from sdk-spring-boot)
// when the subprojects' compile classpaths are aggregated here.
dependencyManagement {
  imports {
    mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
  }
}

rootProject.subprojects
    .filter { it.name != "examples" }
    .forEach {
      it.plugins.withId("java") {
        // Add dependency
        dependencies.javadoc(it)

        tasks.javadoc { classpath += it.configurations["compileClasspath"] }
      }
    }

tasks.javadoc {
  title = "Restate SDK-Java documentation"
  options.windowTitle = "Restate SDK-Java documentation"
}
