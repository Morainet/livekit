/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.model

/** 丢弃原因，用于可观测性上报。 */
enum class DropReason { OUT_OF_ORDER, ORPHAN, MALFORMED, STORE_BUSY }

/** 渲染通道，供降级追踪。 */
enum class RenderChannel { PROGRESS_STYLE, REMOTE_VIEWS, PLAIN, NONE }

/** SDK 对外统一事件流。 */
sealed interface LiveKitEvent {
    data class Rendered(val key: String, val channel: RenderChannel) : LiveKitEvent
    data class Dropped(val key: String, val reason: DropReason) : LiveKitEvent
    data class Degraded(val key: String, val from: RenderChannel, val to: RenderChannel) : LiveKitEvent
    data class PermissionMissing(val key: String) : LiveKitEvent
    data class Throttled(val key: String, val mergedCount: Int) : LiveKitEvent
    /** 收到高于 SDK 支持上限的协议版本：已尽力解析已知字段并照常处理（白皮书 §10.1）。 */
    data class UnsupportedVersion(val version: Int, val key: String) : LiveKitEvent
}

/** 全局观测 / 异常监听器。 */
interface LiveKitObserver {
    fun onEvent(event: LiveKitEvent) {}
    fun onError(throwable: Throwable, key: String?) {}
}
