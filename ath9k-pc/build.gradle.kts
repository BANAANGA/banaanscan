// PC prototype for the AR9271 (ath9k_htc) driver: runs it against a real adapter through libusb.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":ath9k"))
    implementation("net.java.dev.jna:jna:5.15.0")
}

application {
    mainClass.set("io.github.banaanga.sniffowireless.mobile.ath9k.pc.MainKt")
}
