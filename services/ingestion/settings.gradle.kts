pluginManagement {
    // Shared build conventions (Java 21, compiler flags, formatting, JUnit) live in an included build.
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "gridpulse-ingestion"

include("events-common")
