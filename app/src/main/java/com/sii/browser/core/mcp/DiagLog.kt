// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.mcp

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 导航与界面事件环，供 MCP 诊断工具读取。
 *
 * 只保留最近 300 条、不落盘；记录的是导航地址与界面状态，不写页面内容与凭据。
 */
object DiagLog {

    private const val MAX = 300

    private class Entry(val at: Long, val tag: String, val msg: String)

    private val entries = ArrayDeque<Entry>()

    /** 仅测试包开启记录；正式包不积累导航事件。 */
    @Volatile
    var enabled: Boolean = false

    fun record(tag: String, msg: String) {
        if (!enabled) return
        synchronized(entries) {
            entries.addLast(Entry(SystemClock.elapsedRealtime(), tag, msg))
            while (entries.size > MAX) entries.removeFirst()
        }
    }

    /** 最近 [limit] 条，按时间正序返回 JSON 数组。 */
    fun dump(limit: Int): String {
        val snapshot = synchronized(entries) { entries.toList() }
        val slice = if (limit <= 0 || limit >= snapshot.size) snapshot else sliceTail(snapshot, limit)
        val array = JSONArray()
        slice.forEach { entry ->
            array.put(
                JSONObject()
                    .put("t", entry.at)
                    .put("tag", entry.tag)
                    .put("msg", entry.msg)
            )
        }
        return array.toString()
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun sliceTail(list: List<Entry>, limit: Int): List<Entry> =
        list.subList(list.size - limit, list.size)
}
