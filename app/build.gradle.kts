// Release builds take their version from the git tag (vX.Y.Z, passed as BANAANSCAN_VERSION) and are signed with the
// release key from environment variables, which the release workflow fills from GitHub secrets. Without them, a
// release build is simply unsigned.
val releaseVersion: String? = System.getenv("BANAANSCAN_VERSION")?.removePrefix("v")
val keystoreFile: String? = System.getenv("RELEASE_KEYSTORE_FILE")

/** 1.2.3 -> 10203, so every new tag counts as an update. */
fun versionCodeOf(version: String): Int {
    val parts = version.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    require(parts[1] < 100 && parts[2] < 100) { "Minor and patch versions must stay below 100: $version" }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.banaanscan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.banaanscan"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersion?.let(::versionCodeOf) ?: 2
        versionName = releaseVersion ?: "0.2.0"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.FlowPreview",
        )
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation(project(":mt7921u"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
