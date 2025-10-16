package com.appforcross.editor.color

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import com.appforcross.editor.logging.Logger
import kotlin.math.*

/** Простейший EOTF для BT.2100 **PQ** и **HLG** → линейный (норм. до [0..1]). */
object HdrTonemap {

    /** Если cs == BT2020_PQ или BT2020_HLG — применяет EOTF к **linear sRGB F16** bitmap. */
    fun applyIfNeeded(linearSrgbF16: Bitmap, srcColorSpace: ColorSpace?): Boolean {
        if (srcColorSpace == null) return false
        val isPQ = srcColorSpace == ColorSpace.get(ColorSpace.Named.BT2020_PQ)
        val isHLG = srcColorSpace == ColorSpace.get(ColorSpace.Named.BT2020_HLG)
        if (!isPQ && !isHLG) return false
        val w = linearSrgbF16.width
        val h = linearSrgbF16.height
        val dst = ColorSpace.get(ColorSpace.Named.LINEAR_SRGB)
        if (isPQ) Logger.i("IO", "hdr.tonemap", mapOf("oetf" to "PQ", "space" to srcColorSpace.name))
        if (isHLG) Logger.i("IO", "hdr.tonemap", mapOf("oetf" to "HLG", "space" to srcColorSpace.name))
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Читаем без потери: Bitmap.getColor() возвращает float-компоненты в пространстве bitmap (Linear sRGB).
                val c = linearSrgbF16.getColor(x, y)
                var r = c.red(); var g = c.green(); var b = c.blue()
                // На входе уже linear sRGB; «hdr» — headroom > 1.0 после EOTF.
                if (isPQ) {
                    r = pqToLinear(r); g = pqToLinear(g); b = pqToLinear(b)
                } else {
                        r = hlgToLinear(r); g = hlgToLinear(g); b = hlgToLinear(b)
                    }
                // Мягкое «плечо» вместо жёсткого клипа: сохраняем запас яркости для последующих шагов.
                r = softKneeAbove1(r); g = softKneeAbove1(g); b = softKneeAbove1(b)
                // нижняя граница 0 (отрицательные значения не несут пользы)
                if (r < 0f) r = 0f; if (g < 0f) g = 0f; if (b < 0f) b = 0f
                row[x] = Color.valueOf(r, g, b, c.alpha(), dst).toArgb()
            }
            linearSrgbF16.setPixels(row, 0, w, 0, y, w, 1)
        }
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