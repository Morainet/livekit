# LiveKit

[English](README.md) | **简体中文**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.morainet/livekit.svg)](https://central.sonatype.com/artifact/io.github.morainet/livekit)
[![CI](https://github.com/Morainet/livekit/actions/workflows/ci.yml/badge.svg)](https://github.com/Morainet/livekit/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-24%2B-blue.svg)](#环境要求)

**LiveKit** 是一款高性能、纯客户端的 Android **实时活动 / Live Updates** SDK —— 就是外卖、打车、订单追踪里那种常驻、可一眼看状态的「实时活动」卡片。

它在支持的设备上渲染 **Android 16 原生 `ProgressStyle`** 实时更新,并在旧设备上**自动降级**为自定义 `RemoteViews` 通知 —— 全程由数据驱动的单向 API 编排。

> 架构细节见 [TECH_WHITEPAPER.md](TECH_WHITEPAPER.md)。

## 特性

- **双渲染通道,同一套 API** —— 原生 Android 16 `ProgressStyle`(分段进度条、tracker 图标、状态栏 chip),不支持时自动降级 `RemoteViews`。
- **防乱序** —— 单调 `seq_id` + `timestamp` 审计,丢弃弱网重排下的过期更新;落盘持久化,进程被杀后依然生效。
- **智能限流** —— 按活动做 Leading + Trailing 合并,高频更新既不会刷屏,也不会被系统限流。
- **零功耗倒计时** —— 交给系统 `Chronometer` 跳动,宿主进程可安全挂起。
- **跨进程就绪** —— 推送落在 `:push` 进程即可唤醒 `:main` 渲染,内置 `ContentProvider` 存储或可选 **MMKV** 后端。
- **前台服务保活** + Android 14+ **BFGS 闭环** —— 后台启动受限时干净降级,回前台后自动提权。
- **自动清理** —— `clear_policy` 由 `AlarmManager` 兜底(扛得住进程死亡)。
- **防御式设计** —— 业务 binder 抛异常绝不拖垮宿主;超大 Bitmap 自动下采样,规避 `TransactionTooLargeException`。
- **可观测** —— 统一事件流(`Rendered` / `Dropped` / `Degraded` / …)供埋点。

## 环境要求

| | |
|---|---|
| `minSdk` | 24（Android 7.0） |
| 原生 `ProgressStyle` Live Updates | Android 16（API 36），低版本自动降级 |
| 运行时通知权限 | Android 13+（`POST_NOTIFICATIONS`） |
| 前台服务类型 | Android 14+（SDK 内声明为 `dataSync`） |

## 接入

```kotlin
dependencies {
    implementation("io.github.morainet:livekit:1.0.0")

    // 可选：高性能多进程存储后端
    implementation("com.tencent:mmkv:2.4.0")
}
```

## 快速开始

**1. 初始化（在你的 `Application` 里）**

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveKit.init(
            this,
            LiveKitConfig(
                defaultChannelId = "orders",
                observer = object : LiveKitObserver {
                    override fun onEvent(event: LiveKitEvent) { /* 埋点 */ }
                    override fun onError(throwable: Throwable, key: String?) { /* 上报 */ }
                },
            ),
        )

        // RemoteViews 模板（全版本可用）
        LiveKit.registerTemplate("food", "delivery", R.layout.card) { views, payload ->
            views.setTextViewText(R.id.title, payload["title"] as? String ?: "")
            views.setTextViewText(R.id.subtitle, payload["subtitle"] as? String ?: "")
        }
    }
}
```

**2. 驱动活动**

```kotlin
LiveKit.start("food", "10001", "delivery", mapOf("title" to "骑手已接单", "subtitle" to "约 30 分钟"))
LiveKit.update("food", "10001", mapOf("subtitle" to "约 12 分钟"))          // 字段级增量合并
LiveKit.end("food", "10001")
```

## 原生 Live Updates（Android 16 `ProgressStyle`）

为同一个 `templateId` 再注册一个**进度模板**;设备支持时 SDK 走原生通道,否则回退到你的 `RemoteViews` 模板。

```kotlin
LiveKit.registerProgressTemplate("food", "delivery", R.drawable.ic_small) { payload ->
    LiveProgressSpec(
        title = payload["title"] as? String ?: "配送中",
        shortCriticalText = "配送中",
        progress = (payload["progress"] as? Number)?.toInt() ?: 0,
        segments = List(4) { LiveProgressSpec.Segment(25) },
        points = listOf(25, 50, 75, 100).map { LiveProgressSpec.Point(it) },
        trackerIconRes = R.drawable.ic_truck,
        largeIconRes = R.drawable.thumbnail,
        countdownTargetMs = System.currentTimeMillis() + 10 * 60 * 1000, // 零功耗倒计时
    )
}
```

## 从推送驱动（通道无关）

LiveKit 消费来自**任意通道**(FCM、厂商推送、自建长连接)的标准 JSON 外壳,自身不建立任何推送连接。

```json
{
  "protocol_version": 1,
  "biz_type": "food",
  "activity_id": "10001",
  "action": "UPDATE",
  "template_id": "delivery",
  "seq_id": 42,
  "timestamp": 1737000000000,
  "clear_policy": { "dismiss_after_seconds": 300 },
  "payload": { "progress": 50 }
}
```

```kotlin
// 在你的 FirebaseMessagingService / 厂商推送回调里：
override fun onMessageReceived(message: RemoteMessage) {
    message.data["livekit"]?.let { LiveKit.dispatchRawJson(it) }
}
```

`action` 为 `START` | `UPDATE` | `END`。畸形外壳整包拒绝;`protocol_version` 高于 SDK 支持上限时尽力解析并上报 `UnsupportedVersion`。

## 跨进程存储

默认使用内置 `ContentProvider` 存储(SQLite + WAL),经 `ContentObserver` 唤醒 `:main`。改用 **MMKV**:

```kotlin
MMKV.initialize(this)
LiveKit.init(this, LiveKitConfig(store = MmkvLiveKitStore(this)))
```

也可以实现 `ILiveKitStore` 接入你自己的存储。

## 配置项

`LiveKitConfig`(均可选):

| 选项 | 默认 | 作用 |
|---|---|---|
| `store` | 内置 ContentProvider | 跨进程存储后端（`ILiveKitStore`） |
| `defaultChannelId` | `livekit_default` | 通知渠道 id |
| `smallIconRes` | 系统 info 图标 | 通知小图标 |
| `defaultThrottleWindowMs` | `1000` | 每活动的合并窗口 |
| `maxThrottleWindowMs` | `5000` | 上限（静默压实 backoff） |
| `enableForegroundService` | `true` | FGS 保活 + BFGS 提权 |
| `fgsType` | `dataSync` | 前台服务类型 |
| `maxBitmapBytes` / `maxBitmapDimenPx` | `200KB` / `512` | Bitmap 沙箱阈值 |
| `chronometerRomBlacklist` | 空 | Chronometer 有缺陷的 ROM |
| `observer` | – | 事件 / 异常监听器 |

## 可观测性

```kotlin
sealed interface LiveKitEvent {
    data class Rendered(val key: String, val channel: RenderChannel)
    data class Dropped(val key: String, val reason: DropReason)          // OUT_OF_ORDER / ORPHAN / MALFORMED / STORE_BUSY
    data class Degraded(val key: String, val from: RenderChannel, val to: RenderChannel)
    data class PermissionMissing(val key: String)
    data class Throttled(val key: String, val mergedCount: Int)
    data class UnsupportedVersion(val version: Int, val key: String)
}
```

## ProGuard / R8

AAR 自带 `consumer-rules.pro`,使用方无需额外配置。已在完全混淆(`isMinifyEnabled = true`)的 release 构建上验证。

## 从源码构建

```bash
./gradlew :livekit:testDebugUnitTest   # 单元测试
./gradlew :livekit:assembleRelease     # 打 AAR
./gradlew :livekit:publishToMavenLocal # 发到 ~/.m2 供本地联调
```

## 许可证

[MIT](LICENSE) © Morainet
