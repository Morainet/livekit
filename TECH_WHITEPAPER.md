# LiveKit (Android) 技术架构与实现规范白皮书

> 版本：v1.3（评审级封版规约）
> 定位：高性能、纯客户端的 Android「实时活动（Live Activities）」基础架构 SDK
> 建议仓库名：`LiveKit-Android`

---

## 目录

1. [导言与核心痛点](#1-导言与核心痛点)
2. [设计原则与非目标](#2-设计原则与非目标)
3. [架构拓扑与全景设计](#3-架构拓扑与全景设计)
4. [核心技术攻坚与算法细节](#4-核心技术攻坚与算法细节)
5. [跨进程 IPC 与数据持久化规范](#5-跨进程-ipc-与数据持久化规范)
6. [平台适配、能力探测与降级矩阵](#6-平台适配能力探测与降级矩阵)
7. [权限与前台服务合规](#7-权限与前台服务合规)
8. [并发模型与线程安全](#8-并发模型与线程安全)
9. [生命周期时序](#9-生命周期时序)
10. [数据协议与开放接口（API & Schema）](#10-数据协议与开放接口-api--schema)
11. [内存泄漏防御与稳定性规范](#11-内存泄漏防御与稳定性规范)
12. [可观测性与异常上报](#12-可观测性与异常上报)
13. [质量矩阵（模块 ↔ 测试用例映射规约）](#13-质量矩阵模块--测试用例映射规约)
14. [兼容性与版本演进](#14-兼容性与版本演进)
15. [术语表](#15-术语表)

---

## 1. 导言与核心痛点

在 Android 生态中实现类似 iOS `ActivityKit` 的「实时活动」面临诸多系统级瓶颈。`LiveKit` 系统性地解决以下工业级痛点：

* **系统碎片化：** Android 16+ 引入原生 `Notification.ProgressStyle`（Live Updates），旧版本只能依赖 `RemoteViews` + `ForegroundService`。
* **通知系统限流（System Throttling）：** 短时间内高频调用 `NotificationManager.notify()` 会引发系统丢帧、UI 闪烁，甚至被系统直接拦截（部分 ROM 在约 1s 内 >10 次刷新即触发限流）。
* **网络乱序（Out-of-Order）：** 弱网下后发状态（「已送达」）可能比先发状态（「距离 50 米」）先到达，导致状态倒流。
* **跨进程内存隔离：** 宿主推送服务通常运行在独立 `:push` 进程，渲染与业务逻辑运行在 `:main` 进程，两端状态无法通过内存共享。

---

## 2. 设计原则与非目标

**设计原则**

1. **绝不拖垮宿主：** SDK 任何内部异常都不得导致宿主 App 崩溃、ANR 或泄漏（详见 §11）。
2. **数据驱动 + 单向数据流（UDF）：** 外部只投递标准数据，UI 渲染完全由 SDK 内部状态机驱动，业务方不直接触碰 `NotificationManager`。
3. **通道无关（Channel-Agnostic）：** 数据来源（本地触发 / FCM / 厂商推送 / 自建长连接）对 SDK 透明，统一收敛到 `dispatchRawJson`。
4. **优雅降级：** 在任何系统版本 / 权限组合下都给出「尽力而为」的最佳呈现，绝不硬失败。

**非目标（Non-Goals）**

* 不提供服务端推送通道，仅消费标准协议。
* 不承诺在通知被用户手动关闭 / 系统强杀后的「必达」，仅在系统允许范围内保活。
* 不做富媒体动画渲染，遵守 `RemoteViews` 与 `ProgressStyle` 的系统能力边界。

---

## 3. 架构拓扑与全景设计

`LiveKit` 采用数据驱动与单向数据流（UDF）的解耦架构。

```
+-----------------------------------------------------------------------------------+
|                                 LAYER 3: APPLICATION                               |
|           App :main Process                     App :push Process                 |
|     +----------------------------+        +----------------------------+          |
|     |   Business UI/Templates    |        |  PushService (FCM/厂商)     |          |
|     +--------------+-------------+        +--------------+-------------+          |
+--------------------|-------------------------------------|-------------------------+
                     | (Local Trigger)                     | (Push Trigger)
                     v                                     v
+-----------------------------------------------------------------------------------+
|                                 LAYER 2: LIVEKIT ENGINE                            |
|  +-----------------------------------------------------------------------------+  |
|  |                             LiveKit Facade                                   |  |
|  +----------------------------+--------------------------+---------------------+  |
|  |     TemplateRegistry       |    OrderStateMachine     |    ThrottleQueue     |  |
|  |     (UI Map & Binders)     |    (Seq/Timestamp Check) |    (Coroutine)       |  |
|  +----------------------------+--------------------------+---------------------+  |
+-----------------------------------------------------------------------------------+
                                             |
                                             v
+-----------------------------------------------------------------------------------+
|                              LAYER 1: PLATFORM ADAPTOR                             |
|       +------------------------------------+---------------------------------+     |
|       |     Android 16+ Channel            |      Legacy Android Channel      |     |
|       |     - ProgressStyle                |      - ForegroundService 保活     |     |
|       |     - Status Bar Live Chips        |      - RemoteViews Custom Layout |     |
|       +------------------------------------+---------------------------------+     |
|       |         Cross-Process Persistence IPC (MMKV / ContentProvider)        |    |
|       +----------------------------------------------------------------------+     |
+-----------------------------------------------------------------------------------+
```

**核心组件职责**

| 组件 | 职责 | 线程归属 |
|---|---|---|
| `LiveKit Facade` | 唯一公开入口，参数校验与分发 | 任意线程调用，内部切换 |
| `TemplateRegistry` | `bizType#templateId → (layout, binder)` 映射 | 读多写少，`ConcurrentHashMap` |
| `OrderStateMachine` | 复合键索引、乱序校验、状态落盘 | 单一 `Dispatcher`（串行化） |
| `ThrottleQueue` | 滑动窗口合并、限流刷新 | 每 Key 一条协程 |
| `PlatformAdaptor` | 版本能力探测、真正调用系统 API | 主线程渲染，其余后台 |
| `ILiveKitStore` | 跨进程持久化抽象 | 线程安全实现负责 |

### 3.6 门面（LiveKit）与引擎（LiveKitEngine）的职责边界与信息屏障

`LiveKit`（public）与 `LiveKitEngine`（internal）是两个层级：门面是唯一对外的「接火层」，引擎是封闭的「中央大脑」。二者以隐式契约 + 跨进程信道解耦。

```
+---------------------------------------------------------------------------+
|                         PUBLIC FACADE — LiveKit (object)                   |
|  职责：外部业务方 / 推送通道唯一可见入口；轻量粗校 + 进程判定 + 注册映射       |
|  状态：无状态（Stateless），仅持有 TemplateRegistry 引用                     |
|  线程：任意调用线程，绝不阻塞，立即将数据/信号推入内部引擎                     |
+---------------------------------------------------------------------------+
                 |  [ 隐式契约 / 跨进程信道 ]
                 v
+---------------------------------------------------------------------------+
|                  INTERNAL ENGINE — LiveKitEngine (internal object)         |
|  职责：状态机 / 限流 / 平台渲染 / 生命周期 / 持久化管线的中央大脑            |
|  状态：强状态（Stateful），串行驱动 OrderStateMachine 与各类重绘定时器       |
|  线程：封闭于专属单线程（Actor Channel），门面调用在此被拉平为串行事件         |
+---------------------------------------------------------------------------+
```

**职责映射**

| 维度 / 场景 | 门面 `LiveKit`（public） | 引擎 `LiveKitEngine`（internal） |
|---|---|---|
| 可见性 | `public`，外部与推送通道唯一直接依赖 | `internal`，外部不可感知/调用，保护核心管道 |
| 调用进程 | 允许 `:main`（本地）与 `:push`（推送）双进程并发调用 | **仅在 `:main` 进程存活激活**，`:push` 不跑任何业务逻辑 |
| 数据校验 | 拦截外壳畸形 / 必填缺失的原始 JSON（粗校） | 深度解析 payload，强校验（seq/timestamp 乱序审计） |
| 分发路径 | 判定为 `:main` → 直调引擎；判定为 `:push` → **不激活引擎**，仅落盘（§5.2）+ 经跨进程信道（§5.3）向 `:main` 发变更信号 | 接收门面调用或 `ContentObserver` 唤醒信号，凭复合键拉取数据本体投入流水线 |
| 生命周期 | 不承载生命周期回调，仅在 `init` 注入 `applicationContext` | 全权消费 `LiveKitLifecycleTracker`，`onAppForegrounded` 时串行激活提权管线 |
| 容错容灾 | 捕获 JSON 极端畸形，转 `MalformedPayload` 抛给观测者 | 核心容错沙箱：包裹 `binder`、Bitmap 裁剪、平台渲染硬件异常 |

**核心约束**

* **进程漏斗：** `LiveKitEngine` 永不在 `:push` 进程实例化。`:push` 调 `dispatchRawJson` 时门面收敛为「纯写入不渲染」的哑模式，把渲染压力漏斗式压回 `:main` 的引擎。
* **隔离设计：** 门面不持有任何状态机内存实例。多业务线并发轰炸门面时，门面切线程把请求投递到引擎的专属单线程信道，用生产者-消费者模型自然化解外部并发争锁。

---

## 4. 核心技术攻坚与算法细节

### 4.1 命名空间多业务隔离（Anti-Collision）

为防止跨业务线的 `activity_id` 冲突（如外卖与打车同为 `10001`），内部使用复合键作为状态机唯一索引：

```
Internal_Key = biz_type + "#" + activity_id
```

该复合键同时用于：状态机索引、限流队列分片、通知 `notifyId` 派生（对复合键做稳定哈希，避免与宿主自有通知 ID 撞车，详见 §7）。

### 4.2 智能时间流控队列（Dynamic Throttle Queue）

基于 Kotlin 协程 `Channel` 的滑动窗口合并算法，防御高频冲刷导致通知栏崩溃。

```
Incoming:  [P1 dist=100m] --> [P2 dist=80m] --> [P3 dist=50m]
                       |  (窗口 T = 1000ms)
                       v
Merged:                [Merged: dist=50m]
                       |
                       v  (单次 notify)
                 NotificationManager
```

**算法逻辑（Leading + Trailing Edge）**

初稿采用纯 Trailing（窗口结束才刷新），会给「首帧」引入固定 1s 延迟，弱化实时观感。完善版改为 **首帧立即渲染 + 窗口内合并尾帧**：

1. 某 `Internal_Key` 收到更新且当前**无活跃窗口** → **立即渲染一次（Leading Edge）**，并开启周期 `T = 1000ms` 的挂起窗口。
2. 窗口内流入的新 `payload` 对本地缓存做 **增量覆盖（Incremental Merge）**，不触发渲染。
3. 窗口结束时，若期间有过合并（脏标记为真）→ 提取最终态触发 **一次** 渲染（Trailing Edge）；否则跳过，释放 CPU。
4. **`END` 语义优先：** 收到 `END` 时立即 flush 当前合并态并结束窗口，`END` **不受节流延迟**，避免「已完成」卡片滞留。
5. 窗口周期 `T` 可经 `LiveKitConfig` 按 `bizType` 覆盖（默认 1000ms，取值区间 `[300ms, 5000ms]`）。

> 关键点：合并是「后到覆盖先到」的字段级 merge，而非整包替换——`END` 只携带增量字段时也能保留既有展示数据。

**无权限静默压实（Silent Compaction）**：当能力探测确认无通知权限（§7）时，即便业务方高频 `start/update`，任何渲染都不会落到系统，`ThrottleQueue` 若照常空转只会白耗 CPU 与内存。此时队列自适应切入 **Max Backoff**：窗口直接放大至上限（默认 5000ms）、挂起 Leading/Trailing 全部重绘定时器，仅在内存维护字段级最终态；`Chronometer` / 进度条节点重绘一并停摆。权限恢复后按最终态一次性补发，避免「被拒权限期」的无谓算力开销。

### 4.3 状态机双重防乱序校验（Out-of-Order Proof）

针对网络抖动导致的「时光倒流」，状态机建立双重审计：

1. **强校验（`seq_id`）：** 单调递增序列号。
2. **时间校验（`timestamp`）：** `seq_id` 缺失或相等时，用绝对时间戳兜底。

**展示判定：**

```
accept = (in.seq  > local.seq)
      || (in.seq == local.seq && in.timestamp > local.timestamp)
```

不通过则该包在底层 **静默丢弃（Dropped）**，不激活流控与渲染，并抛出一条 `LiveKitEvent.Dropped` 观测事件（§12）。

**关键补充（初稿缺失的边界）**

* **`local.seq` 必须持久化：** 校验基准从 `ILiveKitStore` 读取，保证进程重启 / `:push`↔`:main` 切换后乱序防护依然生效（否则重启即失防）。
* **`END` 同样遵循单调规则：** 一条迟到的 `END`（`seq` 低于当前）会被丢弃，从而防止「陈旧 END 误杀刚刚重启的同 ID 新活动」。
* **孤儿 UPDATE（Orphan Update）：** `UPDATE` 先于 `START` 到达时——
  * 若该 `UPDATE` 携带 `template_id`，则按 **隐式 START** 物化活动；
  * 否则短暂缓冲（默认 3s），超时仍无 `START` 则丢弃并上报 `Dropped(reason=ORPHAN)`。

### 4.4 零功耗本地动态倒计时

传统常驻倒计时靠后台 `Timer` 每秒 `notify()`，阻止 CPU 进入 Deepsleep，严重耗电。

`LiveKit` 深度封装系统 `Chronometer` 远程视图：后端传入截止时间戳 `target_timestamp`，SDK 换算为相对基准 `SystemClock.elapsedRealtime()` 后调用：

```kotlin
remoteViews.setChronometer(R.id.livekit_timer, baseTime, null, /* started = */ true)
// 倒计时场景使用 setChronometerCountDown(R.id.livekit_timer, true)
```

此后倒计时刷新完全托管给系统 `SystemServer`，宿主进程可被安全挂起，实现 **0 功耗高频刷新**。

> 注意：`Chronometer` 仅适用「纯时间数字」跳动；若倒计时需驱动进度条百分比联动，仍需配合 `AlarmManager` 在关键节点（如整分钟）触发一次重绘，而非每秒刷新。

**Note — ROM 兼容性与负数防御：** 部分国内深度定制 ROM（如早期特定版本 MIUI / EMUI）在锁屏或 AOD（熄屏显示）下对 `RemoteViews.Chronometer` 存在「停止刷新」或「时区换算错误导致相差 8 小时 / 倒计时显示负数」的缺陷。SDK 据此加两道防线：

1. **负数防御校验：** 换算 `baseTime` 时对 `target - elapsedRealtime()` 做下界钳制（`≥ 0`），并对 `baseTime` 做合理区间断言，异常值直接拒绝托管系统 `Chronometer`。
2. **机型黑名单降级：** 探测到已知异常 ROM（`Build.MANUFACTURER` / `Build.VERSION.INCREMENTAL` 匹配内置黑名单）时，倒计时**不走** `Chronometer` 自动跳动，改为依赖 `AlarmManager` 节点式定期重绘（每秒 / 关键节点由重要性决定），确保数字绝对正确。黑名单可经 `LiveKitConfig` 增量覆盖，便于社区持续补充。

---

## 5. 跨进程 IPC 与数据持久化规范

大厂推送架构下，数据常在 `:push` 进程落地，而保活的 `ForegroundService` 运行在 `:main` 进程，两端进程无法共享内存。

```
[ :push Process ]                                          [ :main Process ]
FCM/Msg --> 校验 --> 写入跨进程存储 --> 触发唤醒 IPC --> 引擎读取 --> 渲染 View
```

### 5.1 数据落盘

放弃大内存的进程间直传（如大 `Intent Bundle`，受 1MB `Binder` 事务上限约束且易 `TransactionTooLargeException`），改用进程安全的共享存储区。**只传「变更信号 + 复合键」，数据本体从存储读取。**

### 5.2 存储介质（修正初稿）

SDK 抽象出 `ILiveKitStore` 接口。**默认实现不再采用 `SharedPreferences` 多进程模式**——`MODE_MULTI_PROCESS` 自 API 23 起已废弃，不保证跨进程可见性与一致性，会丢更新。

推荐优先级：

| 优先级 | 介质 | 说明 |
|---|---|---|
| 1（默认） | **MMKV（`MULTI_PROCESS` 模式）** | 基于 mmap + 文件锁，天然多进程安全、高性能。宿主已集成时零成本接入 |
| 2 | **`ContentProvider`（SDK 内置）** | 无三方依赖场景的兜底，`Provider` 天生跨进程，配合 `notifyChange` 做变更通知 |
| 3（可选） | 业务自定义 | 实现 `ILiveKitStore` 即可无缝替换 |

> 敏感 `payload` 建议在 `ILiveKitStore` 适配层做加密（如 MMKV 的 `cryptKey`），SDK 不强制。

**多进程冷启动并发防御（Cold-Start Race）**

宿主被强杀后，一条高频推送可能让 `:push` 与被其唤醒的 `:main` 在同一毫秒内各自「冷启动」并同时初始化 / 读写 `ILiveKitStore`。规约如下：

* **初始化幂等：** `ILiveKitStore` 的初始化必须走**懒加载 + 双重检查锁（DCL）**，`init` 内的 `MMKV.initialize` / provider 建立保证多次调用幂等，禁止在冷启动竞态下重复建库。
* **MMKV 路径（默认）：** 以 `mmapID + MULTI_PROCESS` 打开，底层文件锁天然多进程安全，不涉及 SQLite，**无锁死风险**；仅需保证各进程用同一 `mmapID` 与一致的 `cryptKey`。
* **ContentProvider 路径（兜底）：** **刻意不声明 `android:multiprocess="true"`**——保持 provider **单实例**，`:push` 的写经 Binder 漏斗到该唯一实例串行化，从根上消除跨进程 SQLite 争锁（且该 Binder 调用顺带唤醒 `:main`）。同进程内读写并发再叠加两道纵深防御：
  * SQLite 开启 **WAL（Write-Ahead Logging）**，读写不互斥；
  * 写冲突时内置 **阶梯退避重试（50ms → 100ms → 200ms，共 3 次）**，彻底消灭偶发 `SQLiteDatabaseLockedException`；超限则丢弃本次写并上报 `LiveKitEvent.Dropped(reason=STORE_BUSY)`，绝不阻塞调用线程。

> 反模式提醒：切勿为「让每个进程都能直接读库」而开启 `android:multiprocess="true"`——它会为每个进程各起一个 provider 实例，反而制造真正的跨进程并发争锁，与上述漏斗串行化设计相悖。

### 5.3 跨进程唤醒机制（修正初稿）

> **初稿错误：`LocalBroadcast` 无法跨进程。** `LocalBroadcastManager` 仅在单进程内投递，且已 deprecated，`:push` 发出的广播 `:main` 收不到。

正确的跨进程唤醒方案（按推荐度）：

1. **`ContentProvider` + `ContentObserver`：** `:push` 写入后 `getContentResolver().notifyChange(uri, null)`，`:main` 引擎注册 `ContentObserver` 被唤醒。与 §5.2 的存储介质天然一体，**首选**。
2. **显式 `Broadcast`（`setPackage(pkg)` + 指定 `Component`）：** 携带统一 `Action` 与复合键，`:main` 用 `RECEIVER_NOT_EXPORTED` 注册。注意 Android 8+ 后台广播限制，需静态声明或运行时注册。
3. **`Messenger` / AIDL 绑定：** 需要强时序或双向确认时使用，成本较高，非默认。

唤醒后 `:main` 引擎凭复合键从 `ILiveKitStore` 拉取最新态，进入状态机 → 限流 → 渲染流水线。

---

## 6. 平台适配、能力探测与降级矩阵

SDK 启动时做一次 **能力探测（Capability Probe）**，缓存结果，运行期据此选路。宿主在权限变更（如用户授予 `POST_NOTIFICATIONS`）后可调 `LiveKit.refreshCapabilities()` 强制重新探测，避免快照过期导致持续误判降级。

| 系统 / 条件 | 首选渲染通道 | 降级链 |
|---|---|---|
| Android 16+ 且支持 `ProgressStyle` | 原生 Live Updates（状态栏 Live Chip） | → Legacy `RemoteViews` |
| Android 8–15 | `RemoteViews` 自定义布局 + FGS 保活 | → 标准 `Notification` 文本样式 |
| 无 `POST_NOTIFICATIONS` 权限（13+） | 不弹通知，仅内部维护最终态；激活**无权限静默压实（Silent Compaction）** | 回调 `onPermissionMissing`；限流窗口自适应拉长至上限（默认 5000ms）并挂起所有非关键重绘定时器，仅保留内存最终态，直至宿主调 `refreshCapabilities()` 触发权限由关转开后一次性补发 |
| FGS 类型不满足 / 后台启动受限（14+ BFGS 约束） | 自动降级为**非 FGS 常驻通知**（不占 FGS 额度、可被回收，但绝不崩溃），并置「待提权」标记 | 宿主回到前台（`onAppForegrounded`）后 SDK **自动提权（Promotion）** 回标准 FGS 通道；上报 `Degraded` |
| ROM 通知被限流 | 合并窗口自适应放大（backoff） | 保证最终态一致 |
| ROM Chronometer 异常（黑名单机型） | 倒计时改 `AlarmManager` 节点式重绘 | 保证倒计时数字正确（见 §4.4 Note） |

**探测实现要点**

* 用 `Build.VERSION.SDK_INT` + 反射 / `try-catch` 探测 `ProgressStyle` 类是否存在，避免低版本 `NoClassDefFoundError`。
* 探测结果与实际调用结果解耦：即便探测通过，真正 `notify` 仍包裹容错，失败即走降级链并上报。

---

## 7. 权限与前台服务合规

初稿未覆盖，但这是能否成功展示的前置门槛。

| 版本 | 约束 | SDK 处理 |
|---|---|---|
| Android 13（API 33）+ | 运行时 `POST_NOTIFICATIONS` 权限 | SDK 不主动申请（交由宿主决定时机），提供 `LiveKit.hasNotificationPermission()`；缺失时回调并缓存待发态。权限授予后宿主调用 `LiveKit.refreshCapabilities()` 重新探测能力快照，SDK 自动补发缓存中待渲染的活动 |
| Android 14（API 34）+ | 前台服务必须声明 `foregroundServiceType` 且匹配用途 | 保活服务默认声明 `dataSync` / 由 `LiveKitConfig.fgsType` 指定；宿主需在 `Manifest` 补齐 `FOREGROUND_SERVICE_*` 权限 |
| Android 12（API 31）+ / 14（API 34）+ | 后台启动 FGS 受限（BFGS）：`:main` 处于后台被挂起时，即便被 `:push` 唤醒，`startForegroundService()` 仍极易抛 `ForegroundServiceStartNotAllowedException` | 优先「先建通知再择机 FGS」；捕获该异常后**自动降级**为非 FGS 常驻通知并置「待提权」标记，回前台后自动提权（详见下方闭环） |
| 全版本 | 通知渠道重要性 | SDK 建默认渠道 `IMPORTANCE_LOW`（避免每次响铃），业务可覆盖 |

**BFGS 后台启动受限的闭环处理（Degrade → Promote）**

后台启动 FGS 是 Android 12/14 上最隐蔽的崩溃源。SDK 对保活服务的拉起统一走「防御 + 提权」闭环：

1. **防御拉起：** `startForegroundService()` / `startForeground()` 一律包裹 `try-catch(ForegroundServiceStartNotAllowedException)`。
2. **自动降级：** 捕获受限异常后，**不重试、不崩溃**，改为普通常驻通知（`IMPORTANCE_LOW`，不占 FGS 额度），在 `ILiveKitStore` 落「待提权（pending_promotion）」标记，并上报 `Degraded`。
3. **自动提权：** SDK 通过内部零依赖生命周期追踪器（§8.6，非 `androidx.lifecycle-process`）感知回前台事件 `onAppForegrounded`，扫描所有「待提权」活动，此刻处于前台可合法拉起 FGS，将其**提权（Promotion）** 回标准保活通道，清除标记。
4. 提权失败（如权限仍缺）则维持降级态并保留标记，等待下次前台时机，保证幂等。

**`notifyId` 冲突规避：** 由复合键稳定哈希派生并预留 SDK 专属高位段（如 `0x4C4B_0000` 起），避免与宿主自有通知 ID 撞车导致互相顶替。

**Manifest 需求（宿主侧）会由 SDK 文档明确列出**，SDK 不静默合并高危权限。

---

## 8. 并发模型与线程安全

* **状态机串行化：** 每个 `Internal_Key` 的所有变更经由 **单一 `CoroutineDispatcher`**（`limitedParallelism(1)` 或 Actor 模式）串行处理，杜绝 read-modify-write 竞态导致的 `local.seq` 错乱。
* **公开 API 线程无关：** `start / update / end / dispatchRawJson` 可在任意线程调用，内部 `withContext` 切换，不阻塞调用方（尤其推送回调线程）。
* **渲染切主线程：** 真正的 `NotificationManager.notify()` / `startForeground()` 统一在主线程执行。
* **Registry 读写：** `TemplateRegistry` 用 `ConcurrentHashMap`，注册通常在 `Application.onCreate` 完成，运行期以读为主。
* **背压：** `ThrottleQueue` 的 `Channel` 使用 `CONFLATED` 或容量上限 + 丢弃最旧策略，防止突发流量堆积 OOM。

### 8.6 宿主前后台无感感知机制（Zero-Dependency Lifecycle Tracking）

* **无外部依赖设计：** 针对 Android 14+ BFGS 受限后「回前台自动提权（Promotion）」的场景，SDK **拒绝引入** `androidx.lifecycle:lifecycle-process`，以死守「零强依赖」基调——规避宿主 AndroidX 版本冲突、去 AndroidX 魔改架构，以及 `androidx.startup` 自动 `ContentProvider` 在多进程 / 启动优化下的偶发初始化乱序隐患。
* **原生动态注入：** 在 `LiveKit.init` 时于 `applicationContext` 动态注册 `Application.ActivityLifecycleCallbacks`，基于活跃 Activity 引用计数维护「前后台状态机」，对宿主完全透明，无需业务方在各 Activity 打点。
* **多进程安全：** 该计数器仅在 `:main` 进程生效；`:push` 进程不承载 Activity，计数恒为 0，天然规避多进程生命周期交叉污染。跨进程状态一致性仍由 §5 的 `ILiveKitStore` 保证，二者职责正交。
* **代码收敛位置：** 该追踪器收敛于主 artifact 内的 internal 包 `internal.lifecycle`（与 `TemplateRegistry`/`OrderStateMachine` 平级），**不单开可发布模块**（避免 30 行代码换一条依赖坐标），亦**不并入 `LiveKit` 门面**（保持门面单一职责、便于 §13 对前后台状态机做替身单测）。

```kotlin
internal object LiveKitLifecycleTracker : Application.ActivityLifecycleCallbacks {
    private var startedActivityCount = 0

    /** 供引擎消费的只读前台状态 */
    val isAppInForeground: Boolean get() = startedActivityCount > 0

    fun startTracking(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivityCount == 0) LiveKitEngine.onAppForegrounded() // 触发提权管线
        startedActivityCount++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        if (startedActivityCount == 0) LiveKitEngine.onAppBackgrounded()
    }

    // 其余回调空实现
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
```

> 计数读写均发生在主线程（`ActivityLifecycleCallbacks` 契约），无需额外同步；引擎侧消费 `onAppForegrounded` 时再切入 §8 的串行 `Dispatcher` 处理提权。

---

## 9. 生命周期时序

```
START:
  dispatchRawJson --> 解析/Schema校验 --> 状态机(seq基线=in.seq) --> 落盘
                  --> 限流(Leading立即渲染) --> PlatformAdaptor.notify
                  --> 若含 clear_policy: 注册 AlarmManager 兜底移除

UPDATE:
  dispatchRawJson --> 校验 --> 乱序审计 --> (通过)增量Merge+落盘
                  --> 限流窗口合并 --> Trailing 单次渲染

END:
  dispatchRawJson --> 校验 --> 乱序审计 --> flush当前态并立即渲染终态
                  --> 取消该Key协程Job/清 RemoteViews 缓存
                  --> immediate?  是:立即cancel通知  否:保留至dismiss_after
                  --> 取消/重置 AlarmManager
```

**`clear_policy` 的可靠实现（非精确闹钟 + 静态 Receiver）**

`dismiss_after_seconds` **不能用协程 `delay` 实现**——进程被杀后协程即消失，卡片将永久残留。必须用 `AlarmManager` 注册一次性移除任务，进程重启后由持久化状态重建定时器。具体规约：

* **非精确对齐，免敏感权限：** 一律使用 `AlarmManager.setAndAllowWhileIdle()`（**禁用**精确闹钟 `setExactAndAllowWhileIdle`）。「超时销毁」允许数秒系统批处理对齐偏差，从而**规避 Android 12+ 的 `SCHEDULE_EXACT_ALARM` 权限审查**，同时享受系统 Batching 降低唤醒功耗。
* **目标必须是静态 Receiver：** `PendingIntent` 的目标 **必须是 Manifest 清单静态声明的 `BroadcastReceiver`（`android:exported="false"`）**，而**非动态注册**的 Receiver——动态 Receiver 随进程死亡消失，会让「扛进程被杀」这一初衷落空；只有清单静态 Receiver 才能在进程已死时被系统重新拉起执行移除。
* **安全合规：** `PendingIntent` 强制携带 `FLAG_IMMUTABLE`（Android 12+ 要求），`Intent` **显式指定宿主包名与目标 Component**，携带复合键定位待清理活动，杜绝隐式广播被劫持。
* **幂等重建：** 进程重启后引擎依据 `ILiveKitStore` 中残留的 `clear_policy` 与落盘时间重算剩余时长并重挂闹钟，多次重建幂等。

---

## 10. 数据协议与开放接口（API & Schema）

### 10.1 通用外壳 JSON Schema

```json
{
  "protocol_version": "int (必填, 当前=1, 用于协议演进兼容)",
  "biz_type": "string (必填, 标识业务线)",
  "activity_id": "string (必填, 业务线内唯一)",
  "action": "START | UPDATE | END (必填)",
  "template_id": "string (START 必填; UPDATE 携带则可触发隐式 START)",
  "seq_id": "long (必填, 单调递增序列号)",
  "timestamp": "long (必填, 毫秒时间戳)",
  "clear_policy": {
    "dismiss_after_seconds": "int (可选, 默认 300s 后自动移除卡片)"
  },
  "payload": {
    "//": "业务完全自定义的动态 KV 键值对"
  }
}
```

**协议校验规则**

* 缺失必填字段 → 整包拒绝并回调 `LiveKitEvent.MalformedPayload`，**绝不部分应用**。
* `protocol_version` 高于 SDK 支持上限 → 尽力解析已知字段，未知字段忽略（向前兼容），并上报 `UnsupportedVersion`。

### 10.2 核心门面 API（Kotlin）

```kotlin
package com.github.yourname.livekit

import android.content.Context
import android.widget.RemoteViews
import androidx.annotation.LayoutRes

object LiveKit {

    /** 在 Application 中调用，初始化全局配置 */
    fun init(context: Context, config: LiveKitConfig)

    /**
     * 业务线注册定制 UI 样式
     * @param binder 数据绑定回调，在限流队列触发刷新时执行（内部 try-catch 兜底）
     */
    fun registerTemplate(
        bizType: String,
        templateId: String,
        @LayoutRes layoutId: Int,
        binder: (views: RemoteViews, payload: Map<String, Any>) -> Unit
    )

    /** 分发并解析来自任意通道（本地、FCM、自建长连接）的原始标准 JSON */
    fun dispatchRawJson(jsonString: String)

    /** 客户端本地手动启动实时活动 */
    fun start(bizType: String, activityId: String, templateId: String, payload: Map<String, Any>)

    /** 客户端本地手动更新数据 */
    fun update(bizType: String, activityId: String, payload: Map<String, Any>)

    /** 客户端本地手动结束实时活动 */
    fun end(bizType: String, activityId: String, immediate: Boolean = false)

    /** 主动查询通知权限（Android 13+），供宿主决定申请时机 */
    fun hasNotificationPermission(): Boolean

    /** 权限 / 系统能力变更后刷新能力快照（§6）。宿主在用户授予 POST_NOTIFICATIONS（或重新打开通知开关）
     *  后调用，SDK 重新探测并在权限由关转开时自动补渲染被 PermissionMissing 拦下的活动。 */
    fun refreshCapabilities()

    /** 注册全局观测 / 异常监听器（见 §12） */
    fun setObserver(observer: LiveKitObserver)
}
```

### 10.3 配置对象

```kotlin
data class LiveKitConfig(
    val store: ILiveKitStore? = null,           // 默认 MMKV，可替换
    val defaultChannelId: String = "livekit_default",
    val defaultThrottleWindowMs: Long = 1000L,
    val perBizThrottleMs: Map<String, Long> = emptyMap(),
    val fgsType: Int = FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    val orphanBufferMs: Long = 3000L,
    val maxBitmapBytes: Int = 200 * 1024,        // Binder 沙箱下采样阈值（§11）
    val chronometerRomBlacklist: Set<String> = emptySet(), // 增量补充异常 ROM（§4.4）
    val maxThrottleWindowMs: Long = 5000L,       // 静默压实 Max Backoff 上限（§4.2/§6）
    val storeRetryBackoffMs: List<Long> = listOf(50L, 100L, 200L), // 存储写冲突退避（§5.2）
    val observer: LiveKitObserver? = null,
)

interface ILiveKitStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun keys(): Set<String>
    /** 跨进程变更观察（ContentObserver / MMKV 回调） */
    fun observe(onChanged: (key: String) -> Unit)
}
```

---

## 11. 内存泄漏防御与稳定性规范

作为公共基础 SDK，绝不能因自身异常导致宿主崩溃或泄漏。

* **Context 归一化：** 所有持久持有的 `Context` 强制 `applicationContext`，杜绝直接持有 `Activity`。`binder` 回调中亦不向外泄漏 `RemoteViews` 之外的引用。
* **通知解绑保护：** `end` 时显式释放该 `templateId` 绑定的 `RemoteViews` 缓存、取消该 Key 的协程 `Job`、注销对应 `AlarmManager` 与 `ContentObserver`。
* **容错沙箱（Sandbox Execution）：** 业务方 `binder` 属不可控代码，SDK 对每次 `binder.invoke()` 用 `try-catch(Throwable)` 包裹。业务方 NPE / 异常被捕获并上报自定义监听器，**坚决不引发宿主闪退**；该次渲染跳过，保留上一稳定态。
* **全局兜底：** 引擎顶层协程附带 `CoroutineExceptionHandler`，任何未捕获异常转为观测事件，不外抛。
* **资源上限与 LRU 回收：** 单进程活跃活动数设软上限（默认 50），超限按 LRU 回收最旧，防止业务泄漏拖垮通知栏。
* **Binder 事务沙箱（Bitmap 防溢出）：** `RemoteViews` 会被序列化后经 Binder 传给 `SystemServer` 渲染，而 Binder 事务存在 **1MB 全局上限**——业务方在 `payload` 塞入过大 Base64 图 / 在 `binder` 绑定高清大图（骑手头像、商品缩略图）极易触发 `TransactionTooLargeException` 强杀宿主。SDK 对每次渲染涉及的 `Bitmap` 做前置管控：
  * 超过阈值（默认 200KB，或长边超通知栏安全尺寸）的图源，强制**等比下采样（Downsampling）** 裁剪至安全尺寸（如 `≤ 100dp × 100dp`）后再绑定；
  * 对超大图优先转为**本地文件 `Uri`**（`setImageViewUri`）传递，避免整块 `Bitmap` 走 Binder；
  * 单个 `RemoteViews` 总估算体积逼近上限时，丢弃非关键图元并上报 `Degraded`，从源头阻断 `TransactionTooLargeException`。

---

## 12. 可观测性与异常上报

统一事件流，供宿主接入监控 / 埋点。

```kotlin
interface LiveKitObserver {
    fun onEvent(event: LiveKitEvent)
    fun onError(throwable: Throwable, key: String?)  // binder 沙箱捕获等
}

sealed interface LiveKitEvent {
    data class Rendered(val key: String, val channel: RenderChannel) : LiveKitEvent
    data class Dropped(val key: String, val reason: DropReason) : LiveKitEvent  // OUT_OF_ORDER / ORPHAN / MALFORMED / STORE_BUSY
    data class Degraded(val key: String, val from: RenderChannel, val to: RenderChannel) : LiveKitEvent
    data class PermissionMissing(val key: String) : LiveKitEvent
    data class Throttled(val key: String, val mergedCount: Int) : LiveKitEvent
}
```

关键可观测指标：丢弃率（乱序 / 畸形）、平均合并倍率（限流有效性）、降级发生率、渲染失败率、活跃活动数。

---

## 13. 质量矩阵（模块 ↔ 测试用例映射规约）

测试设计遵循「职责正交拆分 + 全场景矩阵覆盖」，白盒与集成测试须完成以下模块 ↔ 用例映射，可直接作为 Contributor 认领任务的抓手。

| 目标模块（Pkg） | 测试用例 | 分类 | 核心断言与预期 |
|---|---|---|---|
| `internal.state`<br>(OrderStateMachine) | `testOutOfOrderDrop` | 单元 | `local.seq=10`，连灌 `seq=9` 的 UPDATE。断言返回 `false`（静默丢弃）+ `Dropped(OUT_OF_ORDER)`；`local.seq` 不回退 |
| `internal.state` | `testEqualSeqTimestampTiebreak` | 单元 | `seq` 相等时以 `timestamp` 裁决，较小时间戳被丢弃 |
| `internal.state` | `testOrphanUpdateBuffering` | 单元 | 注入无 `template_id` 的孤儿 UPDATE 进 Buffer；虚拟时钟推进 3000ms 后释放并 `Dropped(ORPHAN)`；若 2000ms 内注入对应 START，缓冲包被物化渲染 |
| `internal.state` | `testStaleEndDropped` | 单元 | 迟到低 `seq` 的 END 被丢弃，不误杀已重启的同 ID 新活动 |
| `internal.queue`<br>(ThrottleQueue) | `testLeadingTrailingEdge` | 单元 | `TestDispatcher` 虚拟时钟，0/200/500ms 灌 3 帧：0ms 立即 notify（Leading）；200/500ms 仅内存增量覆盖不刷新；1000ms 窗口末触发一次 notify（Trailing）。总渲染次数恒为 2 |
| `internal.queue` | `testEndPriority` | 单元 | 窗口内 300ms 灌 END：无视剩余 700ms 节流，窗口立即关闭并 flush 终态 |
| `internal.lifecycle`<br>(LiveKitLifecycleTracker) | `testForegroundPromotion` | 单元/Mock | 活跃 Activity 计数 `0→1`，触发 `onAppForegrounded`；引擎扫描 `pending_promotion` 活动并成功提权回标准 FGS |
| `internal.store`<br>(ILiveKitStore) | `testConcurrentColdStartLock` | 多进程集成 | 双进程用例，`:push` 写 + `:main` 读同毫秒并发冷启动；断言未开 `multiprocess=true`；触发 SQLite 锁时经阶梯退避（50/100/200ms）3 次内完成，无崩溃 |
| `internal.store` | `testCrossProcessObserverWakeup` | 多进程集成 | `:push` 写入后 `notifyChange`，验证 `:main` 的 `ContentObserver` 被唤醒且数据一致 |
| `internal.adaptor`<br>(PlatformAdaptor) | `testTransactionTooLargeDefense` | 边界/容错 | 向 `binder` 注入含单张 2MB 巨图的 payload；断言前置拦截、下采样至 `≤200KB` 后提交，不外抛 `TransactionTooLargeException` |
| `internal.adaptor` | `testChronometerRomFallback` | 兼容/白盒 | `Build.MANUFACTURER` 置黑名单机型 + 带倒计时活动；断言拒绝托管 `Chronometer`，降级 `AlarmManager` 节点式重绘 |
| `internal.adaptor` | `testCapabilityDegradeChain` | 兼容 | 在 API 26/31/34/36 跑降级链，断言无 `NoClassDefFoundError`、无硬失败 |
| `public.facade`<br>(LiveKit) | `testSilentCompaction` | 降级/能耗 | `POST_NOTIFICATIONS` 置拒绝，高频 `update` 10 次；断言限流 Backoff 放大至 5000ms 且所有局部重绘定时器挂起（挂起率 100%），零算力开销 |
| `public.facade` | `testBinderSandboxIsolation` | 容错 | 业务 `binder` 内故意抛 NPE；断言宿主不崩，异常流转 `onError`，通知栏保留上一帧稳定态 |
| `public.facade` | `testMalformedJsonRejected` | 容错 | 灌入缺 `biz_type` / `seq_id` 的畸形 JSON；断言整包拒绝 + `MalformedPayload`，绝不部分应用 |
| 端到端 | `testProcessDeathRecovery` | 集成 | 杀进程后重建，验证 `AlarmManager` 兜底移除、`clear_policy` 幂等重挂、`local.seq` 防乱序仍生效 |

---

## 14. 兼容性与版本演进

* **`minSdk` 建议 24（Android 7.0）**，`compileSdk` 追随最新（覆盖 `ProgressStyle`）。
* **协议演进：** `protocol_version` 单调递增，SDK 承诺对旧版本协议向后兼容；新增字段一律可选，删除字段需跨大版本弃用期。
* **公开 API 稳定性：** `LiveKit` 门面遵循语义化版本；破坏性变更仅在 major 版本，并提供迁移指南。
* **依赖策略：** MMKV 作为**可选**依赖（`compileOnly` + 运行期探测），未集成时自动回退 `ContentProvider` 实现，保证 SDK 零强依赖可用。

**路线图：拥抱 Jetpack Glance（Compose 化 UI 注册）**

当前 `registerTemplate` 以 `@LayoutRes + RemoteViews binder` 为准，强依赖 XML 布局，对纯 Compose 项目不友好。规划在**不破坏现有 API** 的前提下，新增一层基于 **Jetpack Glance** 的可选注册入口——Glance 是 Google 官方用 Compose 语法产出 `RemoteViews`（面向 Widget / 通知栏）的框架：

```kotlin
// 规划中的扩展入口（与现有 RemoteViews 注册并存）
fun registerGlanceTemplate(
    bizType: String,
    templateId: String,
    content: @Composable (payload: Map<String, Any>) -> Unit
)
```

Glance 产出的 `RemoteViews` 复用同一套限流、乱序、跨进程、Binder 沙箱管线，业务方无需感知底层差异。落地节奏跟随 Glance 对 `ProgressStyle` / Live Updates 的官方支持成熟度推进。

---

## 15. 术语表

| 术语 | 含义 |
|---|---|
| Internal Key | `biz_type#activity_id` 复合键，状态机唯一索引 |
| Leading / Trailing Edge | 限流窗口的首帧立即渲染 / 尾帧合并渲染 |
| Orphan Update | 先于 START 到达的 UPDATE |
| Capability Probe | 系统能力探测（启动时缓存，可由 `refreshCapabilities()` 刷新） |
| FGS | Foreground Service，前台服务 |
| UDF | Unidirectional Data Flow，单向数据流 |

---

**v1.0 → v1.1 关键修正：** ①`LocalBroadcast` 跨进程错误 → `ContentProvider`/显式广播（§5.3）；②多进程 `SharedPreferences` 不可靠 → MMKV/ContentProvider（§5.2）；③`clear_policy` 协程 delay → `AlarmManager`（§9）；④限流改 Leading+Trailing 降首帧延迟（§4.2）。新增：权限与 FGS 合规（§7）、能力探测与降级矩阵（§6）、并发模型（§8）、可观测性（§12）、测试策略（§13）、协议版本化（§10.1）。

**v1.1 → v1.2 新增（生产级补强）：** ①Binder 事务沙箱——Bitmap 下采样防 `TransactionTooLargeException`（§11）；②BFGS 后台启动受限闭环——`ForegroundServiceStartNotAllowedException` 自动降级 + 回前台自动提权（§6/§7）；③`Chronometer` ROM 边缘 case——负数防御 + 机型黑名单降级 `AlarmManager` 重绘（§4.4）；④路线图纳入 Jetpack Glance（Compose 化 UI 注册，§14）；⑤**架构决策**：前后台感知拒绝 `androidx.lifecycle-process`，改用内部 `ActivityLifecycleCallbacks` 零依赖实现，收敛于 `internal.lifecycle` 包（§8.6）。

**v1.2 → v1.3 边缘场景压实（评审级封版）：** ①多进程冷启动并发锁防御——provider 单实例漏斗串行化 + WAL + 阶梯退避重试，并纠正「`multiprocess=true` 反效果」反模式（§5.2）；②无权限静默压实（Silent Compaction）——无通知权限时限流切 Max Backoff、挂起重绘定时器省算力（§4.2/§6）；③`clear_policy` 闹钟规约——非精确 `setAndAllowWhileIdle` 免 `SCHEDULE_EXACT_ALARM`、目标改**静态清单 Receiver** 以扛进程被杀、`FLAG_IMMUTABLE` + 显式 Component（§9）。**两处工程纠错**：`android:multiprocess="true"` 反效果、动态 Receiver 无法扛进程死亡。

**文档收口（开工路标）：** ①新增 §3.6 门面 `LiveKit` 与引擎 `LiveKitEngine` 的职责边界与信息屏障（可见性 / 进程 / 校验 / 分发 / 生命周期 / 容灾映射 + 进程漏斗约束）；②§13 由粗粒度策略升级为「模块 ↔ 测试用例」质量矩阵，覆盖 state/queue/lifecycle/store/adaptor/facade 全模块与端到端进程死亡恢复，供 Contributor 认领。

**v1.3 → v1.4 权限恢复闭环：** 能力快照（Capability Probe）原仅在 `init()` 探测一次；若初始化时 `POST_NOTIFICATIONS` 未授予，快照持续误判「无通知权限」，即使用户后续授权也无法渲染。新增 `LiveKit.refreshCapabilities()`：宿主在权限授予后调用，SDK 重新探测；检测到权限由关转开时自动补渲染被 `PermissionMissing` 拦下的在途活动，闭合「无权限静默压实（§6/§7）」的恢复路径。
```
