plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.karen"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.karen"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/karen-release.jks")
            storePassword = (project.findProperty("KAREN_STORE_PASSWORD") as String?) ?: "karenKeystore2026"
            keyAlias = "karen"
            keyPassword = (project.findProperty("KAREN_KEY_PASSWORD") as String?) ?: "karenKeystore2026"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures { compose = true }

    // On-device Python runtime (Chaquopy, CPython 3.11) — powers the PPT
    // builder (src/main/python). ~60 MB, arm64-v8a only.
    chaquopy {
        defaultConfig {
            version = "3.11"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
    // CameraX + ML Kit barcode scanning: the phone scans a worker's pairing QR.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // ONNX Runtime: executes .onnx voice models on-device (Silero VAD).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Chaquopy Python runtime (CPython 3.11): executes src/main/python on-device.
    // (Runtime AAR is added by the chaquopy Gradle plugin automatically.)

    // Focused JVM tests for the search package (no Android runtime needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
