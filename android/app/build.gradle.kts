plugins {
    id("com.android.application")
}

android {
    namespace = "com.rabbit.sleepguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rabbit.sleepguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.6.3"
    }

    buildTypes {
        release {
            val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            if (keystorePath != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystorePath)
                    storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
                }
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
