import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val appVersion = "1.0.0"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Windows / Linux. Everything in commonMain must stay free of Android APIs so it compiles here.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
            languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
        }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.coil.compose)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
            implementation(libs.mlkit.barcode.scanning)
            implementation(libs.androidx.exifinterface)
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.ktor.client.okhttp)
            }
        }

        val desktopTest by getting {
            // The database and backup tests are plain JVM code, so run them on desktop too.
            kotlin.srcDir("src/androidUnitTest/kotlin")
        }
    }
}

android {
    namespace = "io.github.jman9700.quartermaster"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.jman9700.quartermaster"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = appVersion
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,io.netty.versions.properties}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

sqldelight {
    databases {
        create("QuarterMasterDatabase") {
            packageName.set("io.github.jman9700.quartermaster.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.jman9700.quartermaster.desktop.MainKt"
        jvmArgs += "-Dquartermaster.version=$appVersion"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Quarter Master"
            packageVersion = appVersion
            description = "Pantry and household inventory"
            vendor = "jman9700"
            // From ./gradlew suggestRuntimeModules (java.sql is the SQLite JDBC driver).
            modules("java.instrument", "java.management", "java.sql", "jdk.unsupported")

            windows {
                menuGroup = "Quarter Master"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed so MSI upgrades replace the installed version instead of installing side by side.
                upgradeUuid = "6f4c2a9e-3b7d-4e1a-9c58-2d0f8b7a1e64"
            }
            linux {
                packageName = "quartermaster"
                menuGroup = "Office"
                shortcut = true
            }
        }
    }
}
