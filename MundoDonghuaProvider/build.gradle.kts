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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // No pongas cloudstream3 aquí, ya lo provee el plugin raíz
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")
}
