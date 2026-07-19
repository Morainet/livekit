/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.model

/** 标准协议动作。 */
enum class Action { START, UPDATE, END }

/** 自动清理策略。 */
data class ClearPolicy(val dismissAfterSeconds: Int = 300)

/**
 * 解析后的标准外壳。[payload] 为业务自定义的字段级 KV。
 * [internalKey] 是防跨业务碰撞的复合键，用作状态机索引与限流分片键。
 */
data class Envelope(
    val protocolVersion: Int,
    val bizType: String,
    val activityId: String,
    val action: Action,
    val templateId: String?,
    val seqId: Long,
    val timestamp: Long,
    val clearPolicy: ClearPolicy? = null,
    val payload: Map<String, Any?> = emptyMap(),
) {
    val internalKey: String get() = "$bizType#$activityId"
}
