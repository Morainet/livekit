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
 * 卡片交互按钮的点击入口（白皮书 §6 增强）。
 *
 * 与 [LiveKitCleanupReceiver] 同为 Manifest 静态声明的 Receiver（exported=false）：
 * 通知按钮的 PendingIntent 由系统在任意进程（含 :push）触发，静态 Receiver 保证进程被杀后
 * 也能被重新拉起接收。onReceive 内同步委托给引擎，由引擎裁决回 :main emit。
 */
internal class LiveKitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val actionId = intent.getStringExtra(EXTRA_ACTION) ?: return
        // 同步转发：Receiver 进程返回后可能立即被杀，不能走异步。
        LiveKitEngine.handleAction(key, actionId)
    }

    companion object {
        const val ACTION = "com.morainet.livekit.action.ACTION_CLICK"
        const val EXTRA_KEY = "key"
        const val EXTRA_ACTION = "action"
    }
}
