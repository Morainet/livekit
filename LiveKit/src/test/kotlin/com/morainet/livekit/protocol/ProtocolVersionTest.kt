/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.protocol

import com.morainet.livekit.internal.ProtocolVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolVersionTest {

    @Test
    fun testCurrentVersionSupported() {
        assertTrue(ProtocolVersion.isSupported(ProtocolVersion.SUPPORTED))
        assertTrue(ProtocolVersion.isSupported(1))
        assertFalse(ProtocolVersion.isFuture(1))
    }

    @Test
    fun testFutureVersionDetected() {
        assertTrue(ProtocolVersion.isFuture(ProtocolVersion.SUPPORTED + 1))
        assertTrue(ProtocolVersion.isFuture(99))
        assertFalse(ProtocolVersion.isSupported(2))
    }

    @Test
    fun testInvalidVersionNotSupportedNorFuture() {
        // 0 / 负数：非已知支持，也不算未来版本（按最低容错处理，不当作 future 上报）
        assertFalse(ProtocolVersion.isSupported(0))
        assertFalse(ProtocolVersion.isFuture(0))
        assertFalse(ProtocolVersion.isSupported(-3))
        assertFalse(ProtocolVersion.isFuture(-3))
    }

    @Test
    fun testSupportedConstantIsOne() {
        assertEquals(1, ProtocolVersion.SUPPORTED)
    }
}
