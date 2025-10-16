package com.appforcross.editor.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class LogEvent(
    val ts: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val category: String,
    val message: String,
    val data: Map<String, Any?> = emptyMap(),
    val thread: String = Thread.currentThread().name,
    val sessionId: String? = null,
    val requestId: String? = null,
    val tileId: String? = null
) {
    companion object {
        private val iso by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
        private fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
        private fun anyToJson(value: Any?): String = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            is String -> "\"${esc(value)}\""
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
                "\"${esc(it.key.toString())}\":${anyToJson(it.value)}"
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { anyToJson(it) }
            else -> "\"${esc(value.toString())}\""
        }
    }
    fun toJsonLine(): String {
        val base = mutableMapOf<String, Any?>(
            "ts" to iso.format(Date(ts)),
            "epoch_ms" to ts,
            "level" to level.name,
            "cat" to category,
            "msg" to message,
            "thread" to thread
        )
        if (sessionId != null) base["session"] = sessionId
        if (requestId != null) base["req"] = requestId
        if (tileId != null) base["tile"] = tileId
        if (data.isNotEmpty()) base["data"] = data
        return anyToJson(base)
    }
}