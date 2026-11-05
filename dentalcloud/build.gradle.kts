// Dental Cloud SDK: Firebase data layer (one Firestore document per patient, lossless images in
// Firebase Storage). Used by the app (:app) and shareable with other clinic systems.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.spala.dental.cloud"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(platform("com.google.firebase:firebase-bom:35.1.0"))
    api("com.google.firebase:firebase-firestore")
    api("com.google.firebase:firebase-storage")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
