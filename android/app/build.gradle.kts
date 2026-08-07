plugins {
    id("com.android.application")
}

android {
    namespace = "com.tv.live"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tv.live"
        minSdk = 21                      // ExoPlayer 2.19 要求 minSdk 21（Android 5.0+）
        targetSdk = 30                   // 降到 30：避免 34 的严格返回键/前台权限问题
        versionCode = 6
        versionName = "2.4.0"

        // 只打包 arm 架构，缩小 APK 体积，覆盖小米盒子
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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
        // 开启核心库 desugaring：让 Java 8+ API（如 java.util.function.Consumer）
        // 在低版本 Android（minSdk 21）上可用，避免 NoClassDefFoundError 启动崩溃
        isCoreLibraryDesugaringEnabled = true
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

    // 核心库 desugaring（让 Java 8+ API 在 minSdk 21 可用）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
