plugins {
    id("mira.kmp.library")
    id("mira.metro")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":core:platform"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.serialization.json)
            }
        }
        androidMain {
            dependencies {
                // Firebase is reached entirely over REST from commonMain, so no native SDK here.
                implementation(libs.ktor.client.okhttp)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
        iosMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
    }
}
