/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.util

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/** 进程角色判定（白皮书 §3.6）：引擎仅在 :main 全量激活，:push 只落盘不渲染。 */
internal object ProcessUtil {

    fun isMainProcess(context: Context): Boolean {
        val name = processName(context) ?: return true
        // 主进程名等于包名；:push 等辅助进程形如 "pkg:push"。
        return name == context.packageName
    }

    fun processName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        val pid = Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
