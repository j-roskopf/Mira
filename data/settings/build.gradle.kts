plugins {
    id("mira.data")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:platform"))
            }
        }
    }
}
