plugins {
    kotlin("jvm") version "1.9.0"
    application
}

group = "org.telegramBot"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Telegram Java SDK (rubenlagus)
    implementation("org.telegram:telegrambots:6.8.0")
    implementation("org.telegram:telegrambotsextensions:6.8.0")

    // HTTP клиент и JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")


    implementation("org.slf4j:slf4j-simple:2.0.13")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.deepl.api:deepl-java:1.8.0")
    implementation("com.google.firebase:firebase-admin:9.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("emily.app.AppKt")
}
