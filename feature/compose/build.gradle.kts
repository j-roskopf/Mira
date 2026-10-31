plugins {
    id("mira.feature")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":data:entries"))
                implementation(project(":core:media"))
            }
        }
    }
}
