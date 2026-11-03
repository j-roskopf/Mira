plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.joetr.mira"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joetr.mira"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("mira.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("mira.versionName").getOrElse("0.1.0")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                val store = rootProject.file(storeFilePath)
                if (store.exists()) {
                    storeFile = store
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
