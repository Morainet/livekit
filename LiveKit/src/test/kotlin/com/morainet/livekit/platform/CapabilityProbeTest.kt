/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.platform

import com.morainet.livekit.internal.platform.Capabilities
import com.morainet.livekit.internal.platform.CapabilityProbe
import com.morainet.livekit.model.RenderChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityProbeTest {

    @Test
    fun testNoPermissionYieldsNone() {
        val caps = Capabilities(sdkInt = 36, progressStyleAvailable = true, notificationsEnabled = false)
        assertEquals(RenderChannel.NONE, CapabilityProbe.preferredChannel(caps))
    }

    @Test
    fun testProgressStyleWhenSupported() {
        val caps = Capabilities(sdkInt = 36, progressStyleAvailable = true, notificationsEnabled = true)
        assertEquals(RenderChannel.PROGRESS_STYLE, CapabilityProbe.preferredChannel(caps))
    }

    @Test
    fun testLegacyWhenProgressStyleAbsent() {
        val caps = Capabilities(sdkInt = 36, progressStyleAvailable = false, notificationsEnabled = true)
        assertEquals(RenderChannel.REMOTE_VIEWS, CapabilityProbe.preferredChannel(caps))
    }

    @Test
    fun testLegacyOnOlderSdk() {
        val caps = Capabilities(sdkInt = 34, progressStyleAvailable = true, notificationsEnabled = true)
        assertEquals(RenderChannel.REMOTE_VIEWS, CapabilityProbe.preferredChannel(caps))
    }
}
