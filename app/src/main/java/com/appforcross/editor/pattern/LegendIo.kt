package com.appforcross.editor.pattern

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LegendIo {

    fun load(file: File): PatternLegend? {
        if (!file.exists()) return null
        val txt = file.readText()
        val obj = JSONObject(txt)
        val arr = obj.getJSONArray("entries")
        val entries = mutableListOf<LegendEntry>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            entries += LegendEntry(
                idx = it.getInt("idx"),
                code = it.optString("code", null),
                name = it.optString("name", null),
                rgb  = parseRgb(it.optString("rgb", "#000000")),
                symbol = it.optString("symbol", "?")
            )
        }
        val overrides = mutableMapOf<Int, String>()
        obj.optJSONObject("overrides")?.let { ov ->
            ov.keys().forEach { k ->
                overrides[k.toInt()] = ov.getString(k)
            }
        }
        return PatternLegend(entries, overrides)
    }

    fun save(file: File, legend: PatternLegend) {
        val arr = JSONArray()
        legend.entries.forEach { e ->
            val o = JSONObject()
            o.put("idx", e.idx)
            e.code?.let { o.put("code", it) }
            e.name?.let { o.put("name", it) }
            o.put("rgb", toHexRgb(e.rgb))
            o.put("symbol", e.symbol)
            arr.put(o)
        }
        val root = JSONObject()
            .put("entries", arr)
            .put("overrides", JSONObject().apply {
                legend.overrides.forEach { (idx, sym) -> put(idx.toString(), sym) }
            })
        file.writeText(root.toString())
    }

    private fun parseRgb(s: String): Int {
        // #RRGGBB
        return Color.parseColor(s)
    }

    private fun toHexRgb(c: Int): String {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = (c) and 0xFF
        return String.format("#%02X%02X%02X", r, g, b)
    }
}