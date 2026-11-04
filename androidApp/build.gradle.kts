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
        versionCode = 3
        versionName = "1.2"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK is installable without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
}
