/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.lifecycle

/**
 * 前台活跃 Activity 引用计数（白皮书 §8.6）的纯逻辑核心，平台无关、可直接单测。
 * [onStart]/[onStop] 返回是否发生了前后台跨越，供上层触发提权 / 收敛。
 */
class ForegroundCounter {
    private var startedActivityCount = 0

    val isAppInForeground: Boolean get() = startedActivityCount > 0

    /** @return true 表示由后台跨入前台（0 → 1）。 */
    fun onStart(): Boolean {
        val wasBackground = startedActivityCount == 0
        startedActivityCount++
        return wasBackground
    }

    /** @return true 表示由前台跨入后台（1 → 0）。 */
    fun onStop(): Boolean {
        if (startedActivityCount > 0) startedActivityCount--
        return startedActivityCount == 0
    }
}
