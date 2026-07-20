/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.platform

import com.morainet.livekit.internal.platform.CountdownMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownMathTest {

    @Test
    fun testBaseIsElapsedPlusRemaining() {
        // 目标在 now 之后 60s；base = elapsed + 60_000
        val base = CountdownMath.countdownBase(targetEpochMs = 1_060_000, nowEpochMs = 1_000_000, nowElapsedMs = 500_000)
        assertEquals(560_000L, base)
    }

    @Test
    fun testNegativeGuardReturnsNull() {
        // 已过期 → null（负数防御，避免 Chronometer 显示负数）
        assertNull(CountdownMath.countdownBase(targetEpochMs = 900_000, nowEpochMs = 1_000_000, nowElapsedMs = 500_000))
        assertNull(CountdownMath.countdownBase(targetEpochMs = 1_000_000, nowEpochMs = 1_000_000, nowElapsedMs = 500_000))
    }

    @Test
    fun testRomBlacklist() {
        assertTrue(CountdownMath.isBlacklistedRom("Xiaomi", setOf("xiaomi", "huawei")))
        assertFalse(CountdownMath.isBlacklistedRom("Google", setOf("xiaomi", "huawei")))
        assertFalse(CountdownMath.isBlacklistedRom("Google", emptySet()))
    }
}
