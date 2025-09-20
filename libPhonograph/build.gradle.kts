plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "uk.akane.libphonograph"
    compileSdk = 35

    defaultConfig {
        aarMetadata {
            minCompileSdk = 33
        }
        testFixtures {
            enable = true
        }
        minSdk = 16
        consumerProguardFiles("consumer-rules.pro")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        create("profiling") {
            isMinifyEnabled = false
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.media3:media3-common:1.6.0-alpha03")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}