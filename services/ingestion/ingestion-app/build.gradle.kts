plugins {
    id("gridpulse.spring-boot-app")
}

description = "Polls public UK grid data sources and publishes normalised events to Kafka"

dependencies {
    implementation(project(":events-common"))
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-redpanda")
    testImplementation("org.awaitility:awaitility")
}
