/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.morainet.livekit.model.RenderChannel

/** 一次能力探测的快照（白皮书 §6）。 */
data class Capabilities(
    val sdkInt: Int,
    val progressStyleAvailable: Boolean,
    val notificationsEnabled: Boolean,
)

object CapabilityProbe {

    /** 纯决策：据能力快照选出首选渲染通道，可直接单测。 */
    fun preferredChannel(caps: Capabilities): RenderChannel = when {
        !caps.notificationsEnabled -> RenderChannel.NONE
        caps.progressStyleAvailable && caps.sdkInt >= 36 -> RenderChannel.PROGRESS_STYLE
        else -> RenderChannel.REMOTE_VIEWS
    }

    /** 运行期探测（触碰 Android API）。 */
    fun probe(context: Context): Capabilities = Capabilities(
        sdkInt = Build.VERSION.SDK_INT,
        progressStyleAvailable = probeProgressStyle(),
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    /** 反射探测 Android 16+ 的 ProgressStyle，低版本不会抛 NoClassDefFoundError。 */
    private fun probeProgressStyle(): Boolean = try {
        Class.forName("android.app.Notification\$ProgressStyle")
        true
    } catch (_: Throwable) {
        false
    }
}
