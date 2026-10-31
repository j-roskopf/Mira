plugins {
    id("mira.compose.library")
    id("mira.metro")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":ui"))
                implementation(project(":core:platform"))
                implementation(libs.jetbrains.navigation3.ui)
                implementation(libs.serialization.json)
            }
        }
    }
}
