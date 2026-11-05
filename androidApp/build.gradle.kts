import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "at.zentriert.setlist"
    compileSdk = 35
    // Needed here as well so the native libraries from :shared get stripped in release builds.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "at.zentriert.setlist"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        // Host of the band server, so https://<host>/join and /s/… open in the app (see server/README.md).
        // Set as Gradle property setlistAppLinkHost (CI: variable SETLIST_APP_LINK_HOST) or in local.properties.
        manifestPlaceholders["appLinkHost"] = (project.findProperty("setlistAppLinkHost") as String?)?.takeIf { it.isNotBlank() }
            ?: localProperty("setlistAppLinkHost") ?: "setlist-server-814326728745.europe-west1.run.app"
    }

    // Release signing: the keystore comes from the environment (CI secrets or a local shell), see README.
    // Without it the APK is signed with the debug key, so a local build stays installable.
    val keystorePath = System.getenv("SETLIST_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (keystorePath != null) create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SETLIST_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SETLIST_KEY_ALIAS") ?: "setlist"
            keyPassword = System.getenv("SETLIST_KEY_PASSWORD") ?: System.getenv("SETLIST_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        // The lint checks shipped with the multiplatform lifecycle 2.9 artifacts crash on AGP 8.7's lint.
        disable += "NullSafeMutableLiveData"
    }
}

fun localProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(name)?.takeIf { it.isNotBlank() }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
}
