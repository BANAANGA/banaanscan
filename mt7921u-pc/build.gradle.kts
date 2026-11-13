// PC prototype for the MT7921U driver: runs it against a real adapter through libusb.
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
    implementation(project(":mt7921u"))
    implementation("net.java.dev.jna:jna:5.15.0")
}

application {
    mainClass.set("com.example.banaanscan.mt7921u.pc.MainKt")
}
