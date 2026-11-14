plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase config is optional so the app still builds without it;
// see README "Firebase setup".
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.tvguard.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvguard.phone"
        minSdk = 26
        targetSdk = 34
        // CI sets BUILD_NUMBER to the workflow run number, so every build can
        // replace the previous one and the TV can tell which release is newer.
        val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
        // Where "Get newest phone app" points.
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "fk-hash-0077/tv-parental-control"
        buildConfigField("String", "RELEASES_REPO", "\"$repo\"")
    }

    signingConfigs {
        // Shared key committed to the repo so every build can update the
        // installed app. The TV app blocks its own uninstall, so a key change
        // would mean removing protection first.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("androidx.biometric:biometric:1.1.0")
}
