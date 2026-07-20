/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.template

import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import com.morainet.livekit.model.LiveProgressSpec
import java.util.concurrent.ConcurrentHashMap

/** RemoteViews 自定义模板：布局 + 数据绑定回调。 */
class Template(
    @param:LayoutRes val layoutId: Int,
    val binder: (views: RemoteViews, payload: Map<String, Any?>) -> Unit,
)

/** ProgressStyle 原生进度模板：payload → 进度规格。 */
class ProgressTemplate(
    @param:DrawableRes val smallIconRes: Int,
    val binder: (payload: Map<String, Any?>) -> LiveProgressSpec,
)

/**
 * bizType#templateId → 模板 的注册表（白皮书 §3）。同一 key 可同时注册 RemoteViews 与 Progress
 * 两种模板：能力允许时优先原生 ProgressStyle，否则降级 RemoteViews。
 */
class TemplateRegistry {
    private val remoteViews = ConcurrentHashMap<String, Template>()
    private val progress = ConcurrentHashMap<String, ProgressTemplate>()

    fun register(bizType: String, templateId: String, @LayoutRes layoutId: Int, binder: (RemoteViews, Map<String, Any?>) -> Unit) {
        remoteViews[key(bizType, templateId)] = Template(layoutId, binder)
    }

    fun registerProgress(bizType: String, templateId: String, @DrawableRes smallIconRes: Int, binder: (Map<String, Any?>) -> LiveProgressSpec) {
        progress[key(bizType, templateId)] = ProgressTemplate(smallIconRes, binder)
    }

    fun get(bizType: String, templateId: String): Template? = remoteViews[key(bizType, templateId)]

    fun getProgress(bizType: String, templateId: String): ProgressTemplate? = progress[key(bizType, templateId)]

    private fun key(bizType: String, templateId: String) = "$bizType#$templateId"
}
