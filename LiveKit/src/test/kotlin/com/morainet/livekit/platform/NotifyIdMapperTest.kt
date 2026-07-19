/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.platform

import com.morainet.livekit.internal.platform.NotifyIdMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyIdMapperTest {

    @Test
    fun testDeterministic() {
        assertEquals(NotifyIdMapper.idFor("food#10001"), NotifyIdMapper.idFor("food#10001"))
    }

    @Test
    fun testReservedHighSegment() {
        // 高 16 位固定为 0x4C4B，与宿主自有通知 ID 隔离。
        val id = NotifyIdMapper.idFor("taxi#42")
        assertEquals(0x4C4B0000.toInt(), id and 0xFFFF0000.toInt())
    }

    @Test
    fun testDistinctKeysUsuallyDiffer() {
        assertNotEquals(NotifyIdMapper.idFor("food#1"), NotifyIdMapper.idFor("taxi#1"))
        assertNotEquals(NotifyIdMapper.idFor("food#1"), NotifyIdMapper.idFor("food#2"))
    }

    @Test
    fun testAntiCollisionCompositeKey() {
        // 相同 activityId、不同 bizType 不得碰撞（复合键隔离）。
        assertNotEquals(NotifyIdMapper.idFor("food#10001"), NotifyIdMapper.idFor("taxi#10001"))
    }

    @Test
    fun testAlwaysPositive() {
        assertTrue(NotifyIdMapper.idFor("any#key") > 0)
    }
}
