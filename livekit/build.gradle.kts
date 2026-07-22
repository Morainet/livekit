plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "com.morainet.livekit"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // vanniktech 自动配置 release 变体的发布 + sources/javadoc jar，无需手写 android.publishing{}。
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // 可选：宿主引入 MMKV 时可用 MmkvLiveKitStore；不引入则自动走内置 ContentProvider。
    compileOnly(libs.mmkv)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 发布到 Maven Central Portal（vanniktech：一并处理签名 + sources/javadoc + POM + 上传）。
// 凭据从环境变量注入（CI Secrets）：
//   ORG_GRADLE_PROJECT_mavenCentralUsername / mavenCentralPassword  —— Central Portal token
//   ORG_GRADLE_PROJECT_signingInMemoryKey / signingInMemoryKeyPassword —— GPG 私钥
mavenPublishing {
    publishToMavenCentral(automaticRelease = false) // 上传后在 Portal 网页手动点发布（首发更稳）
    // 仅当提供了 GPG 私钥（CI Secret → ORG_GRADLE_PROJECT_signingInMemoryKey）时签名，
    // 本地无密钥的 publishToMavenLocal 不受影响。
    if (project.findProperty("signingInMemoryKey") != null) {
        signAllPublications()
    }
    coordinates("io.github.morainet", "livekit", System.getenv("RELEASE_VERSION") ?: "0.0.2")

    pom {
        name.set("LiveKit")
        description.set("High-performance, client-side Live Activities (Live Updates) SDK for Android.")
        url.set("https://github.com/Morainet/livekit")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("xichen")
                name.set("xichen")
                organization.set("Morainet")
            }
        }
        scm {
            url.set("https://github.com/Morainet/livekit")
            connection.set("scm:git:https://github.com/Morainet/livekit.git")
            developerConnection.set("scm:git:ssh://git@github.com/Morainet/livekit.git")
        }
    }
}
