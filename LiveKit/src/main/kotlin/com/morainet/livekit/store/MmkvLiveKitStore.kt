/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.store

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.tencent.mmkv.MMKV

/**
 * [ILiveKitStore] 的 MMKV 实现（白皮书 §5.2 推荐的默认高性能存储）。
 *
 * 职责分离：**存储**用 MMKV（mmap + 文件锁，天然多进程安全、高性能）；
 * **跨进程变更信号**复用内置 LiveKitStoreProvider 的 notifyChange —— 因为 MMKV 本身
 * 不提供跨进程观察能力。
 *
 * 前置：宿主须先 `MMKV.initialize(context)` 并把本类通过 [LiveKitConfig.store] 注入。
 * 依赖 `com.tencent:mmkv` 为可选，未引入时请使用默认的 ContentProvider 存储。
 */
class MmkvLiveKitStore @JvmOverloads constructor(
    context: Context,
    mmapId: String = "livekit",
    cryptKey: String? = null,
) : ILiveKitStore {

    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.livekit.store"
    private val baseUri: Uri = Uri.parse("content://$authority")
    private val resolver get() = appContext.contentResolver
    private val mmkv: MMKV = MMKV.mmkvWithID(mmapId, MMKV.MULTI_PROCESS_MODE, cryptKey)

    override fun put(key: String, value: String) {
        mmkv.encode(key, value)
        signal(key)
    }

    override fun get(key: String): String? = mmkv.decodeString(key)

    override fun remove(key: String) {
        mmkv.removeValueForKey(key)
        signal(key)
    }

    override fun keys(): Set<String> = mmkv.allKeys()?.toSet() ?: emptySet()

    override fun observe(onChanged: (key: String) -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.lastPathSegment?.let(onChanged)
            }
        }
        resolver.registerContentObserver(baseUri, true, observer)
    }

    /** MMKV 写入后触发跨进程唤醒（数据已在 MMKV，此处只发信号）。 */
    private fun signal(key: String) {
        runCatching { resolver.call(authority, "notify", key, null) }
    }
}
