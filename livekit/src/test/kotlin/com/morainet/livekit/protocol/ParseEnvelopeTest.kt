/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.protocol

import com.morainet.livekit.LiveKit
import com.morainet.livekit.model.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 校验标准 JSON 外壳的解析契约（白皮书 §10.1 / §13 `testMalformedJsonRejected`）：
 * 必填字段缺失或取值非法时整包拒绝（抛异常），可选字段缺失走默认值。
 *
 * 注：[LiveKit.dispatchRawJson] 的 catch → 事件转换链依赖 `LiveKit` 单例与 Context，
 * 无法在纯 JVM 下测；这里只覆盖纯解析逻辑（`parse` 本身不碰 Android Framework）。
 */
class ParseEnvelopeTest {

    /** 一份合法完整的外壳，供各用例裁剪。 */
    private val full = """
        {
          "protocol_version": 1,
          "biz_type": "food",
          "activity_id": "10001",
          "action": "UPDATE",
          "template_id": "delivery",
          "seq_id": 42,
          "timestamp": 1737000000000,
          "clear_policy": { "dismiss_after_seconds": 300 },
          "payload": { "progress": 50 }
        }
    """.trimIndent()

    private fun parse(json: String) = LiveKit.parse(json)

    // ---- 畸形整包拒绝：任何必填字段缺失 / 取值非法 → 抛异常，绝不部分应用 ----

    @Test
    fun testMalformedSyntaxRejected() {
        // 非法 JSON 语法
        assertThrows(Throwable::class.java) { parse("{bad") }
        assertThrows(Throwable::class.java) { parse("") }
        assertThrows(Throwable::class.java) { parse("not even json") }
    }

    @Test
    fun testMissingBizTypeRejected() {
        val json = full.replace("\"biz_type\": \"food\",", "")
        assertThrows(Throwable::class.java) { parse(json) }
    }

    @Test
    fun testMissingActivityIdRejected() {
        val json = full.replace("\"activity_id\": \"10001\",", "")
        assertThrows(Throwable::class.java) { parse(json) }
    }

    @Test
    fun testMissingSeqIdRejected() {
        val json = full.replace("\"seq_id\": 42,", "")
        assertThrows(Throwable::class.java) { parse(json) }
    }

    @Test
    fun testMissingTimestampRejected() {
        val json = full.replace("\"timestamp\": 1737000000000,", "")
        assertThrows(Throwable::class.java) { parse(json) }
    }

    @Test
    fun testInvalidActionRejected() {
        // action 非 START/UPDATE/END → Action.valueOf 抛 IllegalArgumentException
        val json = full.replace("\"UPDATE\"", "\"FOO\"")
        assertThrows(Throwable::class.java) { parse(json) }
    }

    // ---- 正向：合法外壳正常解析，字段值正确 ----

    @Test
    fun testWellFormedEnvelopeParsed() {
        val env = parse(full)
        assertEquals(1, env.protocolVersion)
        assertEquals("food", env.bizType)
        assertEquals("10001", env.activityId)
        assertEquals(Action.UPDATE, env.action)
        assertEquals("delivery", env.templateId)
        assertEquals(42L, env.seqId)
        assertEquals(1737000000000L, env.timestamp)
        assertEquals("food#10001", env.internalKey)
        assertEquals(300, env.clearPolicy?.dismissAfterSeconds)
        assertEquals(50, env.payload["progress"])
    }

    @Test
    fun testAllActionsAccepted() {
        for (a in Action.entries) {
            val json = full.replace("\"UPDATE\"", "\"${a.name}\"")
            assertEquals(a, parse(json).action)
        }
    }

    @Test
    fun testOptionalFieldsOptional() {
        // 去掉 template_id / payload / clear_policy / protocol_version，均走默认值不报错
        val json = """
            {
              "biz_type": "food",
              "activity_id": "1",
              "action": "START",
              "seq_id": 1,
              "timestamp": 1
            }
        """.trimIndent()
        val env = parse(json)
        assertEquals(1, env.protocolVersion) // 默认 1
        assertNull(env.templateId)
        assertNull(env.clearPolicy)
        assertEquals(emptyMap<String, Any?>(), env.payload)
    }

    @Test
    fun testTemplateIdNullableWhenExplicitNull() {
        // template_id 显式 null（如 END 包）应解析为 null，不抛异常
        val json = full.replace("\"delivery\"", "null")
        assertNull(parse(json).templateId)
    }

    @Test
    fun testPayloadNullValuesPreserved() {
        // payload 内显式 null 值应保留为 null（字段级合并语义）
        val json = """
            {
              "biz_type": "food", "activity_id": "1", "action": "UPDATE",
              "seq_id": 1, "timestamp": 1,
              "payload": { "a": null, "b": 2 }
            }
        """.trimIndent()
        val env = parse(json)
        assertNull(env.payload["a"])
        assertEquals(2, env.payload["b"])
    }

    @Test
    fun testProtocolVersionDefaultsToOne() {
        val json = """
            {
              "biz_type": "food", "activity_id": "1", "action": "START",
              "seq_id": 1, "timestamp": 1
            }
        """.trimIndent()
        assertEquals(1, parse(json).protocolVersion)
    }
}
