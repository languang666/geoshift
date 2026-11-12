plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.geoshift"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.geoshift"
        minSdk = 26
        targetSdk = 35
        versionCode = 50
        versionName = "3.1.0"
        // Native map code is per ABI. 64-bit ARM only (32-bit phones are no longer supported: it halves the map library);
        // build with -Pemulator to add x86_64 for the emulator.
        ndk {
            abiFilters += "arm64-v8a"
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed afterwards with the existing private key (see build-release.sh).
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        // The AMap bundle's location SDK is never started; drop its native part.
        jniLibs.excludes += "**/libapssdk.so"
        // Compressed native code: the AMap map library is 34 MB raw, 13 MB compressed (download size halves).
        jniLibs.useLegacyPackaging = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("dev.chrisbanes.haze:haze-glass:2.1.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // Official AMap map + search SDK; only initialised after the user saves their own Android key.
    implementation("com.amap.api:3dmap-location-search:11.3.100_loc11.3.101_sea9.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
