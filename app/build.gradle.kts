plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pooltrajectory.br"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pooltrajectory.br"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Keep all app Kotlin code in a dedicated source root. This deliberately
    // excludes stale files left under src/main/java by older ZIP extractions.
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/main/kotlin"))
        }
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.opencv:opencv:4.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
