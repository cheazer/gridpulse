plugins {
    id("gridpulse.java-conventions")
    `java-library`
}

description = "OpenTelemetry for the Spring Boot apps: Spring Boot's own OTLP export, configured from OTEL_* variables"

dependencies {
    val springBootBom = platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    implementation(springBootBom)
    testImplementation(springBootBom)
    // Micrometer Observation bridged to OpenTelemetry tracing, Micrometer metrics over OTLP, and Spring Boot's
    // auto-configuration for both. Part of the API: every app that uses this module gets them.
    api("org.springframework.boot:spring-boot-starter-opentelemetry")

    testImplementation("org.springframework:spring-test")
}
