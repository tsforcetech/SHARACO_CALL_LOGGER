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
        versionCode = 3
        versionName = "1.2.0"
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
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
