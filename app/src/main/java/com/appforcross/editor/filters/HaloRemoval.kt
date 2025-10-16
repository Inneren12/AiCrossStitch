package com.appforcross.editor.filters

import android.graphics.Bitmap
import android.graphics.Color
import com.appforcross.editor.logging.Logger
import kotlin.math.*

/** Подавление светлых ореолов (смартфонный шарп): DoG вдоль кромок + мягкий clamp. */
object HaloRemoval {

    // Рабочие буферы — переиспользуем через ThreadLocal, чтобы не аллоцировать на каждый вызов
    private class Workspace {
        var tmp = FloatArray(0)
        var bufA = FloatArray(0)
        var bufB = FloatArray(0)
        fun ensure(cap: Int) {
            if (tmp.size < cap) tmp = FloatArray(cap)
            if (bufA.size < cap) bufA = FloatArray(cap)
            if (bufB.size < cap) bufB = FloatArray(cap)
        }
    }
    private val wsLocal = ThreadLocal.withInitial { Workspace() }

    /** Возвращает оценку halo и применяет исправление in-place. */
    fun removeHalosInPlaceLinear(bitmap: Bitmap, amount: Float = 0.25f, radiusPx: Int = 2): Float {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)

        // 1) Собираем карту яркости (linear luma)
        val L = FloatArray(w * h)
        for (i in src.indices) {
            val c = Color.valueOf(src[i])
            L[i] = 0.2126f * c.red() + 0.7152f * c.green() + 0.0722f * c.blue()
        }

        // 2) DoG: blur(r) - blur(1.6*r) — ближе к каноническому DoG
        val ws = wsLocal.get().apply { ensure(w * h) }
        gaussianBlurInto(L, w, h, radiusPx, ws.bufA, ws) // small
        val largeR = max(1, (radiusPx * 1.6f).roundToInt())
        gaussianBlurInto(L, w, h, largeR, ws.bufB, ws)   // large
        var haloScore = 0.0

        // 3) Снижаем положительные ореолы (светлые каймы) около кромок.
        //    Простая эвристика: если DoG>>0 — уменьшаем L; если DoG<<0 — почти не трогаем.
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = Color.valueOf(src[idx])
                val l = L[idx]
                val d = ws.bufA[idx] - ws.bufB[idx]
                haloScore += abs(d.toDouble())
                val k = if (d > 0f) amount else amount * 0.05f
                val nl = (l - k * d).coerceIn(0f, 1f)
                // Пропорционально меняем RGB (сохраняя оттенок)
                val scale = if (l > 1e-6f) nl / l else 1f
                val nr = (c.red() * scale).coerceIn(0f, 1f)
                val ng = (c.green() * scale).coerceIn(0f, 1f)
                val nb = (c.blue() * scale).coerceIn(0f, 1f)
                dst[idx] = Color.valueOf(nr, ng, nb, c.alpha()).toArgb()
                idx++
            }
        }
        haloScore /= (w * h).toDouble()
        bitmap.setPixels(dst, 0, w, 0, 0, w, h)
        Logger.i("FILTER", "halo.removed", mapOf("score" to haloScore, "amount" to amount, "radius" to radiusPx, "radiusLarge" to largeR))
        return haloScore.toFloat()
    }

    // Простой сепарабельный гаусс с выводом в предоставленный буфер (для реюза памяти)
    private fun gaussianBlurInto(src: FloatArray, w: Int, h: Int, radius: Int, out: FloatArray, ws: Workspace) {
        val sigma = max(1.0, radius.toDouble() / 2.0).toFloat()
        val k = kernel1D(sigma, radius)
        val tmp = ws.tmp
        // гарантируем буфер
        if (tmp.size < w * h) ws.tmp = FloatArray(w * h)
        val tmpBuf = ws.tmp
        // X
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var acc = 0f; var norm = 0f
                for (dx in -radius..radius) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val wgt = k[dx + radius]
                    acc += src[row + xx] * wgt
                    norm += wgt
                }
                tmpBuf[row + x] = acc / norm
            }
        }
        // Y
        for (x in 0 until w) {
            for (y in 0 until h) {
                var acc = 0f; var norm = 0f
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val wgt = k[dy + radius]
                    acc += tmpBuf[yy * w + x] * wgt
                    norm += wgt
                }
                out[y * w + x] = acc / norm
            }
        }
    }

    private fun kernel1D(sigma: Float, radius: Int): FloatArray {
        val k = FloatArray(radius * 2 + 1)
        var sum = 0f
        val s2 = 2f * sigma * sigma
        for (i in -radius..radius) {
            val v = exp(-(i * i) / s2)
            k[i + radius] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }
}