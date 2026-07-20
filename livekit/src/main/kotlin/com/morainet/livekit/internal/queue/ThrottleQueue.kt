/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 智能时间流控队列（白皮书 §4.2），Leading + Trailing Edge：
 *  - 首帧（无活跃窗口）立即渲染，消除固定窗口延迟；
 *  - 窗口内的后续更新做字段级增量 merge，不触发渲染；
 *  - 窗口结束时若期间有过合并，触发唯一一次尾帧渲染；
 *  - END 立即 flush 并关窗，不受节流延迟。
 *
 * 每个复合键独立成窗。渲染回调 [onEmit] 在锁外调用，避免持锁挂起。
 */
class ThrottleQueue(
    private val scope: CoroutineScope,
    private val windowMs: Long = 1000L,
    private val onEmit: suspend (key: String, payload: Map<String, Any?>) -> Unit,
) {
    private class Window(
        var merged: Map<String, Any?>,
        var dirty: Boolean,
        var mergedCount: Int,
        var job: Job,
    )

    private val windows = HashMap<String, Window>()
    private val mutex = Mutex()

    /** 提交一次 START/UPDATE。首帧立即发，其余合并到尾帧。 */
    suspend fun submit(key: String, payload: Map<String, Any?>) {
        val leading = mutex.withLock {
            val w = windows[key]
            if (w == null) {
                windows[key] = Window(
                    merged = payload,
                    dirty = false,
                    mergedCount = 0,
                    job = launchWindow(key),
                )
                payload
            } else {
                w.merged = w.merged + payload
                w.dirty = true
                w.mergedCount++
                null
            }
        }
        if (leading != null) onEmit(key, leading)
    }

    /** 提交 END：合并其增量后立即 flush 终态并关窗。 */
    suspend fun submitEnd(key: String, payload: Map<String, Any?>) {
        val terminal = mutex.withLock {
            val w = windows.remove(key)
            if (w != null) {
                w.job.cancel()
                w.merged + payload
            } else {
                payload
            }
        }
        onEmit(key, terminal)
    }

    /** 关闭并丢弃某键的窗口（不 flush），用于 end(immediate) 或清理。 */
    suspend fun cancel(key: String) {
        mutex.withLock { windows.remove(key)?.job?.cancel() }
    }

    private fun launchWindow(key: String): Job = scope.launch {
        delay(windowMs)
        val trailing = mutex.withLock {
            val w = windows.remove(key)
            if (w != null && w.dirty) w.merged else null
        }
        if (trailing != null) onEmit(key, trailing)
    }
}
