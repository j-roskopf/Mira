plugins {
    id("mira.compose.library")
    id("mira.metro")
}

tasks.named<org.gradle.api.tasks.testing.Test>("desktopTest") {
    // Exercise the same trimmed helper we ship, not a full Homebrew FFmpeg found on PATH.
    val helperDir = rootProject.layout.buildDirectory.dir("ffmpeg").get().asFile
    val helper = helperDir.resolve(if (System.getProperty("os.name").startsWith("Windows")) "ffmpeg.exe" else "ffmpeg")
    if (helper.isFile) {
        systemProperty("compose.application.resources.dir", helperDir.absolutePath)
        inputs.file(helper)
    }
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":core:platform"))
                implementation(libs.coroutines.core)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.camera.core)
                implementation(libs.androidx.camera.camera2)
                implementation(libs.androidx.camera.lifecycle)
                implementation(libs.androidx.camera.view)
                implementation(libs.androidx.activity.compose)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                // Skiko's native library. Without it every JPEG decode throws
                // UnsatisfiedLinkError and the preview tests silently measure nothing — the app
                // gets it through compose.desktop.currentOs, the test classpath does not.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
