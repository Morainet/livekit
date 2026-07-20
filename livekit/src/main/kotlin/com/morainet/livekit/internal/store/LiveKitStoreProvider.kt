/**
 * Created by xichen on 2026-07-18.
 * Copyright (c) 2026 Morainet. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.morainet.livekit.internal.store

import android.content.ContentProvider
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle

/**
 * 内置零依赖跨进程存储（白皮书 §5.2 兜底通道）。
 *
 * 刻意保持单实例（不声明 android:multiprocess），:push 的写经 Binder 漏斗到唯一 provider
 * 串行化，从根上消除跨进程 SQLite 争锁，且该 Binder 调用顺带唤醒 :main。
 * 底层开启 WAL，读写不互斥。以 call() RPC 收敛 CRUD，写入后 notifyChange 唤醒 :main 观察者。
 */
internal class LiveKitStoreProvider : ContentProvider() {

    private lateinit var db: SQLiteDatabase
    private lateinit var baseUri: Uri

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        baseUri = Uri.parse("content://${ctx.packageName}.livekit.store")
        val helper = object : SQLiteOpenHelper(ctx, "livekit_store.db", null, 1) {
            override fun onCreate(d: SQLiteDatabase) {
                d.execSQL("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
            }
            override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {}
        }
        helper.setWriteAheadLoggingEnabled(true)
        db = helper.writableDatabase
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        "put" -> {
            putValue(arg!!, extras?.getString("v"))
            notifyKey(arg)
            null
        }
        "get" -> Bundle().apply { putString("v", getValue(arg!!)) }
        "remove" -> {
            deleteValue(arg!!)
            notifyKey(arg)
            null
        }
        "keys" -> Bundle().apply { putStringArray("keys", listKeys().toTypedArray()) }
        // 纯跨进程信号：数据存在别处（如 MMKV），provider 只负责 notifyChange 唤醒。
        "notify" -> {
            notifyKey(arg!!)
            null
        }
        else -> null
    }

    private fun putValue(k: String, v: String?) {
        db.execSQL("INSERT OR REPLACE INTO kv(k, v) VALUES(?, ?)", arrayOf<Any?>(k, v))
    }

    private fun getValue(k: String): String? =
        db.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun deleteValue(k: String) {
        db.execSQL("DELETE FROM kv WHERE k=?", arrayOf<Any?>(k))
    }

    private fun listKeys(): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT k FROM kv", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    private fun notifyKey(key: String) {
        val uri = baseUri.buildUpon().appendPath("item").appendPath(key).build()
        context?.contentResolver?.notifyChange(uri, null)
    }

    // 未使用的标准 CRUD 出口。
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
