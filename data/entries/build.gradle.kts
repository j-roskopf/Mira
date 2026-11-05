plugins {
    id("mira.data")
}

kotlin {
    sourceSets {
        named("desktopTest") {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.ktor.client.mock)
            }
        }
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
