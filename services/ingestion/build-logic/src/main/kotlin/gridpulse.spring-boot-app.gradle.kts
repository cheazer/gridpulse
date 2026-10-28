// Conventions for deployable Spring Boot applications.
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("gridpulse.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    // Spring Boot's BOM aligns versions of Spring, Kafka, Testcontainers, JDBC drivers and friends.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
}

tasks.named<Jar>("jar") {
    // Only the executable bootJar is shipped.
    enabled = false
}
