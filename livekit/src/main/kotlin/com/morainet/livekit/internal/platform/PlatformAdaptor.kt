/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

import com.morainet.livekit.model.RenderChannel

/** 一次渲染请求。payload 为字段级最终态。 */
data class RenderRequest(
    val internalKey: String,
    val bizType: String,
    val templateId: String,
    val payload: Map<String, Any?>,
    val channelId: String,
)

/** 平台渲染抽象：真正触碰系统通知栈的唯一出口。 */
interface PlatformAdaptor {
    /** 渲染一次，返回实际落地的渲染通道（用于降级追踪）。 */
    fun render(request: RenderRequest): RenderChannel

    /** 移除某活动的通知。 */
    fun dismiss(internalKey: String, immediate: Boolean)
}
