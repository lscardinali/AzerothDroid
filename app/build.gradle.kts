plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lscardinali.azerothdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lscardinali.azerothdroid"
        minSdk = 26
        targetSdk = 36
        // CI passes its run number, so each build can install over the one before.
        versionCode = System.getenv("AZEROTHDROID_VERSION_CODE")?.toInt() ?: 1
        // CI passes the version from the release tag.
        versionName = System.getenv("AZEROTHDROID_VERSION_NAME") ?: "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Android only updates an app in place, keeping its realm data, when the signing key
    // matches. Without this key set, builds use the machine's own debug key.
    val sharedKeystore = System.getenv("AZEROTHDROID_KEYSTORE")
    val sharedSigning = sharedKeystore?.let {
        signingConfigs.create("shared") {
            storeFile = file(it)
            storePassword = System.getenv("AZEROTHDROID_KEYSTORE_PASSWORD")
            keyAlias = "azerothdroid"
            keyPassword = System.getenv("AZEROTHDROID_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            sharedSigning?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Installed from GitHub, not a store, so an APK must always be signed to install.
            signingConfig = sharedSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // Produced by tools/stage_mariadb.py (and later stagers).
            jniLibs.srcDir(rootProject.file("native/out/jniLibs"))
            assets.srcDir(rootProject.file("native/out/assets"))
        }
    }

    androidResources {
        // sql.zip is already deflated; storing it avoids a second compression pass.
        noCompress += "zip"
    }

    // That block is encrypted for Google Play only; outside the store it is unreadable bytes.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        // Native server binaries are shipped as lib*.so and exec'd from nativeLibraryDir,
        // so they must be extracted to disk at install time.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
