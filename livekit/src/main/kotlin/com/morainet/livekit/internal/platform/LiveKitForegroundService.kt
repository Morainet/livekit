/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.morainet.livekit.internal.LiveKitEngine

/**
 * 保活前台服务（白皮书 §7）。锚定当前 anchor 活动的通知作为前台通知，
 * 使宿主进程免于被系统回收。startForeground 的 BFGS / 类型受限异常由引擎接管降级。
 */
internal class LiveKitForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfDetached()
            return START_NOT_STICKY
        }

        val anchor = LiveKitEngine.anchorInfo()
        if (anchor == null) {
            stopSelfDetached()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(this, anchor.first, anchor.second, LiveKitEngine.fgsType())
        } catch (t: Throwable) {
            // BFGS 后台启动受限 / FGS 类型不满足：交回引擎降级并置待提权标记。
            LiveKitEngine.onForegroundStartFailed(t)
            stopSelf()
        }
        return START_STICKY
    }

    private fun stopSelfDetached() {
        // 保留终态通知（DETACH），仅解除前台绑定。
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.morainet.livekit.action.STOP_FGS"
    }
}
