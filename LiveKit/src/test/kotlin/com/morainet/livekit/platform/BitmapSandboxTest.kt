/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.platform

import com.morainet.livekit.internal.platform.BitmapSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapSandboxTest {

    @Test
    fun testNeedsDownsample() {
        assertTrue(BitmapSandbox.needsDownsample(300 * 1024, 200 * 1024))
        assertFalse(BitmapSandbox.needsDownsample(100 * 1024, 200 * 1024))
        assertFalse(BitmapSandbox.needsDownsample(200 * 1024, 200 * 1024))
    }

    @Test
    fun testSampleSizeWithinBoundsIsOne() {
        assertEquals(1, BitmapSandbox.sampleSize(80, 80, 100, 100))
    }

    @Test
    fun testSampleSizePowerOfTwo() {
        // 400x400 → 100x100 目标：sample=4（400/4=100）
        assertEquals(4, BitmapSandbox.sampleSize(400, 400, 100, 100))
        // 1024x512 → 100x100：需 8（1024/8=128 仍 >? 128>=100 → 继续到 16? 校验语义）
        val s = BitmapSandbox.sampleSize(1024, 512, 100, 100)
        assertTrue(s >= 8)
        assertEquals(0, s and (s - 1)) // 2 的幂
    }

    @Test
    fun testArgb8888Bytes() {
        assertEquals(4L, BitmapSandbox.argb8888Bytes(1, 1))
        // 4000x4000 ARGB = 64MB，远超任何通知预算
        assertEquals(64_000_000L, BitmapSandbox.argb8888Bytes(4000, 4000))
    }

    @Test
    fun testFitDimensionsKeepsAspectRatio() {
        assertEquals(80 to 80, BitmapSandbox.fitDimensions(80, 80, 100, 100))
        // 1000x500 → 落入 100x100：按较小比例 0.1 → 100x50
        assertEquals(100 to 50, BitmapSandbox.fitDimensions(1000, 500, 100, 100))
    }

    @Test
    fun testTargetDimensionsHonorsByteBudget() {
        // 4000x4000，先 fit 到 512x512，再按 200KB 预算继续缩：结果字节数 ≤ 预算
        val (w, h) = BitmapSandbox.targetDimensions(4000, 4000, 200 * 1024, 512)
        assertTrue(w in 1..512 && h in 1..512)
        assertTrue(BitmapSandbox.argb8888Bytes(w, h) <= 200L * 1024)
    }

    @Test
    fun testTargetDimensionsNoShrinkWhenSmall() {
        // 已在预算内：不放大
        assertEquals(64 to 64, BitmapSandbox.targetDimensions(64, 64, 200 * 1024, 512))
    }
}
