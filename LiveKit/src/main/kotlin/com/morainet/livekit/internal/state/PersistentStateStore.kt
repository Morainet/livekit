/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.state

import com.morainet.livekit.model.Action
import com.morainet.livekit.store.ILiveKitStore
import org.json.JSONObject

/**
 * 把 [ActivityState] 序列化后落到跨进程 [ILiveKitStore]（白皮书 §5）。
 * local.seq 随状态一并持久化，保证进程重启 / :push↔:main 切换后防乱序仍生效。
 */
internal class PersistentStateStore(private val store: ILiveKitStore) : StateStore {

    override fun get(key: String): ActivityState? =
        store.get(key)?.let { runCatching { deserialize(it) }.getOrNull() }

    override fun put(key: String, state: ActivityState) {
        store.put(key, serialize(state))
    }

    override fun remove(key: String) = store.remove(key)

    override fun keys(): Set<String> = store.keys()

    private fun serialize(state: ActivityState): String = JSONObject().apply {
        put("seq", state.seqId)
        put("ts", state.timestamp)
        put("tpl", state.templateId ?: JSONObject.NULL)
        put("act", state.lastAction.name)
        put("end", state.ended)
        put("dis", state.dismissAfterSeconds ?: JSONObject.NULL)
        put("pl", JSONObject(state.payload))
    }.toString()

    private fun deserialize(raw: String): ActivityState {
        val o = JSONObject(raw)
        val pl = o.getJSONObject("pl")
        val payload = buildMap<String, Any?> {
            pl.keys().forEach { k -> put(k, if (pl.isNull(k)) null else pl.get(k)) }
        }
        return ActivityState(
            seqId = o.getLong("seq"),
            timestamp = o.getLong("ts"),
            templateId = if (o.isNull("tpl")) null else o.getString("tpl"),
            payload = payload,
            lastAction = Action.valueOf(o.getString("act")),
            ended = o.getBoolean("end"),
            dismissAfterSeconds = if (o.isNull("dis")) null else o.getInt("dis"),
        )
    }
}
