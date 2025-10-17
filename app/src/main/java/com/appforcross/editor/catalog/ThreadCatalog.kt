package com.appforcross.editor.catalog

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import kotlin.math.pow

data class ThreadColor(
    val brand: String,
    val code: String,
    val name: String?,
    val rgb: Int,
    val okL: Float,
    val okA: Float,
    val okB: Float
    )

data class ThreadCatalog(val brand: String, val items: List<ThreadColor>)

object ThreadCatalogs {
    /** Загрузить палитру из assets/palettes/{brand}.json, если нет — пробуем старый путь catalog/{brand}.json, далее fallback. */
    fun load(ctx: Context, brand: String = "DMC"): ThreadCatalog {
        val key = brand.lowercase()
        // 1) новый формат: assets/palettes/{brand}.json (id/name/type/colors[])
        val p1 = "palettes/${key}.json"
        try {
            ctx.assets.open(p1).use {
                val json = it.readBytes().toString(StandardCharsets.UTF_8)
                return parsePaletteSchema(brandUpper(brand), json)
            }
        } catch (_: Exception) { /* try next */ }
        // 2) старый формат: assets/catalog/{brand}.json (brand/items[])
        val p2 = "catalog/${key}.json"
        try {
            ctx.assets.open(p2).use {
                val json = it.readBytes().toString(StandardCharsets.UTF_8)
                return parseLegacySchema(brandUpper(brand), json)
            }
        } catch (_: Exception) { /* fallback */ }
        // 3) fallback (маленький демо-набор)
        return fallbackDmc()
    }

    /** Отдать список доступных brandId по файлам в assets/palettes. */
    fun listAvailable(ctx: Context): List<String> =
        try {
            (ctx.assets.list("palettes") ?: emptyArray())
                .mapNotNull { it.substringBeforeLast('.', missingDelimiterValue = "").takeIf { n -> n.isNotBlank() } }
                .map { it.uppercase() }
                .sorted()
        } catch (_: Exception) { listOf("DMC") }


    // ---------- Парсеры ----------
    /** legacy: { "brand": "...", "items":[{"code","name","rgb":"#RRGGBB"}] } */
    private fun parseLegacySchema(brand: String, json: String): ThreadCatalog {
        val root = JSONObject(json)
        val arr = root.getJSONArray("items")
        val list = ArrayList<ThreadColor>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val code = o.getString("code")
            val name = if (o.has("name")) o.optString("name", null) else null
            val hex = o.getString("rgb")
            val rgb = parseHexFlexible(hex)
            val (L, A, B) = rgbToOKLab(rgb)
            list.add(ThreadColor(brand, code, name, rgb, L, A, B))
        }
        return ThreadCatalog(brand, list)
    }

    /** новая схема (твоя): { id, name, type, colors:[{ code, name, rgb }...] } */
    private fun parsePaletteSchema(brand: String, json: String): ThreadCatalog {
        val root = JSONObject(json)
        val arr = root.getJSONArray("colors")
        val list = ArrayList<ThreadColor>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val code = o.getString("code")
            val name = if (o.has("name")) o.optString("name", null) else null
            val hex = o.getString("rgb")         // может быть без '#'
            val rgb = parseHexFlexible(hex)
            val (L, A, B) = rgbToOKLab(rgb)
            list.add(ThreadColor(brand, code, name, rgb, L, A, B))
        }
        return ThreadCatalog(brand, list)
    }

    /** Мини-фоллбек (демо‑набор), можно заменить на полный. */
    private fun fallbackDmc(): ThreadCatalog {
        val brand = "DMC"
        val raw = listOf(
            "B5200:#FFFFFF:Snow White",
            "BLANC:#F7F7F7:Blanc",
            "3865:#F0EEE8:Winter White",
            "Ecru:#ECE2C6:Ecru",
            "415:#BCC0C3:Pewter Gray",
            "318:#8D939A:Steel Gray",
            "317:#6E747C:Pewter Gray Dark",
            "413:#4E555D:Pewter Gray Very Dark",
            "310:#000000:Black",
            "321:#C2162B:Red",
            "666:#E31E2B:Bright Red",
            "815:#701C31:Garnet Medium",
            "823:#0E1F4A:Navy Blue Dark",
            "939:#0A1433:Navy Blue Very Dark",
            "995:#00A4D6:Electric Blue Dark",
            "727:#FFF1A3:Topaz Very Light"
        )
        val items = raw.map {
            val (code, hex, name) = it.split(":")
            val rgb = parseHexFlexible(hex)
            val (L, A, B) = rgbToOKLab(rgb)
            ThreadColor(brand, code, name, rgb, L, A, B)
        }
        return ThreadCatalog(brand, items)
    }

    // ---------- helpers ----------
    private fun brandUpper(b: String) = b.trim().ifBlank { "DMC" }.uppercase()

    private fun parseHexFlexible(s: String): Int {
        val v = s.trim().removePrefix("#")
        require(v.length == 6) { "Bad hex rgb: $s" }
        val r = v.substring(0,2).toInt(16)
        val g = v.substring(2,4).toInt(16)
        val b = v.substring(4,6).toInt(16)
        return Color.rgb(r,g,b)
    }

    // --- OKLab helpers (sRGB -> linear -> OKLab) ---
    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else (((c + 0.055f) / 1.055f).toDouble().pow(2.4)).toFloat()
    private fun cbrtF(x: Float) = if (x <= 0f) 0f else Math.cbrt(x.toDouble()).toFloat()
    private fun rgbToOKLab(rgb: Int): Triple<Float,Float,Float> {
        val r = Color.red(rgb)/255f
        val g = Color.green(rgb)/255f
        val b = Color.blue(rgb)/255f
        val rl = srgbToLinear(r); val gl = srgbToLinear(g); val bl = srgbToLinear(b)
        val l = 0.4122214708f * rl + 0.5363325363f * gl + 0.0514459929f * bl
        val m = 0.2119034982f * rl + 0.6806995451f * gl + 0.1073969566f * bl
        val s = 0.0883024619f * rl + 0.2817188376f * gl + 0.6299787005f * bl
        val l_ = cbrtF(l); val m_ = cbrtF(m); val s_ = cbrtF(s)
        val L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
        val A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
        val B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        return Triple(L,A,B)
    }
}