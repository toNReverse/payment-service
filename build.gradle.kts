plugins {
    application
}

group = "io.github.tonreverse"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "io.github.tonreverse.payment.App"
}

dependencies {
    implementation(libs.javalin)
    implementation(libs.jackson.databind)
    implementation(libs.hikaricp)
    implementation(libs.jedis)
    // Only needed at runtime: the code talks to javax.sql.DataSource, never to the driver directly.
    runtimeOnly(libs.postgresql)
    // SLF4J backend. The code depends only on the SLF4J API (it comes with Javalin).
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
