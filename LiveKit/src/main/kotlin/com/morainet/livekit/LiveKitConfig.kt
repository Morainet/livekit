/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit

import android.content.pm.ServiceInfo
import com.morainet.livekit.model.LiveKitObserver
import com.morainet.livekit.store.ILiveKitStore

/**
 * 全局配置。数值项与白皮书 §10.3 对齐。
 */
data class LiveKitConfig(
    val store: ILiveKitStore? = null,
    val defaultChannelId: String = "livekit_default",
    val smallIconRes: Int = android.R.drawable.ic_dialog_info,
    val defaultThrottleWindowMs: Long = 1000L,
    val perBizThrottleMs: Map<String, Long> = emptyMap(),
    val orphanBufferMs: Long = 3000L,
    val maxBitmapBytes: Int = 200 * 1024,
    val maxBitmapDimenPx: Int = 512,
    val chronometerRomBlacklist: Set<String> = emptySet(),
    val maxThrottleWindowMs: Long = 5000L,
    val storeRetryBackoffMs: List<Long> = listOf(50L, 100L, 200L),
    val enableForegroundService: Boolean = true,
    val fgsType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    val observer: LiveKitObserver? = null,
)
