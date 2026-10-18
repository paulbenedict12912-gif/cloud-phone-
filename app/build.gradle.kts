plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vanta.cloudphone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vanta.cloudphone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.webrtc:google-webrtc:1.0.32006")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
