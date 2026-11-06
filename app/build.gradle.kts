plugins {
    id("com.android.application")
}

// Stable release signing: keystore.p12 is committed to the repo so every build
// uses the same key (updates install over the previous build). Rotate it and
// move it to a secret before making the repo public.
val keystoreFile = rootProject.file("keystore.p12")

android {
    namespace = "com.puddingroot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.puddingroot"
        minSdk = 31
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.0.1-beta"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storeType = "pkcs12"
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "puddingroot"
            keyAlias = System.getenv("KEY_ALIAS") ?: "puddingroot"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "puddingroot"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
