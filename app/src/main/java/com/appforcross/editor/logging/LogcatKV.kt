package com.appforcross.editor.logging

import android.util.Log

object LogcatKV {
    fun i(tag: String, event: String, data: Map<String, Any?>? = null) {
        Log.i("AiX/$tag", build(event, data))
    }
    fun w(tag: String, event: String, data: Map<String, Any?>? = null) {
        Log.w("AiX/$tag", build(event, data))
    }
    fun e(tag: String, event: String, data: Map<String, Any?>? = null, t: Throwable? = null) {
        Log.e("AiX/$tag", build(event, data), t)
    }
    private fun build(event: String, data: Map<String, Any?>?): String {
        if (data.isNullOrEmpty()) return event
        val parts = data.entries
            .sortedBy { it.key }
            .take(24) // не распыляемся
            .joinToString(" ") { (k, v) -> "$k=${fmt(v)}" }
        return "$event $parts"
    }
    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> v.toString()
    }
}