import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    listOf(iosArm64() to "device", iosSimulatorArm64() to "simulator").forEach { (target, variant) ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        // Native audio engine (build with audio/tools/build_ios.sh on a Mac first).
        target.compilations.getByName("main").cinterops.create("setlistaudio") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/setlistaudio.def"))
            includeDirs(project.file("../audio/src/capi"))
            extraOpts("-libraryPath", project.file("../audio/build-ios/$variant/lib").absolutePath)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.io.core)
            implementation(libs.ktor.network)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.oboe)
            implementation(libs.androidx.webkit)
        }
    }
}

compose.resources {
    packageOfResClass = "at.zentriert.setlist.res"
    publicResClass = false
}

android {
    namespace = "at.zentriert.setlist.shared"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    // Native audio engine (audio/): metronome, click synth, Oboe output.
    externalNativeBuild {
        cmake {
            path = file("../audio/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures { prefab = true }
    // The alphaTab viewer (HTML/JS/fonts) is shared with iOS; Android serves it as assets.
    sourceSets.getByName("main").assets.srcDirs("web")
    lint {
        // The lint checks shipped with the multiplatform lifecycle 2.9 artifacts crash on AGP 8.7's lint.
        disable += "NullSafeMutableLiveData"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
