plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // google-services：读取 demo/google-services.json 注入 Firebase 配置。
    // 注意：缺少该文件时构建会失败，需从 Firebase 控制台下载后放入 demo/（见 README）。
    alias(libs.plugins.google.services)
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
    // demo 与 livekit 同处一个 monorepo，直接引用源码工程。
    // 远程坐标 io.github.morainet:livekit:x.y.z 仅用于外部消费者；用 project 才能始终吃到最新源码。
    implementation(project(":livekit"))
    implementation(libs.androidx.core.ktx)
    // registerForActivityResult / ActivityResultContracts 来源，用于运行时申请 POST_NOTIFICATIONS。
    implementation(libs.androidx.activity)
    implementation(libs.mmkv)
    // FCM 推送测试：firebase-bom 统一版本，仅需 firebase-messaging 收推送。
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
