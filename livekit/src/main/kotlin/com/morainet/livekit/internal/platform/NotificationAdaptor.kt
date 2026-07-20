/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.morainet.livekit.internal.template.TemplateRegistry
import com.morainet.livekit.model.LiveProgressSpec
import com.morainet.livekit.model.RenderChannel

/**
 * 基于 NotificationManagerCompat + RemoteViews 的降级渲染通道（白皮书 §6 Legacy 通道，非 FGS）。
 *
 * 里程碑 2 范围：常驻通知 + 自定义 RemoteViews + binder 沙箱。
 * FGS 保活、BFGS 提权、ProgressStyle 原生通道留待后续里程碑。
 */
internal class NotificationAdaptor(
    private val context: Context,
    private val registry: TemplateRegistry,
    private val smallIconRes: Int,
    private val maxBitmapBytes: Int,
    private val maxBitmapDimenPx: Int,
    private val onError: (Throwable, String?) -> Unit,
) : PlatformAdaptor {

    private val ensuredChannels = HashSet<String>()

    fun notifyIdFor(internalKey: String): Int = NotifyIdMapper.idFor(internalKey)

    private fun progressStyleAvailable(): Boolean = Build.VERSION.SDK_INT >= BAKLAVA

    /**
     * 统一构建：能力允许且注册了 Progress 模板则走原生 ProgressStyle，否则降级 RemoteViews。
     * 返回 (通知, 实际通道)；null 表示无可用模板或 binder 沙箱拦截了异常。
     */
    fun buildForRequest(request: RenderRequest): Pair<Notification, RenderChannel>? {
        val progressTpl = registry.getProgress(request.bizType, request.templateId)
        if (progressTpl != null && progressStyleAvailable()) {
            val spec = try {
                progressTpl.binder(request.payload)
            } catch (t: Throwable) {
                onError(t, request.internalKey)
                return null
            }
            return buildProgressNotification(spec, progressTpl.smallIconRes, request.channelId) to RenderChannel.PROGRESS_STYLE
        }
        val rv = buildRemoteViewsNotification(request) ?: return null
        return rv to RenderChannel.REMOTE_VIEWS
    }

    /** FGS anchor 复用：构建当前请求对应的通知（不投递）。 */
    fun buildNotification(request: RenderRequest): Notification? = buildForRequest(request)?.first

    private fun buildRemoteViewsNotification(request: RenderRequest): Notification? {
        val template = registry.get(request.bizType, request.templateId) ?: return null
        val views = RemoteViews(context.packageName, template.layoutId)
        // binder 沙箱：业务方回调异常绝不冒泡至宿主，跳过本次渲染、保留上一帧稳定态。
        try {
            template.binder(views, request.payload)
        } catch (t: Throwable) {
            onError(t, request.internalKey)
            return null
        }
        ensureChannel(request.channelId)
        return NotificationCompat.Builder(context, request.channelId)
            .setSmallIcon(smallIconRes)
            .setCustomContentView(views)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .build()
    }

    private fun buildProgressNotification(spec: LiveProgressSpec, smallIcon: Int, channelId: String): Notification {
        ensureChannel(channelId)
        val style = NotificationCompat.ProgressStyle()
            .setProgress(spec.progress)
            .setProgressIndeterminate(spec.indeterminate)
        if (spec.segments.isNotEmpty()) {
            style.setProgressSegments(spec.segments.map { seg ->
                NotificationCompat.ProgressStyle.Segment(seg.length).apply { if (seg.color != 0) setColor(seg.color) }
            })
        }
        if (spec.points.isNotEmpty()) {
            style.setProgressPoints(spec.points.map { pt ->
                NotificationCompat.ProgressStyle.Point(pt.position).apply { if (pt.color != 0) setColor(pt.color) }
            })
        }
        if (spec.trackerIconRes != 0) {
            style.setProgressTrackerIcon(IconCompat.createWithResource(context, spec.trackerIconRes))
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setOngoing(true)
            .setRequestPromotedOngoing(true) // 请求升级为 Live Update 常驻 chip
            .setContentTitle(spec.title)
            .setStyle(style)
        spec.text?.let { builder.setContentText(it) }
        spec.shortCriticalText?.let { builder.setShortCriticalText(it) }
        val largeBitmap = spec.largeIconBitmap
        if (largeBitmap != null) {
            // Bitmap 沙箱：下采样后再提交，防止大图经 Binder 触发 TransactionTooLargeException。
            builder.setLargeIcon(BitmapSandbox.downsample(largeBitmap, maxBitmapBytes, maxBitmapDimenPx))
        } else if (spec.largeIconRes != 0) {
            builder.setLargeIcon(IconCompat.createWithResource(context, spec.largeIconRes).toIcon(context))
        }
        spec.countdownTargetMs?.let {
            builder.setWhen(it).setUsesChronometer(true).setChronometerCountDown(true)
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission") // 权限由引擎在 renderNow 前经 CapabilityProbe 校验
    override fun render(request: RenderRequest): RenderChannel {
        val (notification, channel) = buildForRequest(request) ?: return RenderChannel.NONE
        NotificationManagerCompat.from(context)
            .notify(NotifyIdMapper.idFor(request.internalKey), notification)
        return channel
    }

    override fun dismiss(internalKey: String, immediate: Boolean) {
        NotificationManagerCompat.from(context).cancel(NotifyIdMapper.idFor(internalKey))
    }

    private fun ensureChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!ensuredChannels.add(channelId)) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(channelId) == null) {
            // IMPORTANCE_LOW：常驻不响铃，符合实时活动语义。
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "Live Activities", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private companion object {
        const val BAKLAVA = 36 // Android 16，ProgressStyle / Live Updates 起始版本
    }
}
