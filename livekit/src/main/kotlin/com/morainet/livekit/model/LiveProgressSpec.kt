/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.model

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * Android 16+ 原生 Live Updates（ProgressStyle）的数据规格（白皮书 §6）。
 * 业务方在 registerProgressTemplate 的 binder 内据 payload 产出，SDK 据此构建系统级进度通知。
 */
data class LiveProgressSpec(
    val title: CharSequence,
    val text: CharSequence? = null,
    /** 状态栏 Live Chip 的短文案（如 "Prepping" / "已出发"）。 */
    val shortCriticalText: String? = null,
    /** 0..100 当前进度。 */
    val progress: Int = 0,
    /** 不确定进度（如"下单中"），忽略 [progress]。 */
    val indeterminate: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val points: List<Point> = emptyList(),
    /** 沿进度条移动的 tracker 图标（骑手/包裹/对勾）。 */
    @param:DrawableRes val trackerIconRes: Int = 0,
    /** 大图标（商品缩略图，资源）。 */
    @param:DrawableRes val largeIconRes: Int = 0,
    /** 大图标（运行时 Bitmap，如网络头像）。非空时优先，并经 Bitmap 沙箱下采样后再提交。 */
    val largeIconBitmap: Bitmap? = null,
    /** 若非空，绑定系统零功耗倒计时至该绝对时间戳（毫秒）。 */
    val countdownTargetMs: Long? = null,
    /** 卡片交互按钮；SDK 挂为 Notification.Action，点击经 ActionClicked 回调宿主。 */
    val actions: List<LiveAction> = emptyList(),
) {
    data class Segment(val length: Int, @param:ColorInt val color: Int = 0)
    data class Point(val position: Int, @param:ColorInt val color: Int = 0)
}
