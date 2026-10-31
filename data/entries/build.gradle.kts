plugins {
    id("mira.data")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:auth"))
                implementation(libs.ktor.client.core)
            }
        }
    }
}
