/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.demo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.firebase.messaging.FirebaseMessaging
import com.morainet.livekit.LiveKit

class MainActivity : ComponentActivity() {

    private val biz = "food"
    private val actId = "10001"
    private val main = Handler(Looper.getMainLooper())
    private var fcmToken: String? = null

    // Android 13+（API 33+）必须运行时申请 POST_NOTIFICATIONS。
    // 授权后调 refreshCapabilities()：SDK 重新探测能力并自动补渲染被 PermissionMissing 拦下的活动。
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) LiveKit.refreshCapabilities()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeInsets()
        ensureNotificationPermission()
        findViewById<Button>(R.id.btn_order).setOnClickListener { doOrder() }
        findViewById<Button>(R.id.btn_start).setOnClickListener { doStart() }
        findViewById<Button>(R.id.btn_update).setOnClickListener { doUpdate() }
        findViewById<Button>(R.id.btn_end).setOnClickListener { doEnd() }
        findViewById<Button>(R.id.btn_huge).setOnClickListener { doHugeImage() }
        findViewById<Button>(R.id.btn_copy_token).setOnClickListener { copyFcmToken() }
        loadFcmToken()
    }

    /** 异步获取 FCM token 展示，供用脚本/控制台向其推送测试消息。 */
    private fun loadFcmToken() {
        val tokenView = findViewById<TextView>(R.id.fcm_token)
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                fcmToken = task.result
                tokenView.text = fcmToken
            } else {
                tokenView.text = getString(R.string.fcm_token_failed)
            }
        }
    }

    private fun copyFcmToken() {
        val token = fcmToken ?: return
        val clip = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("fcm_token", token))
        Toast.makeText(this, "Token 已复制", Toast.LENGTH_SHORT).show()
    }

    /** Android 13+ 首次进入弹窗申请通知权限；已授予或低版本直接跳过。 */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** targetSdk 35+ 默认全屏沉浸：把系统栏 inset 作为根视图 padding，避免内容顶到状态栏 / 导航栏下面。 */
    private fun applyEdgeToEdgeInsets() {
        // 浅色背景 → 状态栏图标用深色
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    // 旧的 RemoteViews 卡片流。
    private fun doStart() = LiveKit.start(
        biz, actId, "delivery",
        mapOf("title" to "骑手已接单", "subtitle" to "预计 30 分钟送达", "status" to "距你 3.2 km"),
    )

    private fun doUpdate() = LiveKit.update(
        biz, actId,
        mapOf("subtitle" to "预计 12 分钟送达", "status" to "距你 1.1 km"),
    )

    private fun doEnd() = LiveKit.end(biz, actId, immediate = true)

    // Bitmap 沙箱：塞 4000×4000 巨图，SDK 下采样后安全上通知（不崩即通过）。
    private fun doHugeImage() = LiveKit.start(
        biz, "imgtest", "delivery",
        mapOf("title" to "巨图沙箱测试", "subtitle" to "4000×4000 ≈ 64MB → 安全下采样", "status" to "未崩溃即通过", "huge_image" to true),
    )

    // Android 16+ 原生 ProgressStyle 外卖流：一键推进 下单→备餐→出发→即将到达→完成。
    private fun doOrder() {
        val id = "order1"
        val tpl = "delivery-live"
        LiveKit.start(biz, id, tpl, mapOf("stage" to "init", "progress" to 0))
        main.postDelayed({ LiveKit.update(biz, id, mapOf("stage" to "prep", "progress" to 25)) }, 3000)
        main.postDelayed({ LiveKit.update(biz, id, mapOf("stage" to "enroute", "progress" to 50)) }, 6000)
        main.postDelayed({ LiveKit.update(biz, id, mapOf("stage" to "arriving", "progress" to 75)) }, 9000)
        main.postDelayed({ LiveKit.update(biz, id, mapOf("stage" to "complete", "progress" to 100)) }, 12000)
    }
}
