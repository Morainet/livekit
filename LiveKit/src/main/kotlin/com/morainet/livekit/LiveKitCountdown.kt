/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit

import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import com.morainet.livekit.internal.platform.CountdownMath

/**
 * 零功耗倒计时公开工具（白皮书 §4.4）。业务方在 registerTemplate 的 binder 内调用，
 * 把倒计时刷新完全托管给系统 SystemServer，宿主进程可安全挂起、0 功耗。
 */
object LiveKitCountdown {

    /**
     * 将 [viewId] 指向的 Chronometer 绑定为倒计时至 [targetEpochMs]。
     * 已过期（负数防御）或命中 [romBlacklist] 时降级为静态显示，不托管系统跳动。
     */
    fun bind(
        views: RemoteViews,
        viewId: Int,
        targetEpochMs: Long,
        romBlacklist: Set<String> = emptySet(),
    ) {
        views.setChronometerCountDown(viewId, true)
        val base = CountdownMath.countdownBase(
            targetEpochMs = targetEpochMs,
            nowEpochMs = System.currentTimeMillis(),
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        val blacklisted = CountdownMath.isBlacklistedRom(Build.MANUFACTURER, romBlacklist)
        if (base == null || blacklisted) {
            // 静态定格，不启动系统自动跳动（异常 ROM 交由 clear_policy / 业务侧节点重绘兜底）。
            views.setChronometer(viewId, SystemClock.elapsedRealtime(), null, false)
            return
        }
        views.setChronometer(viewId, base, null, true)
    }
}
