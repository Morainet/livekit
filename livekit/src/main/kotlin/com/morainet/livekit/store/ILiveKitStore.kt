/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.store

/**
 * 跨进程持久化抽象（白皮书 §5.2）。SDK 默认提供 ContentProvider 实现；
 * 宿主可提供 MMKV 适配层通过 [LiveKitConfig.store] 无缝替换。
 *
 * 实现方必须保证多进程读写安全，并在 [observe] 中提供跨进程变更通知。
 */
interface ILiveKitStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun keys(): Set<String>

    /** 注册跨进程变更观察。回调参数为发生变更的 key。 */
    fun observe(onChanged: (key: String) -> Unit)
}
