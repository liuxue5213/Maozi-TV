plugins {
    id("com.android.application")
}

android {
    namespace = "com.tv.live"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tv.live"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // 使用环境变量或默认 debug 签名
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "maozitv"
            keyAlias = System.getenv("KEY_ALIAS") ?: "maozitv"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "maozitv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // AndroidX
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
    // Leanback for TV
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
}
