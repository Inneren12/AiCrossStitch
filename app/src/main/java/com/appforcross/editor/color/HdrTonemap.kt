package com.appforcross.editor.color

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Half
import java.nio.ShortBuffer
import android.graphics.ColorSpace
import com.appforcross.editor.logging.Logger
import kotlin.math.*
import com.appforcross.editor.util.HalfBufferPool

/** Простейший EOTF для BT.2100 **PQ** и **HLG** → линейный (норм. до [0..1]). */
object HdrTonemap {

    /** Если cs == BT2020_PQ или BT2020_HLG — применяет EOTF к **linear sRGB F16** bitmap. */
    @SuppressLint("NewApi", "HalfFloat")
    fun applyIfNeeded(
        linearSrgbF16: Bitmap,
        srcColorSpace: ColorSpace?,
        headroom: Float = 3f
    ): Boolean {
        if (srcColorSpace == null) return false
        val isPQ = srcColorSpace == ColorSpace.get(ColorSpace.Named.BT2020_PQ)
        val isHLG = srcColorSpace == ColorSpace.get(ColorSpace.Named.BT2020_HLG)
        if (!isPQ && !isHLG) return false
        val w = linearSrgbF16.width
        val h = linearSrgbF16.height
        // === Буферизованное чтение/запись F16 ===
        val total = w * h * 4
        val half = HalfBufferPool.obtain(total)
        // Считываем сырые half‑значения линейного sRGB (RGBA_F16) без getColor.
        val inBuf: ShortBuffer = ShortBuffer.wrap(half, 0, total)
        linearSrgbF16.copyPixelsToBuffer(inBuf)
        if (isPQ) Logger.i("IO", "hdr.tonemap", mapOf("oetf" to "PQ", "space" to srcColorSpace.name))
        if (isHLG) Logger.i("IO", "hdr.tonemap", mapOf("oetf" to "HLG", "space" to srcColorSpace.name))
        var headroomMax = 0f
        // Обрабатываем in-place в массиве half (RGBA)
        var i = 0
        while (i < total) {
            // R,G,B,A — в half
            var r = Half.toFloat(half[i])
            var g = Half.toFloat(half[i + 1])
            var b = Half.toFloat(half[i + 2])
            val a = Half.toFloat(half[i + 3]).coerceIn(0f, 1f)
            // EOTF
            if (isPQ) { r = pqToLinear(r); g = pqToLinear(g); b = pqToLinear(b) }
            else      { r = hlgToLinear(r); g = hlgToLinear(g); b = hlgToLinear(b) }
            // Телеметрия по "запасу над 1.0"
            headroomMax = max(headroomMax, max(r, max(g, b)) - 1f)
            // Мягкое плечо и клип снизу
            r = max(0f, softKneeAbove1(r, headroom)); g = max(0f, softKneeAbove1(g, headroom)); b = max(0f, softKneeAbove1(b, headroom))
             // Запись обратно в half
            half[i    ] = Half.toHalf(r)
            half[i + 1] = Half.toHalf(g)
            half[i + 2] = Half.toHalf(b)
            half[i + 3] = Half.toHalf(a)
            i += 4
        }
        // Записываем обратно без 8‑битной квантизации
        linearSrgbF16.copyPixelsFromBuffer(ShortBuffer.wrap(half, 0, total))
        HalfBufferPool.trimIfOversized()
        // После тонмапа явно помечаем bitmap как Linear sRGB
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            linearSrgbF16.setColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))
        }
        Logger.i("IO", "hdr.tonemap.done", mapOf("space" to srcColorSpace.name, "headroomMax" to headroomMax))
        return true
    }

    // ===== BT.2100 — PQ (ST.2084) EOTF =====
    // Нормируем до [0..1] для нашего пайплайна. Коэффициенты из стандарта.
    private fun pqToLinear(v: Float): Float {
        val m1 = 2610f / 16384f
        val m2 = 2523f / 32f
        val c1 = 3424f / 4096f
        val c2 = 2413f / 128f
        val c3 = 2392f / 128f
        val vp = max(v, 0f).toDouble().pow(1.0 / m2).toFloat()
        val num = max(vp - c1, 0f)
        val den = c2 - c3 * vp
        val l = (num / den).toDouble().pow(1.0 / m1).toFloat() // относительная яркость
        // грубая нормализация в [0..1] (для тонов 0..~1000 нит)
        return (l / 1.0f).coerceIn(0f, 1.5f) // небольшой запас
    }

    // ===== BT.2100 — HLG OETF^-1 (EOTF) =====
    private fun hlgToLinear(v: Float): Float {
        // В упрощённой форме: см. ITU-R BT.2100 Annex 2
        val a = 0.17883277f
        val b = 1f - 4f * a
        val c = 0.5f - a * ln(4f * a)
        return if (v <= 0.5f) (v * v) / 3f
        else (exp((v - c) / a) + b) / 12f
    }
    /**
     * Мягкое «плечо» над 1.0: сохраняет динамический диапазон, но подавляет слишком яркие пики.
     * При headroom=3f выходная яркость асимптотически стремится к 1+headroom.
     */
    private fun softKneeAbove1(v: Float, headroom: Float = 3f): Float {
        if (v <= 1f) return v
        val e = v - 1f
        val k = headroom
        // e' = e / (1 + e/k); итог: 1..(1+k)
        return 1f + e / (1f + e / k)
    }
}