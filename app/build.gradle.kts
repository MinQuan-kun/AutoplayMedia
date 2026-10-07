plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.signage.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.signage.player"
        minSdk = 24       // Hỗ trợ từ Android 7.0 trở lên (phù hợp 99.9% màn hình TV, TV Box và bo mạch nhúng)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Android Core & Giao diện Material
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Mạng & Tải file ngầm (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Trình phát Video tối ưu (Google Media3 ExoPlayer)
    // Tự động giải mã phần cứng, chạy lặp vô tận (continuous loop)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Thư viện JAR cục bộ trong thư mục app/libs (giúp Android Studio nhận diện ngay lập tức)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Giao thức MQTT thời gian thực (Real-time IoT Remote Control)
    // Nhận lệnh cập nhật video tức thì (< 1 giây) từ Server quản trị
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
