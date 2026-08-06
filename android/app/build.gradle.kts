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
        versionCode = 5
        versionName = "2.3.0"
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
    // AndroidX 核心
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.12.0")  // FileProvider（应用内更新安装）
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    // ExoPlayer — 原生视频播放（HLS/FLV/MP4/DASH）
    val exoVersion = "2.19.1"
    implementation("com.google.android.exoplayer:exoplayer-core:$exoVersion")
    implementation("com.google.android.exoplayer:exoplayer-hls:$exoVersion")
    implementation("com.google.android.exoplayer:exoplayer-dash:$exoVersion")
    implementation("com.google.android.exoplayer:exoplayer-ui:$exoVersion")
    implementation("com.google.android.exoplayer:extension-rtmp:$exoVersion")

    // Leanback for TV 遥控器支持
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")

    // 图片加载（频道 Logo）
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // WorkManager — 后台周期检查频道源更新
    implementation("androidx.work:work-runtime:2.9.0")
}
