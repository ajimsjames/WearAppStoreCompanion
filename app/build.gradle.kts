plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ajimsjames.wearappstorecompanion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ajimsjames.wearappstorecompanion"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.5"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all"
        )
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Phone UI dependencies
    implementation("androidx.compose.ui:ui:1.6.2")
    implementation("androidx.compose.ui:ui-graphics:1.6.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.2")
    implementation("androidx.compose.material3:material3:1.2.0")
    
    // libadb-android for ADB connections and native wireless pairing
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    
    // BouncyCastle for X.509 Certificate generation (matching libadb-android)
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    
    // Conscrypt for TLS 1.3 support on Android (required by libadb-android)
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    
    // Play Services Wearable for official Bluetooth / Data Layer Sync
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
}
