plugins {
  `java-conventions`
  `java-library`
  `library-publishing-conventions`
}

description = "Restate SDK APIs"

dependencies {
  compileOnly(libs.jspecify)
  compileOnly(libs.jetbrains.annotations)

  api(project(":sdk-common"))
  api(project(":sdk-serde-jackson"))

  api(libs.slf4j.api)

  runtimeOnly(project(":bytebuddy-proxy-support")) { isTransitive = true }
}
