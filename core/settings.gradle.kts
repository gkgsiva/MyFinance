// Lets the pure-Kotlin core build on its own (no Android SDK needed):
//   ./gradlew -p core test
// When built as part of the main project this file is ignored.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "core"
