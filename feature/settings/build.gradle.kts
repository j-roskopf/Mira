plugins {
    id("mira.feature")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":data:entries"))
                implementation(project(":data:archive"))
                implementation(project(":data:settings"))
                implementation(project(":data:auth"))
                implementation(project(":core:updates"))
            }
        }
    }
}
