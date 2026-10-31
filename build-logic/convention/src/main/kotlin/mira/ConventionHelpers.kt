package mira

import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.libraryNamespace(): String {
    val suffix = path
        .removePrefix(":")
        .split(":")
        .joinToString(".") { segment ->
            segment
                .replace(Regex("[^A-Za-z0-9_]"), "_")
                .lowercase()
        }
    return "com.joetr.mira.$suffix"
}

internal fun Project.miraIosTargetsEnabled(): Boolean {
    val configured = extensions.extraProperties.let { extra ->
        if (!extra.has("miraIosTargets")) return@let null
        extra.get("miraIosTargets")
    }
    return when (configured) {
        is Boolean -> configured
        is String -> configured.toBoolean()
        null -> path != ":androidApp"
        else -> true
    }
}

internal fun Project.configureMiraKmp(
    extension: KotlinMultiplatformExtension,
    enableIosTargets: Boolean = miraIosTargetsEnabled(),
) {
    extensions.configure(BasePluginExtension::class.java) {
        archivesName.set(path.removePrefix(":").replace(":", "-"))
    }

    extension.apply {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }

        jvm("desktop") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }

        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }

        if (enableIosTargets) {
            iosArm64()
            iosSimulatorArm64()
        }

        sourceSets.apply {
            all {
                languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
                languageSettings.optIn("kotlinx.coroutines.FlowPreview")
            }
            matching { it.name.contains("wasmJs", ignoreCase = true) }.configureEach {
                languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            }
            named("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        }
    }
}
