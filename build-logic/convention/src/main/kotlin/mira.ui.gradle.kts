import mira.libs

plugins {
    id("mira.compose.library")
}

kotlin {
    sourceSets {
        all {
            languageSettings.optIn("coil3.annotation.ExperimentalCoilApi")
        }
        named("commonMain") {
            dependencies {
                api(project(":domain"))
                implementation(libs.findLibrary("coroutines-core").get())
                implementation(libs.findLibrary("coil-compose").get())
                implementation(libs.findLibrary("coil-network-ktor3").get())
            }
        }
    }
}
