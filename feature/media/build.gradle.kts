plugins {
    id("mira.feature")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core:media"))
            }
        }
    }
}
