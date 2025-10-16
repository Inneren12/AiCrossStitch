package com.appforcross.editor.filters

import android.graphics.Bitmap
import android.graphics.Color
import com.appforcross.editor.logging.Logger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Подавление светлых ореолов (смартфонный шарп): DoG вдоль кромок + мягкий clamp. */
object HaloRemoval {

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
        // 2) DoG: blur(r) - blur(2r)
        val blurSmall = gaussianBlur(L, w, h, radiusPx)
        val blurLarge = gaussianBlur(L, w, h, radiusPx * 2)
        val dog = FloatArray(w * h)
        var haloScore = 0.0
        for (i in dog.indices) {
            val v = blurSmall[i] - blurLarge[i]
            dog[i] = v
            haloScore += kotlin.math.abs(v)
        }
        haloScore /= dog.size

        // 3) Снижаем положительные ореолы (светлые каймы) около кромок.
        //    Простая эвристика: если DoG>>0 — уменьшаем L; если DoG<<0 — почти не трогаем.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val c = Color.valueOf(src[idx])
                val l = L[idx]
                val d = dog[idx]
                val k = if (d > 0f) amount else amount * 0.05f
                val nl = (l - k * d).coerceIn(0f, 1f)
                // Пропорционально меняем RGB (сохраняя оттенок)
                val scale = if (l > 1e-6f) nl / l else 1f
                val nr = (c.red() * scale).coerceIn(0f, 1f)
                val ng = (c.green() * scale).coerceIn(0f, 1f)
                val nb = (c.blue() * scale).coerceIn(0f, 1f)
                dst[idx] = Color.valueOf(nr, ng, nb, c.alpha()).toArgb()
            }
        }
        bitmap.setPixels(dst, 0, w, 0, 0, w, h)
        Logger.i("FILTER", "halo.removed", mapOf("score" to haloScore, "amount" to amount, "radius" to radiusPx))
        return haloScore.toFloat()
    }

    // Простой сепарабельный гаусс
    private fun gaussianBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val sigma = max(1.0, radius.toDouble() / 2.0).toFloat()
        val k = kernel1D(sigma, radius)
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
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
                tmp[row + x] = acc / norm
            }
        }
        // Y
        for (x in 0 until w) {
            for (y in 0 until h) {
                var acc = 0f; var norm = 0f
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val wgt = k[dy + radius]
                    acc += tmp[yy * w + x] * wgt
                    norm += wgt
                }
                out[y * w + x] = acc / norm
            }
        }
        return out
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