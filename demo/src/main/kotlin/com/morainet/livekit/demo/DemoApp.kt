/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.demo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.util.Log
import android.view.View
import com.morainet.livekit.LiveKit
import com.morainet.livekit.LiveKitConfig
import com.morainet.livekit.LiveKitCountdown
import com.morainet.livekit.model.LiveKitEvent
import com.morainet.livekit.model.LiveKitObserver
import com.morainet.livekit.model.LiveAction
import com.morainet.livekit.model.LiveProgressSpec
import com.morainet.livekit.store.MmkvLiveKitStore
import com.tencent.mmkv.MMKV

/**
 * 在 Application 中初始化，保证 :main 被 :push 通过 ContentProvider 拉起时（无 Activity）
 * 引擎与观察者也已就绪。init 在每个进程都会执行，SDK 内部按进程分角色。
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 每个进程（:main / :push）都要初始化 MMKV，供多进程共享存储。
        val root = MMKV.initialize(this)
        Log.i(TAG, "Application.onCreate pid=${Process.myPid()} mmkvRoot=$root")

        LiveKit.init(
            this,
            LiveKitConfig(
                store = MmkvLiveKitStore(this),
                defaultChannelId = "livekit_demo",
                defaultThrottleWindowMs = 1000L,
                observer = object : LiveKitObserver {
                    override fun onEvent(event: LiveKitEvent) {
                        Log.i(TAG, "event=$event pid=${Process.myPid()}")
                    }

                    override fun onError(throwable: Throwable, key: String?) {
                        Log.e(TAG, "error key=$key", throwable)
                    }
                },
            ),
        )

        LiveKit.registerTemplate("food", "delivery", R.layout.livekit_demo_card) { views, payload ->
            views.setTextViewText(R.id.title, payload["title"] as? String ?: "")
            views.setTextViewText(R.id.subtitle, payload["subtitle"] as? String ?: "")
            views.setTextViewText(R.id.status, payload["status"] as? String ?: "")
            val target = (payload["countdown_target"] as? Number)?.toLong()
            if (target != null) {
                views.setViewVisibility(R.id.timer, View.VISIBLE)
                LiveKitCountdown.bind(views, R.id.timer, target)
            } else {
                views.setViewVisibility(R.id.timer, View.GONE)
            }
            // Bitmap 沙箱演示：塞一张 4000×4000（≈64MB）巨图，交给 SDK 安全下采样后再上通知。
            if (payload["huge_image"] == true) {
                val huge = Bitmap.createBitmap(4000, 4000, Bitmap.Config.ARGB_8888)
                huge.eraseColor(Color.rgb(255, 90, 95))
                Log.i(TAG, "huge bitmap ${huge.width}x${huge.height} ≈ ${huge.allocationByteCount / 1024}KB → setImageBitmapSafe")
                LiveKit.setImageBitmapSafe(views, R.id.image, huge)
                views.setViewVisibility(R.id.image, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.image, View.GONE)
            }
        }

        // Android 16+ 原生 ProgressStyle 外卖 Live Update（参考 platform-samples/live-updates）。
        LiveKit.registerProgressTemplate("food", "delivery-live", R.drawable.small_icon) { payload ->
            val stage = payload["stage"] as? String ?: "init"
            val progress = (payload["progress"] as? Number)?.toInt() ?: 0
            val pointColor = Color.rgb(236, 183, 255)
            val segColor = Color.rgb(134, 247, 250)
            val segments = List(4) { LiveProgressSpec.Segment(25, segColor) }
            val points = listOf(25, 50, 75, 100).map { LiveProgressSpec.Point(it, pointColor) }
            val cupcake = R.drawable.cupcake
            when (stage) {
                "init" -> LiveProgressSpec(
                    title = "正在下单", text = "正在与商家确认…", shortCriticalText = "下单中",
                    indeterminate = true, segments = segments, points = points,
                )
                "prep" -> LiveProgressSpec(
                    title = "商家备餐中", text = "下一步：出餐", shortCriticalText = "备餐",
                    progress = progress, segments = segments, points = points, largeIconRes = cupcake,
                )
                "enroute" -> LiveProgressSpec(
                    title = "订单已出发", text = "骑手正在赶来", shortCriticalText = "配送中",
                    progress = progress, segments = segments, points = points,
                    trackerIconRes = R.drawable.shopping_bag, largeIconRes = cupcake,
                    countdownTargetMs = System.currentTimeMillis() + 10 * 60 * 1000L,
                )
                "arriving" -> LiveProgressSpec(
                    title = "订单即将送达", text = "骑手已在楼下", shortCriticalText = "即将到达",
                    progress = progress, segments = segments, points = points,
                    trackerIconRes = R.drawable.delivery_truck, largeIconRes = cupcake,
                    countdownTargetMs = System.currentTimeMillis() + 2 * 60 * 1000L,
                    // 交互按钮示例：骑手到楼下时给出「联系骑手」「去开门」。
                    actions = listOf(
                        LiveAction(id = "call_rider", label = "联系骑手"),
                        LiveAction(id = "open_door", label = "去开门"),
                    ),
                )
                else -> LiveProgressSpec(
                    title = "订单已完成", text = "感谢使用，请及时取餐", shortCriticalText = "已送达",
                    progress = 100, segments = segments, points = points,
                    trackerIconRes = R.drawable.check_circle, largeIconRes = cupcake,
                )
            }
        }
    }

    companion object {
        const val TAG = "LiveKitDemo"
    }
}
