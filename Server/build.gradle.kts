plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    application
}

// Kept in step with the app's version (Client/build.gradle.kts).
version = "2.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Runs on any Java 17+ (the server PC has 21, the Docker image uses 21).
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets {
        main {
            // The sync protocol, shared with the app.
            kotlin.srcDir("../Shared/src/commonMain/kotlin")
        }
        test {
            kotlin.srcDir("../Shared/src/commonTest/kotlin")
        }
    }
}

application {
    mainClass.set("io.github.jman9700.quartermaster.server.MainKt")
    applicationName = "quartermaster-server"
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
}

sqldelight {
    databases {
        create("ServerDatabase") {
            packageName.set("io.github.jman9700.quartermaster.server.db")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

// Bakes the version into the jar so /api/v1/info can report it.
tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("quartermaster-server.properties") { expand("version" to v) }
}
