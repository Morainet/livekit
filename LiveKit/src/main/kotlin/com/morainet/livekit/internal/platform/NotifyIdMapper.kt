/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

/**
 * 复合键 → 稳定 notifyId 映射（白皮书 §7）。
 *
 * 预留 SDK 专属高位段 0x4C4B____，与宿主自有通知 ID 隔离，避免互相顶替。
 * 低 16 位取复合键的 FNV-1a 稳定哈希，保证同键同 ID、跨进程一致。
 */
object NotifyIdMapper {
    private const val BASE = 0x4C4B0000.toInt()
    private const val MASK = 0x0000FFFF

    fun idFor(internalKey: String): Int = BASE or (stableHash(internalKey) and MASK)

    /** FNV-1a 32-bit，纯函数、平台无关，跨进程结果一致。 */
    private fun stableHash(s: String): Int {
        var hash = -0x7ee3623b // 2166136261 的有符号表示
        for (b in s.encodeToByteArray()) {
            hash = hash xor (b.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash
    }
}
