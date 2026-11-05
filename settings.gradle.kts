pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Mira"

include(":androidApp")
include(":composeApp")
include(":domain")
include(":navigation")
include(":ui")
include(":core:database")
include(":core:network")
include(":core:platform")
include(":core:updates")
include(":core:media")
include(":data:auth")
include(":data:entries")
include(":data:archive")
include(":data:settings")
include(":feature:auth")
include(":feature:calendar")
include(":feature:day")
include(":feature:compose")
include(":feature:timeline")
include(":feature:media")
include(":feature:search")
include(":feature:settings")
