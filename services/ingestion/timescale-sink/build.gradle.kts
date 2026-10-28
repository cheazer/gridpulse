plugins {
    id("gridpulse.spring-boot-app")
}

description = "Consumes GridPulse events from Kafka and upserts them into TimescaleDB"

dependencies {
    implementation(project(":events-common"))
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Tests apply the real migrations from db/migrations, exactly as the migrate job does.
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.awaitility:awaitility")
}

// Golden example events from the repo root, used as test input.
tasks.processTestResources {
    from(rootDir.resolve("../../schemas/events/v1/examples/valid")) {
        into("gridpulse/examples/v1/valid")
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("gridpulse.migrations", rootDir.resolve("../../db/migrations").absolutePath)
}
