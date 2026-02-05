rootProject.name = "fomovoi"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":composeApp")
include(":androidApp")

// Core modules
include(":core:audio")
include(":core:transcription")
include(":core:sharing")
include(":core:domain")
include(":core:data")

// Feature modules
include(":feature:recording")
include(":feature:history")
include(":feature:settings")
