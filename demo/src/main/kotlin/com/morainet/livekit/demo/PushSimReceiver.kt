/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.morainet.livekit.LiveKit

/**
 * 模拟厂商推送落在独立 :push 进程：收到广播后把标准 JSON 交给 SDK。
 * SDK 在 :push 只落盘 + 触发跨进程 notifyChange，真正渲染发生在 :main。
 */
class PushSimReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("json") ?: return
        Log.i("LiveKitDemo", "PushSimReceiver onReceive pid=${Process.myPid()} (:push)")
        LiveKit.dispatchRawJson(json)
    }
}
