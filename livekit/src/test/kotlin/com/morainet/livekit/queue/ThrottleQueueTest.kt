/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.queue

import com.morainet.livekit.internal.queue.ThrottleQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleQueueTest {

    @Test
    fun testLeadingTrailingEdge() = runTest {
        val emissions = mutableListOf<Pair<Long, Map<String, Any?>>>()
        val q = ThrottleQueue(this, windowMs = 1000L) { _, p ->
            emissions.add(testScheduler.currentTime to p)
        }

        q.submit("k", mapOf("d" to 100)) // t=0 Leading，立即发
        advanceTimeBy(200); runCurrent()
        q.submit("k", mapOf("d" to 80))  // 合并，不发
        advanceTimeBy(300); runCurrent() // t=500
        q.submit("k", mapOf("d" to 50))  // 合并，不发
        advanceUntilIdle()               // 越过 t=1000，Trailing 发一次

        assertEquals(2, emissions.size)
        assertEquals(0L, emissions[0].first)
        assertEquals(100, emissions[0].second["d"])
        assertEquals(1000L, emissions[1].first)
        assertEquals(50, emissions[1].second["d"])
    }

    @Test
    fun testNoTrailingWhenSingleFrame() = runTest {
        val emissions = mutableListOf<Map<String, Any?>>()
        val q = ThrottleQueue(this, windowMs = 1000L) { _, p -> emissions.add(p) }
        q.submit("k", mapOf("d" to 1)) // 仅一帧
        advanceUntilIdle()
        assertEquals(1, emissions.size) // 无脏合并 → 不发尾帧
    }

    @Test
    fun testEndPriority() = runTest {
        val emissions = mutableListOf<Pair<Long, Map<String, Any?>>>()
        val q = ThrottleQueue(this, windowMs = 1000L) { _, p ->
            emissions.add(testScheduler.currentTime to p)
        }
        q.submit("k", mapOf("s" to "run")) // t=0 Leading
        advanceTimeBy(300); runCurrent()
        q.submitEnd("k", mapOf("s" to "done")) // t=300 立即 flush，无视剩余节流
        advanceUntilIdle()

        assertEquals(2, emissions.size)
        assertEquals(300L, emissions[1].first)
        assertEquals("done", emissions[1].second["s"])
    }

    @Test
    fun testPerKeyIsolation() = runTest {
        val emissions = mutableListOf<String>()
        val q = ThrottleQueue(this, windowMs = 1000L) { key, _ -> emissions.add(key) }
        q.submit("a", mapOf("x" to 1)) // Leading a
        q.submit("b", mapOf("x" to 1)) // Leading b
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), emissions) // 两键各自独立成窗
    }
}
