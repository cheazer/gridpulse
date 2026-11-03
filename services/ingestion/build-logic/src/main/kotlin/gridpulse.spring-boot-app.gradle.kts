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
    // Kubernetes probes: Actuator's liveness and readiness groups at /actuator/health/{liveness,readiness}, served on
    // port 8080. The apps serve nothing else over HTTP; the web server is there for the probes alone.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}

tasks.named<Jar>("jar") {
    // Only the executable bootJar is shipped.
    enabled = false
}
