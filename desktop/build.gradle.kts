plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val javafxVersion = "21.0.2"
val osClassifier = "win"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // JavaFX for Windows (Controls, Graphics, Media Engine)
    implementation("org.openjfx:javafx-base:$javafxVersion:$osClassifier")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$osClassifier")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$osClassifier")
    implementation("org.openjfx:javafx-media:$javafxVersion:$osClassifier")

    // Network & Media download (OkHttp 4.x)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MQTT Client (Paho MQTT)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // JSON parsing
    implementation("org.json:json:20240303")
}

application {
    mainClass.set("com.signage.desktop.MainApplicationKt")
    applicationDefaultJvmArgs = listOf(
        "--add-modules", "javafx.controls,javafx.media",
        "-Dfile.encoding=UTF-8"
    )
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.signage.desktop.MainApplicationKt"
    }
}
