package com.appforcross.editor.filters

import android.annotation.SuppressLint
import android.graphics.Bitmap
import com.appforcross.editor.logging.Logger
import kotlin.math.*
import android.util.Half
import java.nio.ShortBuffer

/** Подавление светлых ореолов (смартфонный шарп): DoG вдоль кромок + мягкий clamp. */
object HaloRemoval {

    // Рабочие буферы — переиспользуем через ThreadLocal, чтобы не аллоцировать на каждый вызов
    private class Workspace {
        var tmp = FloatArray(0)
        var bufA = FloatArray(0)
        var bufB = FloatArray(0)
        fun maybeShrink(pixelCount: Int) {
            // Не удерживаем в ThreadLocal буферы > ~64 МБ на поток
            val bytesEach = pixelCount * 4L
            val tooLarge = bytesEach > 64L * 1024 * 1024
            if (tooLarge) {
                tmp = FloatArray(0); bufA = FloatArray(0); bufB = FloatArray(0)
            }
        }
        fun ensure(cap: Int) {
            if (tmp.size < cap) tmp = FloatArray(cap)
            if (bufA.size < cap) bufA = FloatArray(cap)
            if (bufB.size < cap) bufB = FloatArray(cap)
        }
    }
    private val wsLocal = ThreadLocal.withInitial { Workspace() }

    /** Возвращает оценку halo и применяет исправление in-place. */
    @SuppressLint("HalfFloat")
    fun removeHalosInPlaceLinear(bitmap: Bitmap, amount: Float = 0.25f, radiusPx: Int = 2): Float {
        val w = bitmap.width
        val h = bitmap.height
        // Карта яркости (linear luma) из float‑пикселей
        val L = FloatArray(w * h)
        var idx = 0
        for (y in 0 until h) for (x in 0 until w) {
            val c = bitmap.getColor(x, y)
            L[idx++] = 0.2126f * c.red() + 0.7152f * c.green() + 0.0722f * c.blue()
        }

        // 1) DoG: blur(r) - blur(1.6*r) — ближе к каноническому DoG
        val ws = wsLocal.get().apply { ensure(w * h) }
        gaussianBlurInto(L, w, h, radiusPx, ws.bufA, ws) // small
        val largeR = max(1, (radiusPx * 1.6f).roundToInt())
        gaussianBlurInto(L, w, h, largeR, ws.bufB, ws)   // large
        var haloScore = 0.0

        // 2) Адаптивная сила по средней величине DoG
        val pxCount = (w * h).toDouble()
        // средняя |DoG|
        for (i in 0 until w * h) haloScore += abs((ws.bufA[i] - ws.bufB[i]).toDouble())
        haloScore /= pxCount
        val amountEff = (amount * (1.0f + (haloScore * 20.0).toFloat())).coerceIn(0.1f, 0.6f)

        // 3) Коррекция in‑place и запись назад без 8‑бит квантизации (half‑float)
        val half = ShortArray(w * h * 4)
        var p = 0; idx = 0
        for (y in 0 until h) for (x in 0 until w) {
            val c = bitmap.getColor(x, y)
            val l = L[idx]
            val d = ws.bufA[idx] - ws.bufB[idx]; idx++
            val k = if (d > 0f) amountEff else amountEff * 0.05f
            val nl = (l - k * d).coerceIn(0f, 1f)
            val scale = if (l > 1e-6f) nl / l else 1f
            val nr = (c.red() * scale).coerceIn(0f, 1f)
            val ng = (c.green() * scale).coerceIn(0f, 1f)
            val nb = (c.blue() * scale).coerceIn(0f, 1f)
            half[p++] = Half.toHalf(nr)
            half[p++] = Half.toHalf(ng)
            half[p++] = Half.toHalf(nb)
            half[p++] = Half.toHalf(c.alpha())
        }
        bitmap.copyPixelsFromBuffer(ShortBuffer.wrap(half))
        Logger.i("FILTER", "halo.removed", mapOf("score" to haloScore, "amount_req" to amount, "amount_eff" to amountEff, "radius" to radiusPx, "radiusLarge" to largeR))
        // Снижаем удержание памяти в длительных потоках
        ws.maybeShrink(w * h)
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