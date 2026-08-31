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

group = "io.github.sumansinghs71"
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

// `./gradlew bootRun` is the local quickstart, so it defaults to the `dev`
// profile. That profile adds classpath:db/seed to spring.flyway.locations, which
// is what puts the demo fixtures behind Flyway's afterMigrate callback rather
// than behind a psql invocation that can run before the schema exists. Set
// SPRING_PROFILES_ACTIVE to run bootRun under any other profile.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "dev")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Chaos tests are slow; keep parallel off by default.
    maxParallelForks = 1
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// No --enable-preview anywhere, deliberately. It used to be here for pattern
// matching in switch, which is still preview on Java 17. Preview class files
// are stamped with the exact JDK build that produced them (version 61.65535)
// and every consuming JVM must opt in individually — the JMH bytecode
// generator forks its own JVM and does not, so `./gradlew jmh` failed with
// UnsupportedClassVersionError. IdempotencyService now uses `instanceof`
// patterns, final since Java 16, and the flag is gone from every task.
tasks.withType<JavaCompile> {
    options.release.set(17)
}

// Configure JMH tasks to use preview flags too
jmh {
    // Enough iterations that the reported error bar means something. With the
    // previous 2/3 the confidence interval on uniqueKeyPath was wider than its
    // own mean, which is not a measurement.
    warmupIterations.set(5)
    iterations.set(10)
    fork.set(2)
    timeUnit.set("ms")
    benchmarkMode.set(listOf("thrpt", "avgt"))
    resultFormat.set("JSON")
    humanOutputFile.set(layout.projectDirectory.file("benchmarks/jmh-output.txt"))
    resultsFile.set(layout.projectDirectory.file("benchmarks/jmh-results.json"))
}

tasks.withType<JMHTask> {
    // Make the bench actually use the project classpath including main.
    dependsOn("compileJava")
}
