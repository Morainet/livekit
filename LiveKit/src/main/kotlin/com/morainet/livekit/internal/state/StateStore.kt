/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.state

import com.morainet.livekit.model.Action
import java.util.concurrent.ConcurrentHashMap

/**
 * 某活动当前被接受的权威态。[seqId]/[timestamp] 是乱序审计基准，
 * 必须随状态一并持久化，否则进程重启后防乱序失效（见白皮书 §4.3）。
 */
data class ActivityState(
    val seqId: Long,
    val timestamp: Long,
    val templateId: String?,
    val payload: Map<String, Any?>,
    val lastAction: Action,
    val ended: Boolean,
    val dismissAfterSeconds: Int? = null,
)

/**
 * 状态机的读写抽象。运行期实现（[InMemoryStateStore]）用于单测；
 * 生产实现由 ILiveKitStore 适配层做跨进程持久化（MMKV / ContentProvider）。
 */
interface StateStore {
    fun get(key: String): ActivityState?
    fun put(key: String, state: ActivityState)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** 进程内线程安全实现，用于单测与非持久场景。 */
class InMemoryStateStore : StateStore {
    private val map = ConcurrentHashMap<String, ActivityState>()
    override fun get(key: String): ActivityState? = map[key]
    override fun put(key: String, state: ActivityState) { map[key] = state }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}
