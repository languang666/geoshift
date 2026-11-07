plugins { id("com.android.application") }
android {
    namespace = "test.probe"
    compileSdk = 35
    buildToolsVersion = "37.0.0"
    defaultConfig { applicationId = "test.probe"; minSdk = 31; targetSdk = 35; versionCode = 1; versionName = "1" }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    // Google Play services fused location, as used by most app SDKs outside China.
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
