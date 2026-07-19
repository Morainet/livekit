# LiveKit

**English** | [简体中文](README_zh.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.morainet/livekit.svg)](https://central.sonatype.com/artifact/io.github.morainet/livekit)
[![CI](https://github.com/Morainet/livekit/actions/workflows/ci.yml/badge.svg)](https://github.com/Morainet/livekit/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-24%2B-blue.svg)](#requirements)

**LiveKit** is a high-performance, client-side **Live Activities / Live Updates** SDK for Android — the ongoing, glanceable "real-time activity" card you see for food delivery, ride-hailing, or order tracking.

It renders **Android 16 native `ProgressStyle`** live updates when available and **gracefully degrades** to custom `RemoteViews` notifications on older devices — driven entirely by a data-first, unidirectional API.

> Design details: see [TECH_WHITEPAPER.md](TECH_WHITEPAPER.md).

## Features

- **Two rendering channels, one API** — native Android 16 `ProgressStyle` (segmented progress bar, tracker icon, status chip) with automatic fallback to `RemoteViews`.
- **Out-of-order proof** — monotonic `seq_id` + `timestamp` auditing drops stale updates under weak-network reordering; persisted so it survives process death.
- **Smart throttling** — leading + trailing edge coalescing per activity, so high-frequency updates never flood (or get throttled by) the notification system.
- **Zero-power countdown** — hands the ticking to the system `Chronometer`; the host process can sleep.
- **Cross-process ready** — a push landing in your `:push` process wakes `:main` to render, via a built-in `ContentProvider` store or an optional **MMKV** backend.
- **Foreground-service keep-alive** with the Android 14+ **BFGS** closure: degrades cleanly when a background start is restricted, then auto-promotes when the app returns to foreground.
- **Auto-dismiss** via `clear_policy` backed by `AlarmManager` (survives process death).
- **Defensive by design** — a business binder that throws never crashes the host; oversized bitmaps are downsampled to dodge `TransactionTooLargeException`.
- **Observable** — a single event stream (`Rendered` / `Dropped` / `Degraded` / …) for metrics.

## Requirements

| | |
|---|---|
| `minSdk` | 24 (Android 7.0) |
| Native `ProgressStyle` Live Updates | Android 16 (API 36); auto-degrades below |
| Runtime notification permission | Android 13+ (`POST_NOTIFICATIONS`) |
| Foreground service types | Android 14+ (declared by the SDK as `dataSync`) |

## Installation

```kotlin
dependencies {
    implementation("io.github.morainet:livekit:1.0.0")

    // Optional: high-performance multi-process storage backend
    implementation("com.tencent:mmkv:2.4.0")
}
```

## Quick start

**1. Initialize (in your `Application`)**

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveKit.init(
            this,
            LiveKitConfig(
                defaultChannelId = "orders",
                observer = object : LiveKitObserver {
                    override fun onEvent(event: LiveKitEvent) { /* metrics */ }
                    override fun onError(throwable: Throwable, key: String?) { /* report */ }
                },
            ),
        )

        // A RemoteViews template (works on all supported versions).
        LiveKit.registerTemplate("food", "delivery", R.layout.card) { views, payload ->
            views.setTextViewText(R.id.title, payload["title"] as? String ?: "")
            views.setTextViewText(R.id.subtitle, payload["subtitle"] as? String ?: "")
        }
    }
}
```

**2. Drive the activity**

```kotlin
LiveKit.start("food", "10001", "delivery", mapOf("title" to "Rider assigned", "subtitle" to "~30 min"))
LiveKit.update("food", "10001", mapOf("subtitle" to "~12 min"))          // field-level merge
LiveKit.end("food", "10001")
```

## Native Live Updates (Android 16 `ProgressStyle`)

Register a **progress template** for the same `templateId`; the SDK picks the native channel when the device supports it and falls back to your `RemoteViews` template otherwise.

```kotlin
LiveKit.registerProgressTemplate("food", "delivery", R.drawable.ic_small) { payload ->
    LiveProgressSpec(
        title = payload["title"] as? String ?: "On its way",
        shortCriticalText = "Delivering",
        progress = (payload["progress"] as? Number)?.toInt() ?: 0,
        segments = List(4) { LiveProgressSpec.Segment(25) },
        points = listOf(25, 50, 75, 100).map { LiveProgressSpec.Point(it) },
        trackerIconRes = R.drawable.ic_truck,
        largeIconRes = R.drawable.thumbnail,
        countdownTargetMs = System.currentTimeMillis() + 10 * 60 * 1000, // zero-power countdown
    )
}
```

## Driving from a push (channel-agnostic)

LiveKit consumes a standard JSON envelope from **any** channel (FCM, vendor push, your own socket). It never opens a push connection itself.

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
// In your FirebaseMessagingService / vendor push receiver:
override fun onMessageReceived(message: RemoteMessage) {
    message.data["livekit"]?.let { LiveKit.dispatchRawJson(it) }
}
```

`action` is `START` | `UPDATE` | `END`. Malformed envelopes are rejected whole; a `protocol_version` newer than the SDK is parsed best-effort and reported as `UnsupportedVersion`.

## Cross-process storage

By default LiveKit uses a built-in `ContentProvider`-backed store (SQLite + WAL) and wakes `:main` via `ContentObserver`. To use **MMKV** instead:

```kotlin
MMKV.initialize(this)
LiveKit.init(this, LiveKitConfig(store = MmkvLiveKitStore(this)))
```

You can also supply your own by implementing `ILiveKitStore`.

## Configuration

`LiveKitConfig` (all optional):

| Option | Default | Purpose |
|---|---|---|
| `store` | built-in ContentProvider | Cross-process storage backend (`ILiveKitStore`) |
| `defaultChannelId` | `livekit_default` | Notification channel id |
| `smallIconRes` | system info icon | Small icon for notifications |
| `defaultThrottleWindowMs` | `1000` | Coalescing window per activity |
| `maxThrottleWindowMs` | `5000` | Upper bound (silent-compaction backoff) |
| `enableForegroundService` | `true` | Keep-alive FGS + BFGS promotion |
| `fgsType` | `dataSync` | Foreground service type |
| `maxBitmapBytes` / `maxBitmapDimenPx` | `200KB` / `512` | Bitmap sandbox thresholds |
| `chronometerRomBlacklist` | empty | ROMs with broken `Chronometer` |
| `observer` | – | Event / error listener |

## Observability

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

The AAR ships its own `consumer-rules.pro`; no consumer configuration is required. Verified against a fully minified (`isMinifyEnabled = true`) release build.

## Building from source

```bash
./gradlew :livekit:testDebugUnitTest   # unit tests
./gradlew :livekit:assembleRelease     # AAR
./gradlew :livekit:publishToMavenLocal # publish to ~/.m2 for local testing
```

## License

[MIT](LICENSE) © Morainet
