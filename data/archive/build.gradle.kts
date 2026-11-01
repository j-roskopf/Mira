plugins {
    id("mira.data")
}

kotlin {
    sourceSets {
        named("desktopTest") {
            dependencies { implementation(libs.sqldelight.sqlite.driver) }
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
                implementation(project(":data:auth"))
                implementation(project(":data:entries"))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
    }
}
