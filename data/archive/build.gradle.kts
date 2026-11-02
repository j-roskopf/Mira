plugins {
    id("mira.data")
    // data:entries exposes core:media, so Compose is already on the wasm test classpath. The
    // Compose plugins register the Skiko runtime unpack/copy the wasm test bundle needs, and the
    // runtime dependency below satisfies the compiler plugin's classpath check.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
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
                implementation(libs.compose.runtime)
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
