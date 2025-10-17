package com.appforcross.editor.catalog

import android.graphics.Color
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class CatalogMapOptions(
    val allowBlends: Boolean = true,
    val maxBlends: Int = 4,           // лимит на кол-во блендов в палитре
    val blendPenalty: Double = 0.7,   // штраф за бленд в целевой метрике (чтобы не злоупотреблять)
    val topNForBlend: Int = 8         // пары ищем среди top-N одиночных ближайших
)

sealed class CatalogMatch {
    data class Single(val color: ThreadColor, val dE: Double): CatalogMatch()
    data class Blend(val a: ThreadColor, val b: ThreadColor, val dE: Double): CatalogMatch()
    }

data class CatalogMapEntry(
    val index: Int,
    val paletteRGB: Int,
    val match: CatalogMatch
)

data class CatalogMapMetrics(
    val avgDE: Double,
    val maxDE: Double,
    val blendsCount: Int
)

data class CatalogMapResult(
    val entries: List<CatalogMapEntry>,
    val metrics: CatalogMapMetrics
)

object CatalogMapper {
    /** Основной маппинг: на каждый цвет палитры — ближайшая нитка/бленд. */
    fun mapPaletteToCatalog(palette: IntArray, catalog: ThreadCatalog, opt: CatalogMapOptions = CatalogMapOptions()): CatalogMapResult {
        // Предрасчёт OKLab палитры
        val palLab = Array(palette.size) { okFromRgb(palette[it]) }
        // 1) одиночные ближайшие
        val singleBest = IntArray(palette.size) { 0 }
        val singleDE   = DoubleArray(palette.size) { Double.POSITIVE_INFINITY }
        for (i in palette.indices) {
            val (L,A,B) = palLab[i]
            var best = 0; var bd = Double.POSITIVE_INFINITY
            val items = catalog.items
            for (j in 0 until items.size) {
                val t = items[j]
                val d = de2(L,A,B, t.okL,t.okA,t.okB)
                if (d < bd) { bd = d; best = j }
            }
            singleBest[i] = best
            singleDE[i] = sqrt(bd)
        }
        val entries = ArrayList<CatalogMapEntry>(palette.size)
        var blendsUsed = 0
        // 2) бленды (если разрешены) — проверяем улучшение среди top-N
        for (i in palette.indices) {
            val baseIdx = singleBest[i]
            var bestMatch: CatalogMatch = CatalogMatch.Single(catalog.items[baseIdx], singleDE[i])
            if (opt.allowBlends && blendsUsed < opt.maxBlends) {
                // top-N одиночных для этого цвета
                val top = topN(catalog, palLab[i], n = opt.topNForBlend)
                var bestBlend: CatalogMatch.Blend? = null
                var bestScore = Double.POSITIVE_INFINITY
                for (aIdx in 0 until top.size) {
                    for (bIdx in aIdx until top.size) {
                        val a = top[aIdx]; val b = top[bIdx]
                        val (Lm,Am,Bm) = mixOK(a, b) // 1:1
                        val d = sqrt(de2(palLab[i].first, palLab[i].second, palLab[i].third, Lm,Am,Bm))
                        val score = d + opt.blendPenalty
                        if (score < bestScore) {
                            bestScore = score
                            bestBlend = CatalogMatch.Blend(a, b, d)
                        }
                    }
                }
                if (bestBlend != null && bestBlend.dE < singleDE[i] * 0.8 /* улучшаем хотя бы на 20% */) {
                    bestMatch = bestBlend
                    blendsUsed++
                }
            }
            entries.add(CatalogMapEntry(index = i, paletteRGB = palette[i], match = bestMatch))
        }
        // 3) метрики
        var sum = 0.0; var maxD = 0.0; var blendCnt = 0
        entries.forEach {
            val d = when (val m = it.match) {
                is CatalogMatch.Single -> m.dE
                is CatalogMatch.Blend -> { blendCnt++; m.dE }
            }
            sum += d; if (d > maxD) maxD = d
        }
        val metrics = CatalogMapMetrics(avgDE = sum / entries.size.coerceAtLeast(1), maxDE = maxD, blendsCount = blendCnt)
        return CatalogMapResult(entries, metrics)
    }

    // ---- helpers ----
    private fun okFromRgb(rgb: Int): Triple<Float,Float,Float> {
        val r = Color.red(rgb)/255f; val g = Color.green(rgb)/255f; val b = Color.blue(rgb)/255f
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
    private fun mixOK(a: ThreadColor, b: ThreadColor): Triple<Float,Float,Float> =
        Triple((a.okL+b.okL)/2f, (a.okA+b.okA)/2f, (a.okB+b.okB)/2f)
    private fun de2(L1:Float,A1:Float,B1:Float, L2:Float,A2:Float,B2:Float): Double {
        val dL=(L1-L2).toDouble(); val dA=(A1-A2).toDouble(); val dB=(B1-B2).toDouble()
        return dL*dL + dA*dA + dB*dB
    }
    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else (((c + 0.055f) / 1.055f).toDouble().pow(2.4)).toFloat()
    private fun cbrtF(x: Float) = if (x <= 0f) 0f else Math.cbrt(x.toDouble()).toFloat()

    private fun topN(cat: ThreadCatalog, target: Triple<Float,Float,Float>, n:Int): List<ThreadColor> {
        if (n <= 0) return emptyList()
        val (L,A,B) = target
        val items = cat.items
        val limit = min(n, items.size)
        if (limit == 0) return emptyList()
        val bestColors = ArrayList<ThreadColor>(limit)
        val bestScores = ArrayList<Double>(limit)
        for (t in items) {
            val d = de2(L,A,B, t.okL,t.okA,t.okB)
            if (bestColors.size < limit) {
                var pos = bestScores.size
                while (pos > 0 && d < bestScores[pos - 1]) pos--
                bestScores.add(pos, d)
                bestColors.add(pos, t)
            } else if (d < bestScores.last()) {
                var pos = bestScores.size - 1
                while (pos > 0 && d < bestScores[pos - 1]) pos--
                bestScores.add(pos, d)
                bestColors.add(pos, t)
                bestScores.removeAt(bestScores.lastIndex)
                bestColors.removeAt(bestColors.lastIndex)
            }
        }
        return bestColors
    }
}
