/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.state

import com.morainet.livekit.internal.state.OrphanBuffer
import com.morainet.livekit.model.Action
import com.morainet.livekit.model.Envelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanBufferTest {

    private fun update(seq: Long, key: String = "food#1") = Envelope(
        protocolVersion = 1,
        bizType = key.substringBefore('#'),
        activityId = key.substringAfter('#'),
        action = Action.UPDATE,
        templateId = null,
        seqId = seq,
        timestamp = seq,
    )

    @Test
    fun testDrainSortedBySeq() {
        val buf = OrphanBuffer()
        buf.add(update(3))
        buf.add(update(1))
        buf.add(update(2))
        assertEquals(listOf(1L, 2L, 3L), buf.drain("food#1").map { it.seqId })
    }

    @Test
    fun testDrainClears() {
        val buf = OrphanBuffer()
        buf.add(update(1))
        assertTrue(buf.has("food#1"))
        buf.drain("food#1")
        assertFalse(buf.has("food#1"))
    }

    @Test
    fun testPerKeyIsolation() {
        val buf = OrphanBuffer()
        buf.add(update(1, "food#1"))
        buf.add(update(1, "taxi#1"))
        assertEquals(1, buf.drain("food#1").size)
        assertTrue(buf.has("taxi#1"))
    }

    @Test
    fun testDrainMissingKeyIsEmpty() {
        assertTrue(OrphanBuffer().drain("none#0").isEmpty())
    }
}
