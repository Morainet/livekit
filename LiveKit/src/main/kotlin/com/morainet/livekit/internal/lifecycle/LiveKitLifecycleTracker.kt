/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.morainet.livekit.internal.LiveKitEngine

/**
 * 零依赖前后台感知（白皮书 §8.6）：动态注册 ActivityLifecycleCallbacks，
 * 拒绝引入 androidx.lifecycle-process。仅在 :main 进程生效（:push 不承载 Activity）。
 */
internal object LiveKitLifecycleTracker : Application.ActivityLifecycleCallbacks {

    private val counter = ForegroundCounter()

    fun startTracking(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (counter.onStart()) LiveKitEngine.onAppForegrounded()
    }

    override fun onActivityStopped(activity: Activity) {
        if (counter.onStop()) LiveKitEngine.onAppBackgrounded()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
