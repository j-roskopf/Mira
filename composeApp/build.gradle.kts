import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * jpackage MSI versions must be MAJOR.MINOR.BUILD where major <= 255, minor <= 255, build <= 65535.
 * Display versions like YYYY.MMDD.HHMM exceed those limits, so fall back to mira.versionCode.
 */
fun jpackagePackageVersion(versionName: String, versionCode: Int): String {
    val parts = versionName.split('.')
    if (parts.size == 3) {
        val major = parts[0].toIntOrNull()
        val minor = parts[1].toIntOrNull()
        val build = parts[2].toIntOrNull()
        if (major != null && minor != null && build != null &&
            major in 0..255 && minor in 0..255 && build in 0..65535
        ) {
            return "$major.$minor.$build"
        }
    }
    val minor = (versionCode / 65535).coerceAtMost(255)
    val build = (versionCode % 65535).let { if (it == 0) 1 else it }
    return "1.$minor.$build"
}

fun String.asJpackageMacSigningUserName(): String =
    removePrefix("Developer ID Application: ")
        .removePrefix("Developer ID Installer: ")
        .removePrefix("3rd Party Mac Developer Application: ")
        .removePrefix("3rd Party Mac Developer Installer: ")
        .trim()

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.roborazzi)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.joetr.mira.library"
        compileSdk = 36
        minSdk = 26
        androidResources.enable = true
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "mira.js" }
        }
        binaries.executable()
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlinx.coroutines.FlowPreview")
            languageSettings.optIn("coil3.annotation.ExperimentalCoilApi")
        }
        matching { it.name.contains("wasmJs", ignoreCase = true) }.configureEach {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":navigation"))
            implementation(project(":ui"))
            implementation(project(":core:database"))
            implementation(project(":core:network"))
            implementation(project(":core:platform"))
            implementation(project(":core:updates"))
            implementation(project(":core:media"))
            implementation(project(":data:auth"))
            implementation(project(":data:entries"))
            implementation(project(":data:archive"))
            implementation(project(":data:settings"))
            implementation(project(":feature:auth"))
            implementation(project(":feature:calendar"))
            implementation(project(":feature:day"))
            implementation(project(":feature:compose"))
            implementation(project(":feature:timeline"))
            implementation(project(":feature:search"))
            implementation(project(":feature:settings"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
            implementation(libs.compose.navigationevent)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.coroutines.swing)
                implementation(compose.desktop.currentOs)
            }
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.sqldelight.web.worker.driver)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
            implementation(npm("sql.js", "1.8.0"))
            implementation(npm("heic2any", "0.0.4"))
            implementation(devNpm("copy-webpack-plugin", "9.1.0"))
        }
        val desktopTest by getting {
            dependencies {
                implementation(project(":ui"))
                implementation(libs.roborazzi.compose.desktop)
                implementation(libs.roborazzi.core)
                implementation(libs.compose.ui.test.junit4)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.joetr.mira.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // jlink strips every JDK module that is not named here, and the failure only shows up
            // in a packaged build: SQLDelight's JDBC driver needs java.sql, Ktor's CIO engine
            // needs java.naming for DNS and jdk.unsupported for its buffer pooling.
            modules(
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.sql",
                "java.xml",
                "jdk.unsupported",
            )

            packageName = "Mira"

            // Desktop capture runs a bundled FFmpeg subprocess; scripts/build-ffmpeg.sh drops the
            // per-OS binary here in CI and FfmpegBinary resolves it through
            // compose.application.resources.dir at runtime.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            val versionName = providers.gradleProperty("mira.versionName").orElse("0.1.0").get()
            val versionCode = providers.gradleProperty("mira.versionCode").orElse("1").get().toIntOrNull() ?: 1
            packageVersion = jpackagePackageVersion(versionName, versionCode)
            val iconsDir = project.layout.projectDirectory.dir("src/desktopMain/resources/icons")
            macOS {
                bundleID = "com.joetr.mira"
                val macEntitlements =
                    project.layout.projectDirectory.file("desktop/macos/Mira.entitlements")
                entitlementsFile.set(macEntitlements)
                runtimeEntitlementsFile.set(macEntitlements)
                // Desktop capture opens the camera and microphone through the bundled FFmpeg.
                // macOS refuses both unless the packaged app declares why it wants them, and the
                // refusal is silent — no prompt, no error, just a capture that never yields a
                // frame.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSCameraUsageDescription</key>
                        <string>Mira uses the camera to record video entries in your journal.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Mira uses the microphone to record voice entries in your journal.</string>
                    """.trimIndent()
                }
                iconFile.set(iconsDir.file("icon.icns").asFile)
                signing {
                    identity.set(
                        providers.gradleProperty("compose.desktop.mac.signing.identity")
                            .map(String::asJpackageMacSigningUserName),
                    )
                }
            }
            windows {
                iconFile.set(iconsDir.file("icon.ico").asFile)
            }
            linux {
                iconFile.set(iconsDir.file("icon.png").asFile)
            }
        }
    }
}

/**
 * Signs the bundled FFmpeg helper with its own hardened-runtime capture capabilities.
 *
 * Camera and audio-input entitlements must be present on the executable opening the devices.
 * They are not inherited from the JVM app. Signing a nested binary also changes the bundle seal,
 * so the app is re-signed afterwards.
 */
val signBundledFfmpeg = tasks.register("signBundledFfmpeg") {
    description = "Signs the bundled FFmpeg helper with camera and microphone entitlements."
    val appEntitlements = layout.projectDirectory.file("desktop/macos/Mira.entitlements").asFile
    val helperEntitlements =
        layout.projectDirectory.file("desktop/macos/ffmpeg-helper.entitlements").asFile
    val binariesDir = layout.buildDirectory.dir("compose/binaries").get().asFile
    val identity = providers.gradleProperty("compose.desktop.mac.signing.identity").orNull ?: "-"
    val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    onlyIf { isMac }

    doLast {
        if (identity == "-") {
            logger.warn(
                "Mira is ad-hoc signed. macOS camera and microphone grants may be lost after " +
                    "rebuilding. Use a consistent Developer ID signing identity to preserve app identity.",
            )
        }
        fun codesign(vararg args: String) {
            val process = ProcessBuilder(listOf("codesign") + args)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "codesign failed: $output" }
        }

        val apps = binariesDir.walkTopDown()
            .maxDepth(4)
            .filter { it.name == "Mira.app" && it.isDirectory }
            .toList()

        apps.forEach { app ->
            val helper = File(app, "Contents/app/resources/ffmpeg")
            if (!helper.exists()) {
                logger.lifecycle("No bundled FFmpeg in ${app.name}; desktop capture will be unavailable.")
                return@forEach
            }
            // jpackage copies app resources without the executable bit, and FfmpegBinary skips
            // anything it cannot execute — so without this the app silently falls back to
            // whatever ffmpeg is on PATH, which is outside the bundle and gets no camera access.
            check(helper.setExecutable(true, false)) { "could not make $helper executable" }

            codesign(
                "--force", "--sign", identity, "--timestamp=none",
                "--options", "runtime",
                "--identifier", "com.joetr.mira.ffmpeg",
                "--entitlements", helperEntitlements.absolutePath,
                helper.absolutePath,
            )
            // Re-seal the bundle: changing a nested binary invalidates the outer signature.
            codesign(
                "--force", "--sign", identity, "--timestamp=none",
                "--options", "runtime",
                "--entitlements", appEntitlements.absolutePath,
                app.absolutePath,
            )
            logger.lifecycle("Signed bundled FFmpeg and re-sealed ${app.name}")
        }
    }
}

listOf(
    "createDistributable",
    "createReleaseDistributable",
    "packageDmg",
    "packageReleaseDmg",
).forEach { name ->
    tasks.matching { it.name == name }.configureEach { finalizedBy(signBundledFfmpeg) }
}

roborazzi {
    outputDir.set(file("src/desktopTest/resources/roborazzi"))
}
