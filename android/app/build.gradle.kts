plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hermes.messenger"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "2.1.14"

        // Read API token from local.properties (gitignored)
        val localPropsFile = rootProject.file("local.properties")
        var apiToken = ""
        var serverUrl = "https://your-server.tailnet.ts.net/"
        if (localPropsFile.exists()) {
            localPropsFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("API_TOKEN=")) apiToken = trimmed.substringAfter("API_TOKEN=")
                if (trimmed.startsWith("SERVER_URL=")) serverUrl = trimmed.substringAfter("SERVER_URL=")
            }
        }
        buildConfigField("String", "API_TOKEN", "\"$apiToken\"")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    flavorDimensions += "target"
    productFlavors {
        create("hermes") {
            dimension = "target"
            applicationId = "com.hermes.messenger"
        }
    }

    signingConfigs {
        create("release") {
            // Load from local.properties or use debug keystore
            val keystorePath = rootProject.file("release.keystore")
            if (keystorePath.exists()) {
                storeFile = keystorePath
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystorePath = rootProject.file("release.keystore")
            if (keystorePath.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room DB
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")

    // Security — EncryptedSharedPreferences backed by Android Keystore
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // CameraX — camera preview for QR scanning
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit — barcode/QR scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    
    // Cronet — Chrome TLS stack for DPI bypass (JA3 fingerprint)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
