/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.app.NotificationManagerCompat
import com.morainet.livekit.internal.LiveKitEngine
import com.morainet.livekit.internal.ProtocolVersion
import com.morainet.livekit.internal.platform.BitmapSandbox
import com.morainet.livekit.model.Action
import com.morainet.livekit.model.ClearPolicy
import com.morainet.livekit.model.DropReason
import com.morainet.livekit.model.Envelope
import com.morainet.livekit.model.LiveProgressSpec
import com.morainet.livekit.model.LiveKitEvent
import com.morainet.livekit.model.LiveKitObserver
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * 公开门面（白皮书 §3.6）：唯一对外入口，无状态，仅做参数粗校与分发；
 * 状态机 / 限流 / 渲染由内部 [LiveKitEngine] 承接。
 */
object LiveKit {

    @Volatile private var appContext: Context? = null
    @Volatile private var observer: LiveKitObserver? = null
    @Volatile private var config: LiveKitConfig = LiveKitConfig()
    private val localSeq = AtomicLong(System.currentTimeMillis())

    fun init(context: Context, config: LiveKitConfig) {
        appContext = context.applicationContext
        observer = config.observer
        this.config = config
        LiveKitEngine.init(context, config, config.observer)
    }

    /**
     * 在 RemoteViews binder 内安全设置图片（白皮书 §11）：先经 Bitmap 沙箱下采样，
     * 再 setImageViewBitmap，杜绝大图经 Binder 触发 TransactionTooLargeException。
     */
    fun setImageBitmapSafe(views: RemoteViews, @IdRes viewId: Int, bitmap: Bitmap) {
        val safe = BitmapSandbox.downsample(bitmap, config.maxBitmapBytes, config.maxBitmapDimenPx)
        views.setImageViewBitmap(viewId, safe)
    }

    fun setObserver(observer: LiveKitObserver) {
        this.observer = observer
        LiveKitEngine.setObserver(observer)
    }

    /**
     * 业务线注册定制 UI 样式。[binder] 在限流触发刷新时执行，内部有沙箱兜底。
     */
    fun registerTemplate(
        bizType: String,
        templateId: String,
        @LayoutRes layoutId: Int,
        binder: (views: RemoteViews, payload: Map<String, Any?>) -> Unit,
    ) {
        LiveKitEngine.registry.register(bizType, templateId, layoutId, binder)
    }

    /**
     * 注册 Android 16+ 原生 ProgressStyle 模板（白皮书 §6）。能力可用时优先此通道，
     * 否则自动降级到同 templateId 的 RemoteViews 模板。
     */
    fun registerProgressTemplate(
        bizType: String,
        templateId: String,
        @DrawableRes smallIconRes: Int,
        binder: (payload: Map<String, Any?>) -> LiveProgressSpec,
    ) {
        LiveKitEngine.registry.registerProgress(bizType, templateId, smallIconRes, binder)
    }

    /** 分发来自任意通道的标准 JSON。畸形整包拒绝，绝不部分应用。 */
    fun dispatchRawJson(jsonString: String) {
        val env = try {
            parse(jsonString)
        } catch (t: Throwable) {
            observer?.onEvent(LiveKitEvent.Dropped(key = "?", reason = DropReason.MALFORMED))
            observer?.onError(t, null)
            return
        }
        // 未来协议版本：已尽力解析已知字段，上报后照常处理（向前兼容）。
        if (ProtocolVersion.isFuture(env.protocolVersion)) {
            observer?.onEvent(LiveKitEvent.UnsupportedVersion(env.protocolVersion, env.internalKey))
        }
        LiveKitEngine.dispatch(env)
    }

    fun start(bizType: String, activityId: String, templateId: String, payload: Map<String, Any?>) =
        LiveKitEngine.dispatch(envelope(bizType, activityId, Action.START, templateId, payload))

    fun update(bizType: String, activityId: String, payload: Map<String, Any?>) =
        LiveKitEngine.dispatch(envelope(bizType, activityId, Action.UPDATE, null, payload))

    fun end(bizType: String, activityId: String, immediate: Boolean = false) {
        LiveKitEngine.dispatch(envelope(bizType, activityId, Action.END, null, emptyMap()))
        if (immediate) LiveKitEngine.dismiss("$bizType#$activityId", immediate = true)
    }

    /** Android 13+ 通知权限查询，供宿主决定申请时机。 */
    fun hasNotificationPermission(): Boolean {
        val ctx = appContext ?: return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /**
     * 权限 / 系统能力变更后刷新能力快照。宿主在用户授予 POST_NOTIFICATIONS（或重新打开通知开关）
     * 后调用，SDK 会重新探测并在权限由关转开时自动补渲染被 [LiveKitEvent.PermissionMissing] 拦下的活动。
     */
    fun refreshCapabilities() {
        LiveKitEngine.refreshCapabilities()
    }

    private fun envelope(
        bizType: String,
        activityId: String,
        action: Action,
        templateId: String?,
        payload: Map<String, Any?>,
    ) = Envelope(
        protocolVersion = 1,
        bizType = bizType,
        activityId = activityId,
        action = action,
        templateId = templateId,
        seqId = localSeq.incrementAndGet(),
        timestamp = System.currentTimeMillis(),
        payload = payload,
    )

    internal fun parse(json: String): Envelope {
        val o = JSONObject(json)
        val payload = o.optJSONObject("payload")?.let { p ->
            buildMap { p.keys().forEach { k -> put(k, p.get(k)) } }
        } ?: emptyMap()
        val clearPolicy = o.optJSONObject("clear_policy")?.let {
            ClearPolicy(it.optInt("dismiss_after_seconds", 300))
        }
        return Envelope(
            protocolVersion = o.optInt("protocol_version", 1),
            bizType = o.getString("biz_type"),
            activityId = o.getString("activity_id"),
            action = Action.valueOf(o.getString("action")),
            templateId = if (o.has("template_id")) o.optString("template_id") else null,
            seqId = o.getLong("seq_id"),
            timestamp = o.getLong("timestamp"),
            clearPolicy = clearPolicy,
            payload = payload,
        )
    }
}
