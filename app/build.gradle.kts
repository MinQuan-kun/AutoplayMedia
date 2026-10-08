plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.signage.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.signage.player"
        minSdk = 16       // Ho tro tu Android 4.1 (Jelly Bean) tro len
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    signingConfigs {
        create("release") {
            storeFile = file("keystore/release.jks")
            storePassword = "signage123456"
            keyAlias = "signage"
            keyPassword = "signage123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Multidex ho tro Android 4.x
    implementation("androidx.multidex:multidex:2.0.1")

    // Android Core & Giao dien tuong thich Android 4.3+ (minSdk 14-18)
    implementation("androidx.core:core-ktx:1.6.0")
    implementation("androidx.appcompat:appcompat:1.3.1")
    implementation("com.google.android.material:material:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.0.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    // Mang & Tai file tuong thich Android 4.3+ (OkHttp 3.12.13 ho tro Android 4.x & TLS 1.2)
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // Thu vien Paho MQTT trong app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
