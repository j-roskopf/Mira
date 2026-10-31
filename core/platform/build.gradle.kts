plugins {
    id("mira.kmp.library")
}

import java.util.Properties

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.coroutines.core)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.exifinterface)
                implementation(libs.androidx.activity.compose)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.coroutines.swing)
            }
        }
        wasmJsMain {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }
    }
}

tasks.register("generateMiraConfig") {
    val configOutput = layout.buildDirectory.dir("generated/miraConfig/kotlin")
    val localPropertiesFile = rootProject.layout.projectDirectory.file("local.properties")
    val gradlePropertiesFile = rootProject.layout.projectDirectory.file("gradle.properties")
    val versionName = rootProject.providers.gradleProperty("mira.versionName").orElse("0.1.0")

    // Invalidate when local.properties or gradle.properties changes.
    inputs.files(localPropertiesFile).optional().withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(gradlePropertiesFile).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(configOutput)
    doLast {
        fun readGoogleServiceInfoIosClientId(): String? {
            val plist = rootProject.layout.projectDirectory.file("iosApp/iosApp/GoogleService-Info.plist").asFile
            if (!plist.exists()) return null
            val match = Regex("<key>CLIENT_ID</key>\\s*<string>([^<]+)</string>").find(plist.readText())
            return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }

        fun readLocalProperty(name: String): String? {
            val file = localPropertiesFile.asFile
            if (!file.exists()) return null
            val props = Properties()
            file.inputStream().use { props.load(it) }
            return props.getProperty(name)?.takeIf { it.isNotBlank() }
        }

        fun resolve(gradleKey: String, envKey: String, localKey: String): String =
            providers.gradleProperty(gradleKey).orNull
                ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
                ?: readLocalProperty(localKey)
                ?: ""

        val apiKey = resolve("mira.firebase.apiKey", "FIREBASE_WEB_API_KEY", "mira.firebase.apiKey")
        val projectId = resolve("mira.firebase.projectId", "FIREBASE_PROJECT_ID", "mira.firebase.projectId")
        val authDomain = resolve("mira.firebase.authDomain", "FIREBASE_AUTH_DOMAIN", "mira.firebase.authDomain")
            .ifBlank { if (projectId.isNotBlank()) "$projectId.firebaseapp.com" else "" }
        val storageBucket = resolve("mira.firebase.storageBucket", "FIREBASE_STORAGE_BUCKET", "mira.firebase.storageBucket")
            .ifBlank { if (projectId.isNotBlank()) "$projectId.appspot.com" else "" }
        val functionsRegion = resolve(
            "mira.firebase.functionsRegion",
            "FIREBASE_FUNCTIONS_REGION",
            "mira.firebase.functionsRegion",
        ).ifBlank { "us-central1" }
        val versionNameValue = versionName.get()
        val googleWebClientId = resolve(
            "mira.google.webClientId",
            "MIRA_GOOGLE_WEB_CLIENT_ID",
            "mira.google.webClientId",
        )
        val googleWebClientSecret = resolve(
            "mira.google.webClientSecret",
            "MIRA_GOOGLE_WEB_CLIENT_SECRET",
            "mira.google.webClientSecret",
        )
        val googleIosClientId = resolve(
            "mira.google.iosClientId",
            "MIRA_GOOGLE_IOS_CLIENT_ID",
            "mira.google.iosClientId",
        ).ifBlank { readGoogleServiceInfoIosClientId().orEmpty() }

        fun esc(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

        fun googleIosUrlScheme(clientId: String): String? {
            if (!clientId.endsWith(".apps.googleusercontent.com")) return null
            val prefix = clientId.removeSuffix(".apps.googleusercontent.com")
            return "com.googleusercontent.apps.$prefix"
        }

        val dir = configOutput.get().asFile
        dir.mkdirs()
        dir.resolve("com/joetr/mira/platform/MiraConfig.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.joetr.mira.platform

                object MiraConfig {
                    const val FIREBASE_API_KEY: String = "${esc(apiKey)}"
                    const val FIREBASE_PROJECT_ID: String = "${esc(projectId)}"
                    const val FIREBASE_AUTH_DOMAIN: String = "${esc(authDomain)}"
                    const val FIREBASE_STORAGE_BUCKET: String = "${esc(storageBucket)}"
                    const val FIREBASE_FUNCTIONS_REGION: String = "${esc(functionsRegion)}"
                    const val GOOGLE_WEB_CLIENT_ID: String = "${esc(googleWebClientId)}"
                    const val GOOGLE_WEB_CLIENT_SECRET: String = "${esc(googleWebClientSecret)}"
                    const val GOOGLE_IOS_CLIENT_ID: String = "${esc(googleIosClientId)}"
                }

                object MiraBuildInfo {
                    const val versionName: String = "${esc(versionNameValue)}"
                    const val githubOwner: String = "j-roskopf"
                    const val githubRepo: String = "Mira"
                }
                """.trimIndent(),
            )
        }
        googleIosUrlScheme(googleIosClientId)?.let { scheme ->
            configOutput.get().asFile.parentFile.resolve("ios-oauth-url-scheme.txt").writeText(scheme)
        }
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/miraConfig/kotlin"))
        }
    }
}

tasks.configureEach {
    if (name.contains("compile", ignoreCase = true) && name.contains("Kotlin", ignoreCase = true) ||
        name.contains("compile", ignoreCase = true) && name.contains("Main", ignoreCase = true)
    ) {
        dependsOn("generateMiraConfig")
    }
}
