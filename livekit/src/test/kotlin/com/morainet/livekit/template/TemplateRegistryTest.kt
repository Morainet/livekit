/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.template

import com.morainet.livekit.internal.template.TemplateRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateRegistryTest {

    @Test
    fun testRegisterAndGet() {
        val reg = TemplateRegistry()
        reg.register("food", "delivery", layoutId = 42) { _, _ -> }
        val tpl = reg.get("food", "delivery")
        assertNotNull(tpl)
        assertEquals(42, tpl!!.layoutId)
    }

    @Test
    fun testMissingReturnsNull() {
        val reg = TemplateRegistry()
        assertNull(reg.get("food", "unknown"))
    }

    @Test
    fun testCompositeKeyIsolation() {
        val reg = TemplateRegistry()
        reg.register("food", "card", layoutId = 1) { _, _ -> }
        reg.register("taxi", "card", layoutId = 2) { _, _ -> }
        // 相同 templateId、不同 bizType 互不覆盖。
        assertEquals(1, reg.get("food", "card")!!.layoutId)
        assertEquals(2, reg.get("taxi", "card")!!.layoutId)
    }
}
