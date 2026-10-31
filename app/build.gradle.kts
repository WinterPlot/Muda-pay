plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Push notifications: the Firebase plugin is only applied when you add app/google-services.json.
// Without that file the app still builds; push is simply switched off.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
// Stable signing key. Google sign-in only works when the app's SHA-1 fingerprint is registered in Google Cloud,
// and GitHub's runners create a NEW random debug key on every run (new SHA-1 every build = Google sign-in fails).
// CI writes your own keystore to app/mudapay.keystore from a secret (see README), so every build has the same SHA-1.
val mudaKeystore = file("mudapay.keystore")
fun signingValue(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)

android {
    namespace = "io.mudapay.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.mudapay.mobile"
        minSdk = 23
        targetSdk = 35
        versionCode = 23
        versionName = "3.7.0"
    }
    if (mudaKeystore.exists()) {
        signingConfigs {
            create("mudapay") {
                storeFile = mudaKeystore
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("debug") { signingConfig = signingConfigs.getByName("mudapay") }
            getByName("release") { signingConfig = signingConfigs.getByName("mudapay") }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Agent locator map (OpenStreetMap tiles, no API key). Swap the tile source in AgentLocator.kt for a paid provider before heavy production use.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Background check for new notifications (also restarted after a reboot)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // QR codes (drawing the dot-style code + reading it). If app/libs/core-3.5.3.jar is present it is used (works offline);
    // otherwise the same version is downloaded from Maven. Never both, so there are no duplicate classes.
    if (file("libs/core-3.5.3.jar").exists()) implementation(files("libs/core-3.5.3.jar")) else implementation("com.google.zxing:core:3.5.3")
    // Camera for "Scan to pay" (CameraX); the QR codes themselves are read by ZXing above, so Google Play services are not needed.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // Sign in with Google (Credential Manager + Google ID)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}
