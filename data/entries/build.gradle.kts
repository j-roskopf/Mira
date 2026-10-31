plugins {
    id("mira.data")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                api(project(":core:media"))
                implementation(project(":data:auth"))
                implementation(libs.ktor.client.core)
            }
        }
    }
}
