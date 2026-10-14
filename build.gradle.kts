plugins {
    application
    alias(libs.plugins.jooq.codegen)
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
    implementation(libs.flyway.core)
    implementation(libs.jooq)
    // Only needed at runtime: the code talks to javax.sql.DataSource, never to the driver directly.
    runtimeOnly(libs.postgresql)
    // Flyway finds its Postgres support through ServiceLoader at runtime.
    runtimeOnly(libs.flyway.database.postgresql)
    // SLF4J backend. The code depends only on the SLF4J API (it comes with Javalin).
    runtimeOnly(libs.logback.classic)

    // Lets the jOOQ code generator read the Flyway SQL files directly (see the jooq block below).
    jooqCodegen(libs.jooq.meta.extensions)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.testcontainers.postgresql)
}

// jOOQ generates one Java class per table from the same migration files Flyway runs, so the
// generated code cannot drift from the schema. No database is needed at build time; the
// integration tests then check the generated code against a real Postgres.
jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
            }
            target {
                packageName = "io.github.tonreverse.payment.persistence.jooq"
            }
        }
    }
}

// Compile the generated classes with the rest of main. Passing the task (not just its directory)
// also makes compileJava run jooqCodegen first.
sourceSets.main {
    java.srcDir(tasks.named("jooqCodegen"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Two test tasks over the same test classes, split by JUnit tag:
//   ./gradlew test             fast unit tests, no Docker needed
//   ./gradlew integrationTest  tests tagged "integration", which start Postgres with Testcontainers
// `./gradlew build` (and CI) runs both.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the integration tests (needs Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}
