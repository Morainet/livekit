/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.morainet.livekit.LiveKitConfig
import com.morainet.livekit.internal.lifecycle.LiveKitLifecycleTracker
import com.morainet.livekit.internal.platform.Capabilities
import com.morainet.livekit.internal.platform.CapabilityProbe
import com.morainet.livekit.internal.platform.LiveKitCleanupReceiver
import com.morainet.livekit.internal.platform.LiveKitActionReceiver
import com.morainet.livekit.internal.platform.LiveKitForegroundService
import com.morainet.livekit.internal.platform.NotificationAdaptor
import com.morainet.livekit.internal.platform.NotifyIdMapper
import com.morainet.livekit.internal.platform.RenderRequest
import com.morainet.livekit.internal.queue.ThrottleQueue
import com.morainet.livekit.internal.state.Decision
import com.morainet.livekit.internal.state.OrderStateMachine
import com.morainet.livekit.internal.state.OrphanBuffer
import com.morainet.livekit.internal.state.PersistentStateStore
import com.morainet.livekit.internal.state.StateStore
import com.morainet.livekit.internal.store.ContentProviderStore
import com.morainet.livekit.internal.template.TemplateRegistry
import com.morainet.livekit.internal.util.ProcessUtil
import com.morainet.livekit.model.Action
import com.morainet.livekit.model.DropReason
import com.morainet.livekit.model.Envelope
import com.morainet.livekit.model.LiveKitEvent
import com.morainet.livekit.model.LiveKitObserver
import com.morainet.livekit.model.RenderChannel
import com.morainet.livekit.store.ILiveKitStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内部引擎（白皮书 §3.6）：中央大脑，强状态、串行驱动。
 *
 * 数据流：dispatch（任意进程）→ 状态机裁决 → 持久化落盘（触发 notifyChange）。
 * 渲染仅由 :main 进程的 store 观察者驱动（onStoreChanged），从而统一「本地触发」
 * 与「:push 跨进程唤醒」两条路径。:push 进程只落盘不渲染。
 */
internal object LiveKitEngine {

    /** 卡片点击跨进程信号 key 前缀，与活动状态 key 隔离，避免误触发渲染。 */
    private const val ACTION_SIGNAL_PREFIX = "__livekit_action__"
    private const val ACTION_SIGNAL_SEP = '\u0001'

    private lateinit var appContext: Context
    private var config: LiveKitConfig = LiveKitConfig()
    private var observer: LiveKitObserver? = null

    val registry = TemplateRegistry()
    private lateinit var store: ILiveKitStore
    private lateinit var stateStore: StateStore
    private lateinit var stateMachine: OrderStateMachine
    private lateinit var scope: CoroutineScope

    private var isMain = false
    private var adaptor: NotificationAdaptor? = null
    private var throttle: ThrottleQueue? = null
    private var caps: Capabilities? = null

    // 孤儿 UPDATE 缓冲（白皮书 §4.3）。仅在引擎串行线程访问。
    private val orphanBuffer = OrphanBuffer()
    private val orphanTimers = HashMap<String, Job>()

    // FGS 保活状态（仅 :main）。
    private val fgsLock = Any()
    private val activeKeys = LinkedHashSet<String>()
    private val lastRequests = HashMap<String, RenderRequest>()
    private var anchorKey: String? = null
    private var fgsRequested = false
    @Volatile private var pendingPromotion = false

    @Volatile private var initialized = false

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun init(context: Context, config: LiveKitConfig, observer: LiveKitObserver?) {
        appContext = context.applicationContext
        this.config = config
        this.observer = observer
        store = config.store ?: ContentProviderStore(appContext)
        stateStore = PersistentStateStore(store)
        stateMachine = OrderStateMachine(stateStore)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        isMain = ProcessUtil.isMainProcess(appContext)

        if (isMain) {
            adaptor = NotificationAdaptor(
            appContext, registry, config.smallIconRes,
            config.maxBitmapBytes, config.maxBitmapDimenPx,
            onError = { t, k -> this.observer?.onError(t, k) },
            actionIntentFactory = { internalKey, actionId -> actionPendingIntent(internalKey, actionId) },
        )
            throttle = ThrottleQueue(scope, config.defaultThrottleWindowMs) { key, payload -> renderNow(key, payload) }
            caps = CapabilityProbe.probe(appContext)
            (appContext as? Application)?.let { LiveKitLifecycleTracker.startTracking(it) }
            store.observe { key -> onStoreChanged(key) }
            reconcileOnStart() // 进程重启 / 被 :push 拉起时，补渲染已落盘的活动，兜住观察者注册前的写入竞态。
        }
        initialized = true
    }

    fun setObserver(observer: LiveKitObserver?) { this.observer = observer }

    /**
     * 重新探测系统能力（白皮书 §6）。宿主在用户授予 POST_NOTIFICATIONS（或切换通知开关）后调用，
     * 让 caps 反映最新状态，避免因 init 时一次性快照过期而持续抛 PermissionMissing。
     * 若探测到权限已开且仍有活动在途，自动补渲染一次。
     */
    fun refreshCapabilities() {
        if (!initialized || !isMain) return
        val wasOff = caps?.notificationsEnabled == false
        caps = CapabilityProbe.probe(appContext)
        if (wasOff && caps?.notificationsEnabled == true) {
            // 权限刚开：对已落盘的活动补一次渲染（兜住 init 后被 PermissionMissing 拦下的请求）。
            scope.launch { reRenderAll() }
        }
    }

    private suspend fun reRenderAll() {
        for (key in stateStore.keys()) {
            val state = stateStore.get(key) ?: continue
            if (state.ended || state.templateId == null) continue
            renderNow(key, state.payload)
        }
    }

    /** 任意进程：裁决 + 落盘。渲染由 :main 的 store 观察者接管。 */
    fun dispatch(env: Envelope) {
        if (!initialized) return
        scope.launch {
            try {
                when (val decision = stateMachine.process(env)) {
                    is Decision.Dropped ->
                        if (decision.reason == DropReason.ORPHAN) bufferOrphan(env)
                        else emit(LiveKitEvent.Dropped(decision.key, decision.reason))
                    is Decision.Accepted -> {
                        // 已落盘 → provider notifyChange 唤醒 :main。START 落地后重放先到的孤儿 UPDATE。
                        if (env.action != Action.END) replayOrphans(decision.key)
                    }
                }
            } catch (t: Throwable) {
                observer?.onError(t, env.internalKey)
            }
        }
    }

    private fun bufferOrphan(env: Envelope) {
        val key = env.internalKey
        orphanBuffer.add(env)
        orphanTimers.remove(key)?.cancel()
        orphanTimers[key] = scope.launch {
            delay(config.orphanBufferMs)
            if (orphanBuffer.has(key)) {
                orphanBuffer.clear(key)
                emit(LiveKitEvent.Dropped(key, DropReason.ORPHAN))
            }
            orphanTimers.remove(key)
        }
    }

    private fun replayOrphans(key: String) {
        if (!orphanBuffer.has(key)) return
        orphanTimers.remove(key)?.cancel()
        orphanBuffer.drain(key).forEach { stateMachine.process(it) }
    }

    /** 结束：从 store 移除，:main 观察者据此取消通知并收敛 FGS。 */
    fun dismiss(internalKey: String, immediate: Boolean) {
        if (!initialized) return
        scope.launch { runCatching { stateStore.remove(internalKey) } }
    }

    /**
     * clear_policy 到期的同步清理（白皮书 §9）。必须同步完成：BroadcastReceiver 的进程
     * 在 onReceive 返回后随时可能被杀，异步协程会来不及执行。先移除落盘态再同步取消通知，
     * 避免被 reconcile 重渲染。
     */
    fun handleCleanup(internalKey: String) {
        if (!initialized) return
        runCatching { stateStore.remove(internalKey) }
        if (isMain) {
            runCatching { NotificationManagerCompat.from(appContext).cancel(NotifyIdMapper.idFor(internalKey)) }
            onActivityEnded(internalKey)
        }
    }

    /**
     * 卡片交互按钮点击入口（白皮书 §6 增强）。
     *
     * 可能在任意进程触发（:push 渲染的卡片点击）：:main 直接 emit；:push 经内置 provider 的
     * 纯信号通道把信号送到 :main，由 [onStoreChanged] 解析前缀后 emit。
     * 注意 :push 的 observer 为 null，绝不能直接 emit（事件会丢）。
     */
    fun handleAction(internalKey: String, actionId: String) {
        if (!initialized) return
        if (isMain) {
            emit(LiveKitEvent.ActionClicked(actionId, internalKey))
        } else {
            // :push → :main：发纯信号（不落盘），key 用专属前缀隔离，避免触发渲染。
            runCatching {
                val authority = "${appContext.packageName}.livekit.store"
                val signal = "$ACTION_SIGNAL_PREFIX$actionId$ACTION_SIGNAL_SEP$internalKey"
                appContext.contentResolver.call(authority, "notify", signal, null)
            }
        }
    }

    private fun reconcileOnStart() {
        scope.launch {
            runCatching { stateStore.keys() }.getOrDefault(emptySet()).forEach { onStoreChanged(it) }
        }
    }

    private fun onStoreChanged(key: String) {
        if (!isMain) return
        // 点击信号（来自 :push 的纯信号通道）：解析后 emit，不进渲染链路。
        if (key.startsWith(ACTION_SIGNAL_PREFIX)) {
            val rest = key.removePrefix(ACTION_SIGNAL_PREFIX)
            val sep = rest.indexOf(ACTION_SIGNAL_SEP)
            if (sep > 0) {
                val actionId = rest.substring(0, sep)
                val internalKey = rest.substring(sep + 1) // ACTION_SIGNAL_SEP 为单字符
                emit(LiveKitEvent.ActionClicked(actionId, internalKey))
            }
            return
        }
        scope.launch {
            val state = stateStore.get(key)
            if (state == null) {
                cancelCleanup(key)
                withContext(Dispatchers.Main) {
                    adaptor?.dismiss(key, true)
                    onActivityEnded(key)
                }
                return@launch
            }
            if (state.lastAction == Action.END) {
                throttle?.submitEnd(key, state.payload)
                withContext(Dispatchers.Main) { onActivityEnded(key) }
            } else {
                throttle?.submit(key, state.payload)
            }
            // clear_policy：每次刷新重置超时定时器，静默一段时间后自动移除卡片。
            state.dismissAfterSeconds?.let { scheduleCleanup(key, it) }
        }
    }

    // ---- clear_policy 自动清理（白皮书 §9，非精确闹钟 + 静态 Receiver） ----

    private fun scheduleCleanup(key: String, seconds: Int) {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + seconds * 1000L
        // 非精确 setAndAllowWhileIdle：免 SCHEDULE_EXACT_ALARM 权限，允许系统批处理对齐降耗。
        // 用 WAKEUP 保证设备休眠时也能按时移除卡片（一次性、低频，功耗可忽略）。
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, cleanupPendingIntent(key))
    }

    private fun cancelCleanup(key: String) {
        (appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(cleanupPendingIntent(key))
    }

    private fun cleanupPendingIntent(key: String): PendingIntent {
        val intent = Intent(appContext, LiveKitCleanupReceiver::class.java).apply {
            action = LiveKitCleanupReceiver.ACTION
            putExtra(LiveKitCleanupReceiver.EXTRA_KEY, key)
        }
        return PendingIntent.getBroadcast(
            appContext,
            NotifyIdMapper.idFor(key),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 卡片交互按钮的 PendingIntent（白皮书 §6 增强）：显式 Intent 指向 [LiveKitActionReceiver]，
     * extra 带 actionId + key。requestCode 用 `NotifyIdMapper.idFor("$key#$actionId")`，
     * 既稳定（通知刷新时同按钮复用）又与通知 id 空间（仅 $key）隔离。
     */
    internal fun actionPendingIntent(internalKey: String, actionId: String): PendingIntent {
        val intent = Intent(appContext, LiveKitActionReceiver::class.java).apply {
            action = LiveKitActionReceiver.ACTION
            putExtra(LiveKitActionReceiver.EXTRA_KEY, internalKey)
            putExtra(LiveKitActionReceiver.EXTRA_ACTION, actionId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            NotifyIdMapper.idFor("$internalKey#$actionId"),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private suspend fun renderNow(key: String, payload: Map<String, Any?>) {
        val state = stateStore.get(key) ?: return
        if (caps?.notificationsEnabled == false) {
            emit(LiveKitEvent.PermissionMissing(key))
            return
        }
        val templateId = state.templateId ?: return
        val request = RenderRequest(
            internalKey = key,
            bizType = key.substringBefore('#'),
            templateId = templateId,
            payload = payload,
            channelId = config.defaultChannelId,
        )
        synchronized(fgsLock) { lastRequests[key] = request }
        val channel = withContext(Dispatchers.Main) {
            val c = adaptor?.render(request) ?: RenderChannel.NONE
            if (c != RenderChannel.NONE) onActivityActive(key)
            c
        }
        if (channel != RenderChannel.NONE) emit(LiveKitEvent.Rendered(key, channel))
    }

    // ---- FGS 保活闭环（:main 主线程调用） ----

    private fun onActivityActive(key: String) {
        val shouldPromote: Boolean
        synchronized(fgsLock) {
            activeKeys.add(key)
            if (anchorKey == null) anchorKey = key
            shouldPromote = config.enableForegroundService && !fgsRequested
        }
        if (shouldPromote) promoteToForeground()
    }

    private fun onActivityEnded(key: String) {
        var action: (() -> Unit)? = null
        synchronized(fgsLock) {
            activeKeys.remove(key)
            lastRequests.remove(key)
            if (key == anchorKey) {
                val next = activeKeys.firstOrNull()
                anchorKey = next
                action = if (next == null) {
                    { stopForegroundService() }
                } else {
                    { reanchorForeground() }
                }
            }
        }
        action?.invoke()
    }

    private fun promoteToForeground() {
        try {
            ContextCompat.startForegroundService(appContext, serviceIntent())
            synchronized(fgsLock) { fgsRequested = true }
            pendingPromotion = false
        } catch (t: Throwable) {
            onForegroundStartFailed(t)
        }
    }

    private fun reanchorForeground() {
        runCatching { ContextCompat.startForegroundService(appContext, serviceIntent()) }
            .onFailure { onForegroundStartFailed(it) }
    }

    private fun stopForegroundService() {
        synchronized(fgsLock) { fgsRequested = false }
        runCatching { appContext.stopService(serviceIntent()) }
    }

    fun onForegroundStartFailed(t: Throwable) {
        synchronized(fgsLock) { fgsRequested = false }
        pendingPromotion = true
        val key = anchorKey ?: "?"
        // BFGS 后台启动受限是预期的良性降级：只发 Degraded 事件，回前台会自动提权。
        // 绝不当作错误上报——否则污染宿主日志并可能触发崩溃采集的虚假 non-fatal。
        emit(LiveKitEvent.Degraded(key, RenderChannel.REMOTE_VIEWS, RenderChannel.REMOTE_VIEWS))
        if (!isBackgroundStartRestriction(t)) observer?.onError(t, key)
    }

    /** 按类名判定后台启动受限，避免在低于 API 31 的设备上引用该类导致 NoClassDefFoundError。 */
    private fun isBackgroundStartRestriction(t: Throwable): Boolean {
        val name = t.javaClass.name
        return name == "android.app.ForegroundServiceStartNotAllowedException" ||
            name == "android.app.BackgroundServiceStartNotAllowedException"
    }

    fun onAppForegrounded() {
        if (!initialized || !isMain) return
        val retry = pendingPromotion && synchronized(fgsLock) { activeKeys.isNotEmpty() }
        if (retry) promoteToForeground()
    }

    fun onAppBackgrounded() { /* 目前无需处理 */ }

    fun anchorInfo(): Pair<Int, Notification>? {
        val a = adaptor ?: return null
        val request = synchronized(fgsLock) { anchorKey?.let { lastRequests[it] } } ?: return null
        val notification = a.buildNotification(request) ?: return null
        return a.notifyIdFor(request.internalKey) to notification
    }

    fun fgsType(): Int = config.fgsType

    private fun serviceIntent() = Intent(appContext, LiveKitForegroundService::class.java)

    private fun emit(event: LiveKitEvent) {
        observer?.onEvent(event)
    }
}
