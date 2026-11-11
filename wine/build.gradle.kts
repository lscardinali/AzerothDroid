// GameNative's Wine engine (Winlator core, X server, input, container setup), taken from
// WoWForeverywhere 1.0.8 (GPL-3.0), itself GameNative 1.2.1 without its store backends.
// Kotlin/Java is built from source in src/main. The native libraries and the bundled runtime
// (Proton, DXVK, Box64, Turnip, libredirect) come prebuilt from that release's APK: see
// tools/stage_wine.py, which fills prebuilt/.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // Kept as upstream's, so its R, BuildConfig and JNI class names work unchanged.
    namespace = "app.gamenative"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a") }
        consumerProguardFiles("consumer-rules.pro")

        // Upstream reads these from its app module's BuildConfig.
        buildConfigField("String", "APPLICATION_ID", "\"com.lscardinali.azerothdroid\"")
        buildConfigField("boolean", "XR_BUILD", "false")
        buildConfigField("boolean", "GOLD", "false")
        buildConfigField("boolean", "MODERN_ANDROID", "true")
        buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add("prebuilt/jniLibs")
            assets.directories.add("prebuilt/assets")
        }
    }

    androidResources {
        // Already zstd/xz compressed.
        noCompress += listOf("tzst", "txz", "wcp")
    }

    lint {
        disable += "ExtraTranslation"
        disable += "MissingTranslation"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.settings.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.androidsvg)
    implementation(libs.commons.compress)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)
}
