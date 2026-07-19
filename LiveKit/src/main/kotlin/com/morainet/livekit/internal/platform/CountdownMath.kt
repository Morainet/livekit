/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.platform

/**
 * 零功耗倒计时的纯逻辑（白皮书 §4.4），平台无关、可直接单测。
 * 把绝对截止时间戳换算为系统 Chronometer 用的 elapsedRealtime 基准。
 */
object CountdownMath {

    /**
     * @return 传给 Chronometer 的 elapsedRealtime 基准；若已过期（remaining ≤ 0）返回 null（负数防御）。
     */
    fun countdownBase(targetEpochMs: Long, nowEpochMs: Long, nowElapsedMs: Long): Long? {
        val remaining = targetEpochMs - nowEpochMs
        if (remaining <= 0) return null
        return nowElapsedMs + remaining
    }

    /** 机型黑名单判定（异常 ROM 的 Chronometer 缺陷防御）。 */
    fun isBlacklistedRom(manufacturer: String, blacklist: Set<String>): Boolean =
        blacklist.any { it.isNotBlank() && manufacturer.equals(it, ignoreCase = true) }
}
