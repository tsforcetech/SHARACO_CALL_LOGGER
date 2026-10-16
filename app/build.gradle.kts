plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}
android {
    namespace = "com.sharaco.callwebhook"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sharaco.callwebhook"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "3.6.0"
    }
    val signingFile = System.getenv("SIGNING_KEYSTORE_PATH")
    if (!signingFile.isNullOrBlank()) {
        signingConfigs {
            create("production") {
                storeFile = file(signingFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = false
                signingConfig = signingConfigs.getByName("production")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("androidx.core:core:1.15.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
