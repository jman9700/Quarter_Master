rootProject.name = "QuarterMaster"

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

// The server builds without the Android SDK: pass -PserverOnly (Docker, the server PC's deploy poller).
val serverOnly = providers.gradleProperty("serverOnly").isPresent

if (!serverOnly) {
    include(":client")
    project(":client").projectDir = file("Client")
}

include(":server")
project(":server").projectDir = file("Server")

// Shared/ is plain Kotlin source (the sync protocol) compiled into both modules, not a module of its own.
