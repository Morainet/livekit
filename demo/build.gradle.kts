plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.morainet.livekit.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.morainet.livekit.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            // 开 R8：验证 SDK 的 consumer-rules 能保住公开 API 与清单组件。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 用 debug 签名，便于本地安装验证 minified 构建（发布时替换为正式签名）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("io.github.morainet:livekit:0.0.2")
    implementation(libs.androidx.core.ktx)
    // registerForActivityResult / ActivityResultContracts 来源，用于运行时申请 POST_NOTIFICATIONS。
    implementation(libs.androidx.activity)
    implementation(libs.mmkv)
}
