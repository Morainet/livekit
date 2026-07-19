/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.store

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.morainet.livekit.store.ILiveKitStore

/**
 * [ILiveKitStore] 的默认实现：经 ContentResolver.call 走到 [LiveKitStoreProvider]，
 * 并以 ContentObserver 接收跨进程变更（白皮书 §5.3 首选唤醒方案）。
 */
internal class ContentProviderStore(context: Context) : ILiveKitStore {

    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.livekit.store"
    private val baseUri: Uri = Uri.parse("content://$authority")
    private val resolver get() = appContext.contentResolver

    override fun put(key: String, value: String) {
        resolver.call(authority, "put", key, Bundle().apply { putString("v", value) })
    }

    override fun get(key: String): String? =
        resolver.call(authority, "get", key, null)?.getString("v")

    override fun remove(key: String) {
        resolver.call(authority, "remove", key, null)
    }

    override fun keys(): Set<String> =
        resolver.call(authority, "keys", null, null)?.getStringArray("keys")?.toSet() ?: emptySet()

    override fun observe(onChanged: (key: String) -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.lastPathSegment?.let(onChanged)
            }
        }
        resolver.registerContentObserver(baseUri, true, observer)
    }
}
