/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal

/**
 * 协议版本化与向前兼容（白皮书 §10.1）。
 *
 * SDK 承诺向后兼容旧协议；对高于 [SUPPORTED] 的未来版本采取「尽力而为」：
 * 只解析已知字段、忽略未知字段，照常处理，并上报 UnsupportedVersion 供观测。
 */
object ProtocolVersion {
    const val SUPPORTED = 1

    /** 是否为已知可完整支持的版本。 */
    fun isSupported(version: Int): Boolean = version in 1..SUPPORTED

    /** 是否为高于当前 SDK 上限的未来版本（仍尽力解析，但需上报）。 */
    fun isFuture(version: Int): Boolean = version > SUPPORTED
}
