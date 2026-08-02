/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.demo

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.morainet.livekit.LiveKit

/**
 * FCM 推送入口：收到推送后把标准 LiveKit JSON 外壳交给 SDK（白皮书 §3.5 通道无关）。
 *
 * 约定 payload 形如（data 消息，非 notification 消息，否则 app 在前台不会回调 onMessageReceived）：
 * ```
 * {
 *   "protocol_version": 1, "biz_type": "food", "activity_id": "10001",
 *   "action": "UPDATE", "template_id": "delivery-live",
 *   "seq_id": 42, "timestamp": 1737000000000,
 *   "payload": { "stage": "enroute", "progress": 50 }
 * }
 * ```
 * 可整体放在 data 的 "livekit" 键下，也可每条字段平铺在 data 里（本 demo 取前者，边界清晰）。
 */
class LiveKitMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val json = message.data["livekit"]
        if (json != null) {
            Log.i("LiveKitDemo", "FCM onMessageReceived: ${message.messageId} → dispatchRawJson")
            LiveKit.dispatchRawJson(json)
        } else {
            Log.w("LiveKitDemo", "FCM 收到无 livekit 字段的消息，忽略：${message.data}")
        }
    }

    override fun onNewToken(token: String) {
        Log.i("LiveKitDemo", "FCM onNewToken: $token")
        // 实际业务应上报到自己的后端用于定向推送；demo 仅记录，供 UI 展示。
    }
}
