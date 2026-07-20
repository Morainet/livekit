/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Binder 事务沙箱（白皮书 §11）。
 *
 * RemoteViews / 大图标经 Binder 传给 SystemServer 渲染，受 1MB 事务上限约束；
 * 过大 Bitmap 需前置下采样，避免 TransactionTooLargeException 强杀宿主。
 * 纯数学部分平台无关、可单测；[downsample] 做实际缩放。
 */
object BitmapSandbox {

    /** 字节数是否超阈值，需要下采样。 */
    fun needsDownsample(byteCount: Int, maxBytes: Int): Boolean = byteCount > maxBytes

    /**
     * 计算 2 的幂 inSampleSize（解码期用，语义同 BitmapFactory.Options.inSampleSize）。
     */
    fun sampleSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Int {
        if (srcW <= maxW && srcH <= maxH) return 1
        var sample = 1
        val halfW = srcW / 2
        val halfH = srcH / 2
        while (halfW / sample >= maxW || halfH / sample >= maxH) {
            sample *= 2
        }
        return sample
    }

    /** ARGB_8888 下的字节估算（每像素 4B）。 */
    fun argb8888Bytes(width: Int, height: Int): Long = width.toLong() * height.toLong() * 4L

    /** 等比缩放使尺寸落入 maxW×maxH，返回 (w, h)。 */
    fun fitDimensions(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (srcW <= maxW && srcH <= maxH) return srcW to srcH
        val scale = min(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
        return max(1, (srcW * scale).toInt()) to max(1, (srcH * scale).toInt())
    }

    /** 综合「尺寸上限」与「字节预算」求目标 (w, h)：先 fit 尺寸，再按预算等比再缩。 */
    fun targetDimensions(srcW: Int, srcH: Int, maxBytes: Int, maxDimen: Int): Pair<Int, Int> {
        var (w, h) = fitDimensions(srcW, srcH, maxDimen, maxDimen)
        val bytes = argb8888Bytes(w, h)
        if (bytes > maxBytes) {
            val s = sqrt(maxBytes.toDouble() / bytes)
            w = max(1, (w * s).toInt())
            h = max(1, (h * s).toInt())
        }
        return w to h
    }

    /**
     * 实际下采样（Android）。在预算内直接返回原图，否则等比缩小到目标尺寸。
     * @param maxBytes 字节预算（如 200KB）
     * @param maxDimen 单边像素上限（如 512）
     */
    fun downsample(src: Bitmap, maxBytes: Int, maxDimen: Int): Bitmap {
        val within = src.allocationByteCount <= maxBytes && src.width <= maxDimen && src.height <= maxDimen
        if (within) return src
        val (tw, th) = targetDimensions(src.width, src.height, maxBytes, maxDimen)
        if (tw >= src.width && th >= src.height) return src
        return Bitmap.createScaledBitmap(src, tw, th, /* filter = */ true)
    }
}
