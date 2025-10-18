package com.appforcross.editor.catalog

import android.content.Context
import android.graphics.Color
import org.json.JSONObject


object ThreadCatalogs {
    /** Возвращает путь к файлу палитры бренда в assets (например, "palettes/dmc.json"). */

    /** Список доступных брендов из assets/palettes (по полю "id" в JSON или имени файла). */
    fun listAvailable(ctx: Context): List<String> {
        val dir = "palettes"
        val names = ctx.assets.list(dir) ?: emptyArray()
        val brands = LinkedHashSet<String>()
        for (name in names) {
            if (!name.endsWith(".json", ignoreCase = true)) continue
            val path = "$dir/$name"
            try {
                ctx.assets.open(path).use { ins ->
                    val txt = ins.bufferedReader().readText()
                    val root = JSONObject(txt)
                    val id = root.optString("id", "")
                    if (id.isNotBlank()) {
                        brands += id.uppercase()
                    } else {
                            // fallback по имени файла (dmc.json -> DMC)
                        val base = name.substringBeforeLast('.').uppercase()
                        brands += base
                        }
                }
            } catch (_: Exception) {
                // пропускаем битые/нечитаемые файлы
                }
        }
        return if (brands.isEmpty()) listOf("DMC") else brands.toList().sorted()
    }

    fun resolveBrandAssetPath(ctx: Context, brand: String): String {
        val dir = "palettes"
        val want = brand.lowercase()
        // 1) Прямые кандидаты по регистрам
        val candidates = listOf(
            "$dir/${want}.json",
            "$dir/${brand.uppercase()}.json",
"$dir/${brand.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}.json"
        )
        for (p in candidates) {
            if (assetExists(ctx, p)) return p
        }
        // 2) Скан по имёнам в каталоге
        val names = (ctx.assets.list(dir) ?: emptyArray()).toList()
        names.firstOrNull { it.equals("$want.json", ignoreCase = true) }?.let { return "$dir/$it" }
        // 3) Скан по "id" внутри JSON
        for (name in names) {
            if (!name.endsWith(".json", ignoreCase = true)) continue
            val path = "$dir/$name"
            try {
                ctx.assets.open(path).use { ins ->
                    val txt = ins.bufferedReader().readText()
                    val root = JSONObject(txt)
                    val id = root.optString("id", "").lowercase()
                    if (id == want) return path
                }
            } catch (_: Exception) { /* skip */ }
        }
        val msg = buildString {
            append("Brand asset not found for '$brand'. Checked: ")
            append(candidates.joinToString())
            append(". Available: ")
            append(names.joinToString())
        }
        throw IllegalStateException(msg)
    }

    /** Загружает карту code -> RGB (Int) для бренда. */
    fun loadBrandRgb(ctx: Context, brand: String): Map<String, Int> {
        val path = resolveBrandAssetPath(ctx, brand)
        return loadBrandRgbFromAssetPath(ctx, path)
    }

    /** Низкоуровневая загрузка code->RGB по конкретному asset-пути. */
fun loadBrandRgbFromAssetPath(ctx: Context, assetPath: String): Map<String, Int> {
        ctx.assets.open(assetPath).use { ins ->
            val txt = ins.bufferedReader().readText()
            val root = JSONObject(txt)
            val arr = root.getJSONArray("colors")
            val out = HashMap<String, Int>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.getString("code")
                var hex = o.getString("rgb").trim()
                if (!hex.startsWith("#")) hex = "#$hex"
                val rgb = Color.parseColor(hex)
                out[code] = rgb
            }
            if (out.isEmpty()) {
                throw IllegalStateException("Empty brand palette at $assetPath")
            }
            return out
        }
    }

    private fun assetExists(ctx: Context, path: String): Boolean =
        try { ctx.assets.open(path).close(); true } catch (_: Exception) { false }
}