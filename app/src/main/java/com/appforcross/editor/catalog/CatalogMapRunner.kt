package com.appforcross.editor.catalog

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/** Опции маппинга (совместимы с ImportTab: com.appforcross.editor.catalog.CatalogMapOptions). */


/**
 * Реальная сборка каталога: палитра → ближайшие коды бренда (single/blend),
 * запись palette_catalog.json (строго), метрики ΔE.
 */
object CatalogMapRunner {
    data class Output(
        val brand: String,
        val avgDE: Double,
        val maxDE: Double,
        val blends: Int,
        val jsonPath: String
    )

    @JvmStatic
    fun run(
        ctx: Context,
        palette: IntArray,
        brand: String,
        options: CatalogMapOptions = CatalogMapOptions()
    ): Output {
        require(palette.isNotEmpty()) { "Palette is empty" }

        // 1) Берём карту brand code -> RGB из assets, надёжно (без падений в «фоллбек»).
        val brandPath = ThreadCatalogs.resolveBrandAssetPath(ctx, brand)
        val codes = loadBrandCodes(ctx, brandPath) // список (code, rgb)
        require(codes.isNotEmpty()) { "No colors for brand=$brand (asset=$brandPath)" }

        // Предварительно считаем Lab для бренда — ускоряет поиск
        val labCodes = codes.map { it.first to rgbToLab(it.second) }

        // 2) Для каждого idx палитры находим лучший single; опционально — лучший blend среди topN
        val entries = JSONArray()
        var sumDE = 0.0
        var maxDE = 0.0
        var blendsUsed = 0

        val topN = 8 // ограничим кандидатов для blend-поиска
        for (idx in palette.indices) {
            val rgb = palette[idx]
            val lab = rgbToLab(rgb)

            // Лучший single
            var bestSingleCode = ""
            var bestSingleDE = Double.POSITIVE_INFINITY

            // Топ кандидатов для последующего blend-подбора
            val top = ArrayList<Pair<String, Double>>(topN) // (code, ΔE)

            for ((code, labC) in labCodes) {
                val de = deltaE76(lab, labC)
                if (de < bestSingleDE) {
                    bestSingleDE = de
                    bestSingleCode = code
                }
                // заполняем топ-N простым способом
                if (top.size < topN) {
                    top += code to de
                } else {
                    // заменить худшего, если текущий лучше
                    var worstIdx = -1
                    var worstVal = -1.0
                    for (i in top.indices) {
                        if (top[i].second > worstVal) {
                            worstVal = top[i].second
                            worstIdx = i
                        }
                    }
                    if (de < worstVal && worstIdx >= 0) top[worstIdx] = code to de
                }
            }
            // Отсортировать топ кандидатов по возрастанию ΔE
            top.sortBy { it.second }

            var chosenIsBlend = false
            var chosenA = bestSingleCode
            var chosenB: String? = null
            var chosenDE = bestSingleDE

            if (options.allowBlends && blendsUsed < options.maxBlends && top.size >= 2) {
                // Перебираем пары из первых M кандидатов (например, 6)
                val M = min(6, top.size)
                var bestBlendDE = Double.POSITIVE_INFINITY
                var bestPair: Pair<String, String>? = null
                for (i in 0 until M) {
                    val codeA = top[i].first
                    val rgbA = codes.first { it.first == codeA }.second
                    for (j in i + 1 until M) {
                        val codeB = top[j].first
                        val rgbB = codes.first { it.first == codeB }.second
                        val mix = avgRgb(rgbA, rgbB)
                        val mixLab = rgbToLab(mix)
                        val de = deltaE76(lab, mixLab)
                        if (de < bestBlendDE) {
                            bestBlendDE = de
                            bestPair = codeA to codeB
                        }
                    }
                }
                if (bestPair != null) {
                    // c учётом штрафа за blend
                    val penalized = bestBlendDE * (1.0 + options.blendPenalty)
                    if (penalized + 1e-9 < bestSingleDE) {
                        chosenIsBlend = true
                        chosenA = bestPair.first
                        chosenB = bestPair.second
                        chosenDE = bestBlendDE
                    }
                }
            }

            maxDE = max(maxDE, chosenDE)
            sumDE += chosenDE

            val e = JSONObject()
            e.put("idx", idx)
            if (chosenIsBlend) {
                blendsUsed++
                e.put("type", "blend")
                e.put("codeA", chosenA)
                e.put("codeB", chosenB)
            } else {
                e.put("type", "single")
                e.put("code", chosenA)
            }
            entries.put(e)
        }

        // 3) Собираем JSON каталога и пишем в cacheDir — для PatternRunner.readLegendThreadRgbStrict
        val root = JSONObject()
        root.put("brand", brand.uppercase())
        root.put("entries", entries)
        val outFile = File(ctx.cacheDir, "palette_catalog.json")
        FileOutputStream(outFile).use { it.write(root.toString().toByteArray()) }

        val avgDE = sumDE / palette.size
        return Output(
            brand = brand.uppercase(),
            avgDE = round2(avgDE),
            maxDE = round2(maxDE),
            blends = blendsUsed,
            jsonPath = outFile.absolutePath
        )
    }

    // ---------- brand assets loader ----------
    private fun loadBrandCodes(ctx: Context, assetPath: String): List<Pair<String, Int>> {
        // формат: {"id":"DMC","name":"DMC","type":"threads","colors":[{"code":"310","name":"Black","rgb":"#000000"}, ...]}
        return ThreadCatalogs.loadBrandRgbFromAssetPath(ctx, assetPath).map { it.key to it.value }
    }

    // ---------- color math ----------
    private data class Lab(val L: Double, val a: Double, val b: Double)

    private fun rgbToLab(rgb: Int): Lab {
        // sRGB 0..255 -> linear 0..1
        fun ch(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = ch(Color.red(rgb))
        val g = ch(Color.green(rgb))
        val b = ch(Color.blue(rgb))
        // linear RGB -> XYZ (D65)
        val x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
        // normalize by reference white D65
        val Xn = 0.95047
        val Yn = 1.00000
        val Zn = 1.08883
        fun f(t: Double): Double {
            val d = (6.0 / 29.0)
            val d3 = d * d * d
            return if (t > d3) t.pow(1.0 / 3.0) else t / (3.0 * d * d) + 4.0 / 29.0
        }
        val fx = f(x / Xn)
        val fy = f(y / Yn)
        val fz = f(z / Zn)
        val L = 116.0 * fy - 16.0
        val a = 500.0 * (fx - fy)
        val b2 = 200.0 * (fy - fz)
        return Lab(L, a, b2)
    }

    private fun deltaE76(a: Lab, b: Lab): Double {
        val dL = a.L - b.L
        val da = a.a - b.a
        val db = a.b - b.b
        return sqrt(dL * dL + da * da + db * db)
    }

    private fun avgRgb(a: Int, b: Int): Int {
        val r = (Color.red(a) + Color.red(b)) / 2
        val g = (Color.green(a) + Color.green(b)) / 2
        val bl = (Color.blue(a) + Color.blue(b)) / 2
        return Color.rgb(r, g, bl)
    }

    private fun round2(x: Double) = (x * 100.0).roundToInt() / 100.0
    }