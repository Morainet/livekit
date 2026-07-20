/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.lifecycle

import com.morainet.livekit.internal.lifecycle.ForegroundCounter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundCounterTest {

    @Test
    fun testBackgroundToForegroundTransition() {
        val c = ForegroundCounter()
        assertFalse(c.isAppInForeground)
        assertTrue(c.onStart()) // 0 → 1 跨入前台
        assertTrue(c.isAppInForeground)
    }

    @Test
    fun testSecondActivityDoesNotRetrigger() {
        val c = ForegroundCounter()
        c.onStart()
        assertFalse(c.onStart()) // 1 → 2 不算跨越
    }

    @Test
    fun testForegroundToBackgroundTransition() {
        val c = ForegroundCounter()
        c.onStart()
        assertTrue(c.onStop()) // 1 → 0 跨入后台
        assertFalse(c.isAppInForeground)
    }

    @Test
    fun testRotationStyleOverlapStaysForeground() {
        // Activity 重建：新 onStart 先于旧 onStop → 计数不回 0，不误判后台。
        val c = ForegroundCounter()
        c.onStart()          // A 启动 → 1
        assertFalse(c.onStart()) // B 启动 → 2
        assertFalse(c.onStop())  // A 停止 → 1，仍前台
        assertTrue(c.isAppInForeground)
    }

    @Test
    fun testUnderflowGuard() {
        val c = ForegroundCounter()
        c.onStop() // 不应变负
        assertFalse(c.isAppInForeground)
    }
}
