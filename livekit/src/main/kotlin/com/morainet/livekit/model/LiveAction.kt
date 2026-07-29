/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.model

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes

/**
 * 卡片交互按钮（白皮书 §6 增强）。
 *
 * 业务方在模板 binder 里据 payload 动态产出 [LiveAction] 列表，SDK 负责把它挂到通知上：
 * - ProgressStyle 通道：`Notification.Action` 系统按钮；
 * - RemoteViews 通道：若 [viewId] 非零，SDK 自动 `setOnClickPendingIntent` 到该控件。
 *
 * 点击后 SDK 经 [LiveKitEvent.ActionClicked] 回调宿主，无需宿主自行处理 PendingIntent。
 * [id] 是业务标识（如 `"call_rider"`），用于在回调里区分点了哪个按钮。
 *
 * @param viewId 仅 RemoteViews 通道用：要挂点击的控件 id；ProgressStyle 通道忽略。
 */
data class LiveAction(
    val id: String,
    val label: CharSequence,
    @param:DrawableRes val iconRes: Int = 0,
    @param:IdRes val viewId: Int = 0,
)
