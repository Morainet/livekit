/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.state

import com.morainet.livekit.model.Action
import com.morainet.livekit.model.DropReason
import com.morainet.livekit.model.Envelope

/** 状态机对单个 Envelope 的裁决结果。 */
sealed interface Decision {
    data class Accepted(val key: String, val state: ActivityState, val action: Action) : Decision
    data class Dropped(val key: String, val reason: DropReason) : Decision
}

/**
 * 防乱序状态机（白皮书 §4.3）。
 *
 * 职责：复合键索引、seq/timestamp 双重乱序审计、字段级增量 merge、状态落盘。
 * 不涉及任何 Android API，可在 JVM 上直接单测。
 *
 * 注：孤儿 UPDATE 的「短暂缓冲」是引擎层（带时钟）的职责，本层仅给出即时裁决。
 */
class OrderStateMachine(private val store: StateStore) {

    fun process(env: Envelope): Decision {
        val key = env.internalKey
        val local = store.get(key)

        // 双重乱序审计：seq 优先，seq 相等时以 timestamp 兜底。迟到的 END 同样受此规则约束。
        if (local != null && !isNewer(env, local)) {
            return Decision.Dropped(key, DropReason.OUT_OF_ORDER)
        }

        // 孤儿 UPDATE：无本地态且不携带 template_id，无法物化，即时丢弃（缓冲留待引擎层）。
        if (local == null && env.action == Action.UPDATE && env.templateId == null) {
            return Decision.Dropped(key, DropReason.ORPHAN)
        }

        // 字段级增量 merge：后到覆盖先到，而非整包替换。
        val mergedPayload = if (local == null) env.payload else local.payload + env.payload

        val newState = ActivityState(
            seqId = env.seqId,
            timestamp = env.timestamp,
            templateId = env.templateId ?: local?.templateId,
            payload = mergedPayload,
            lastAction = env.action,
            ended = env.action == Action.END,
            dismissAfterSeconds = env.clearPolicy?.dismissAfterSeconds ?: local?.dismissAfterSeconds,
        )
        store.put(key, newState)
        return Decision.Accepted(key, newState, env.action)
    }

    private fun isNewer(env: Envelope, local: ActivityState): Boolean =
        env.seqId > local.seqId ||
            (env.seqId == local.seqId && env.timestamp > local.timestamp)
}
