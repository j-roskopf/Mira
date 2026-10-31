import mira.libs

plugins {
    id("mira.kmp.library")
    kotlin("plugin.serialization")
}

kotlin {
    sourceSets {
        named("commonMain") {
            dependencies {
                implementation(libs.findLibrary("serialization-json").get())
                api(libs.findLibrary("kotlinx-datetime").get())
                implementation(project(":core:platform"))
            }
        }
    }
}
