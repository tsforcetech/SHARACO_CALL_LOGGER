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
        versionCode = 2
        versionName = "1.1.0"
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
}
