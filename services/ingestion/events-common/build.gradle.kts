plugins {
    id("gridpulse.java-conventions")
    `java-library`
}

description = "Shared GridPulse event model: types, JSON codec, schema validation, IDs and settlement periods"

dependencies {
    // Part of the public API: callers work with Jackson trees and exceptions.
    api(libs.jackson.databind)
    implementation(libs.json.schema.validator)
}

// The schemas and golden examples live once, at the repo root (schemas/), and are
// packaged from there, so Java validates against exactly what the registry holds.
val schemaDir = rootDir.resolve("../../schemas/events/v1")

tasks.processResources {
    from(schemaDir) {
        include("*.schema.json")
        into("gridpulse/schemas/v1")
    }
}

tasks.processTestResources {
    from(schemaDir.resolve("examples")) {
        into("gridpulse/examples/v1")
    }
}
