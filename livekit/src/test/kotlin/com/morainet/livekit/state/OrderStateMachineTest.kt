/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.state

import com.morainet.livekit.internal.state.Decision
import com.morainet.livekit.internal.state.InMemoryStateStore
import com.morainet.livekit.internal.state.OrderStateMachine
import com.morainet.livekit.model.Action
import com.morainet.livekit.model.DropReason
import com.morainet.livekit.model.Envelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderStateMachineTest {

    private fun sm() = OrderStateMachine(InMemoryStateStore())

    private fun env(
        action: Action,
        seq: Long,
        ts: Long = seq,
        templateId: String? = if (action == Action.START) "t" else null,
        payload: Map<String, Any?> = emptyMap(),
        key: String = "biz#1",
    ) = Envelope(
        protocolVersion = 1,
        bizType = key.substringBefore('#'),
        activityId = key.substringAfter('#'),
        action = action,
        templateId = templateId,
        seqId = seq,
        timestamp = ts,
        payload = payload,
    )

    @Test
    fun testOutOfOrderDrop() {
        val m = sm()
        assertTrue(m.process(env(Action.START, seq = 10)) is Decision.Accepted)
        val d = m.process(env(Action.UPDATE, seq = 9))
        assertTrue(d is Decision.Dropped)
        assertEquals(DropReason.OUT_OF_ORDER, (d as Decision.Dropped).reason)
    }

    @Test
    fun testEqualSeqTimestampTiebreak() {
        val m = sm()
        m.process(env(Action.START, seq = 5, ts = 100))
        // 相等 seq、更小 timestamp → 丢弃
        assertTrue(m.process(env(Action.UPDATE, seq = 5, ts = 90)) is Decision.Dropped)
        // 相等 seq、更大 timestamp → 接受
        assertTrue(m.process(env(Action.UPDATE, seq = 5, ts = 110)) is Decision.Accepted)
    }

    @Test
    fun testIncrementalMerge() {
        val m = sm()
        m.process(env(Action.START, seq = 1, payload = mapOf("a" to 1, "b" to 2)))
        val d = m.process(env(Action.UPDATE, seq = 2, payload = mapOf("b" to 3, "c" to 4)))
        d as Decision.Accepted
        assertEquals(mapOf("a" to 1, "b" to 3, "c" to 4), d.state.payload)
    }

    @Test
    fun testStaleEndDropped() {
        val m = sm()
        m.process(env(Action.START, seq = 1))
        m.process(env(Action.UPDATE, seq = 5))
        // 迟到的 END（seq=3 < 5）不得误杀已推进的活动
        val d = m.process(env(Action.END, seq = 3))
        assertTrue(d is Decision.Dropped)
        assertEquals(DropReason.OUT_OF_ORDER, (d as Decision.Dropped).reason)
    }

    @Test
    fun testOrphanUpdateDropped() {
        val m = sm()
        // 无本地态、无 template_id 的 UPDATE 属孤儿，即时丢弃
        val d = m.process(env(Action.UPDATE, seq = 1, templateId = null))
        assertTrue(d is Decision.Dropped)
        assertEquals(DropReason.ORPHAN, (d as Decision.Dropped).reason)
    }

    @Test
    fun testImplicitStartViaTemplateId() {
        val m = sm()
        // UPDATE 携带 template_id → 隐式物化，不算孤儿
        val d = m.process(env(Action.UPDATE, seq = 1, templateId = "t"))
        assertTrue(d is Decision.Accepted)
    }
}
