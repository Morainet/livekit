/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morainet.livekit.internal.LiveKitEngine

/**
 * clear_policy 超时移除的目标（白皮书 §9）。
 *
 * 必须是 Manifest 静态声明的 Receiver（exported=false）：动态 Receiver 随进程死亡消失，
 * 而用 AlarmManager 的唯一理由就是要扛住进程被杀——只有静态 Receiver 才能在进程已死时被系统重新拉起执行移除。
 */
internal class LiveKitCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        // 同步清理：Receiver 进程在 onReceive 返回后可能立即被杀，不能走异步。
        LiveKitEngine.handleCleanup(key)
    }

    companion object {
        const val ACTION = "com.morainet.livekit.action.CLEANUP"
        const val EXTRA_KEY = "key"
    }
}
