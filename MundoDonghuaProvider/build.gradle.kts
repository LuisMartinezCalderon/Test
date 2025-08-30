plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.mundodonghua"

    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
        // 👇 NO uses versionCode ni versionName en librerías
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
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
}

dependencies {
    // API de Cloudstream
    implementation("com.lagradost:cloudstream3:3.6.2")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")
}
