// What makes the app a sniffowireless sensor: the observation formats (ported from sniffowireless, checked against
// fixtures its own Python code produces) and the upload to a server. Plain Kotlin, so it's tested on the PC.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
