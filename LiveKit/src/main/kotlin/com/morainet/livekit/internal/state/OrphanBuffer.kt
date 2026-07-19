/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.state

import com.morainet.livekit.model.Envelope

/**
 * 孤儿 UPDATE 短暂缓冲（白皮书 §4.3）的纯结构：按复合键暂存先于 START 到达的更新，
 * 待 START 落地后按 seq 升序重放。计时由引擎层驱动。
 */
internal class OrphanBuffer {
    private val map = HashMap<String, MutableList<Envelope>>()

    fun add(env: Envelope) {
        map.getOrPut(env.internalKey) { ArrayList() }.add(env)
    }

    fun has(key: String): Boolean = map.containsKey(key)

    /** 取出并清空某键的缓冲，按 seq 升序返回，保证重放顺序正确。 */
    fun drain(key: String): List<Envelope> =
        (map.remove(key) ?: emptyList()).sortedBy { it.seqId }

    fun clear(key: String) {
        map.remove(key)
    }
}
