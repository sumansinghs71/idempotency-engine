// =============================================================================
// IdemEngine — build configuration
// =============================================================================
// Java 17, Spring Boot 3.2.x, Flyway, Postgres, Testcontainers, JMH.
// Run:   ./gradlew bootRun
// Tests: ./gradlew test
// Bench: ./gradlew jmh
// =============================================================================

import me.champeau.jmh.JMHTask

plugins {
    java
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
    id("me.champeau.jmh") version "0.7.2"
}

group = "com.yourname"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Persistence
    runtimeOnly("org.postgresql:postgresql:42.7.3")
    implementation("org.flywaydb:flyway-core:10.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.10.0")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Observability
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers:1.20.0")
    testImplementation("org.testcontainers:postgresql:1.20.0")
    testImplementation("org.testcontainers:junit-jupiter:1.20.0")
    testImplementation("org.testcontainers:toxiproxy:1.20.0")
    testImplementation("org.awaitility:awaitility:4.2.1")
    testImplementation("org.assertj:assertj-core:3.25.3")

    // JMH
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Chaos tests are slow; keep parallel off by default.
    maxParallelForks = 1
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Enable Java preview features at test runtime (required for pattern matching in switch)
    // This adds --enable-preview to the JVM that runs tests.
    jvmArgs = listOf("--enable-preview")
}

// Ensure Java compilation uses --enable-preview so preview language features (pattern matching in switch)
// are accepted by the compiler. Also set release to 17 to match the toolchain.
tasks.withType<JavaCompile> {
    options.release.set(17)
    options.compilerArgs.add("--enable-preview")
}

// Ensure any JavaExec tasks (bootRun, custom run tasks) run with --enable-preview
tasks.withType<org.gradle.api.tasks.JavaExec> {
    jvmArgs = listOf("--enable-preview")
}

// Configure JMH tasks to use preview flags too
jmh {
    // Conservative defaults; override on the CLI.
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    timeUnit.set("ms")
    benchmarkMode.set(listOf("thrpt", "avgt"))
    resultFormat.set("JSON")
    humanOutputFile.set(layout.projectDirectory.file("benchmarks/jmh-output.txt"))
    resultsFile.set(layout.projectDirectory.file("benchmarks/jmh-results.json"))
}

tasks.withType<JMHTask> {
    // Make the bench actually use the project classpath including main.
    dependsOn("compileJava")
    // Run JMH worker JVMs with preview enabled
    jvmArgs = listOf("--enable-preview")
}
