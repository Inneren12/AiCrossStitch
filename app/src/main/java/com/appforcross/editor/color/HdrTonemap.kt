package com.appforcross.editor.color

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.util.Half
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.util.HalfBufferPool
import java.nio.ShortBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Простейший EOTF для BT.2100 **PQ** и **HLG** → линейный (норм. до [0..1]). */
object HdrTonemap {
    /** Глобальный дефолт для "плеча" HDR→SDR; может задаваться из настроек/UI. */
    @Volatile
    var defaultHeadroom: Float = 3f
    /** Если cs == BT2020_PQ или BT2020_HLG — применяет EOTF к **linear sRGB F16** bitmap. */
    @SuppressLint("NewApi", "HalfFloat")
    fun applyIfNeeded(
        linearSrgbF16: Bitmap,
        srcColorSpace: ColorSpace?,
        headroom: Float = defaultHeadroom,
        alreadyLinearFromConnector: Boolean = false,
        pqNormNits: Float = 1000f
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
        val sbIn: ShortBuffer = ShortBuffer.wrap(half, 0, total)
        val isPremul = linearSrgbF16.isPremultiplied
        try {
            // Считываем сырые half-значения линейного sRGB (RGBA_F16) без getColor.
            linearSrgbF16.copyPixelsToBuffer(sbIn)
            if (isPQ) Logger.i("IO", "hdr.tonemap", mapOf("oetf" to "PQ", "space" to srcColorSpace.name, "premul" to isPremul))
            if (isHLG) Logger.i("IO", "hdr.tonemap", mapOf("oetf" to "HLG", "space" to srcColorSpace.name, "premul" to isPremul))
            var headroomMax = 0f
            // Обрабатываем in-place в массиве half (RGBA)
            var i = 0
            while (i < total) {
                // R,G,B,A — в half
                val storedR = Half.toFloat(half[i])
                val storedG = Half.toFloat(half[i + 1])
                val storedB = Half.toFloat(half[i + 2])
                val a = Half.toFloat(half[i + 3]).coerceIn(0f, 1f)
                val hasPremulAlpha = isPremul && a > 1e-6f
                var r = if (hasPremulAlpha) storedR / a else storedR
                var g = if (hasPremulAlpha) storedG / a else storedG
                var b = if (hasPremulAlpha) storedB / a else storedB
                // Не повторяем EOTF, если пиксели уже LINEAR после ColorSpace.connect
                if (!alreadyLinearFromConnector) {
                    if (isPQ) { r = pqToLinear(r, pqNormNits); g = pqToLinear(g, pqNormNits); b = pqToLinear(b, pqNormNits) }
                    else      { r = hlgToLinear(r); g = hlgToLinear(g); b = hlgToLinear(b) }
                }
                // Телеметрия по "запасу над 1.0": если кадр премультиплирован — считаем по депремультиплированным RGB.
                val rgbMax = max(r, max(g, b))
                headroomMax = max(headroomMax, rgbMax - 1f)
                // Мягкое плечо и клип снизу
                r = max(0f, softKneeAbove1(r, headroom))
                g = max(0f, softKneeAbove1(g, headroom))
                b = max(0f, softKneeAbove1(b, headroom))
                // Возврат в премультиплированное пространство при необходимости
                val outR = if (isPremul) (r * a).coerceIn(0f, 1f) else r.coerceIn(0f, 1f)
                val outG = if (isPremul) (g * a).coerceIn(0f, 1f) else g.coerceIn(0f, 1f)
                val outB = if (isPremul) (b * a).coerceIn(0f, 1f) else b.coerceIn(0f, 1f)
                // Запись обратно в half
                half[i    ] = Half.toHalf(outR)
                half[i + 1] = Half.toHalf(outG)
                half[i + 2] = Half.toHalf(outB)
                half[i + 3] = Half.toHalf(a)
                i += 4
            }
            // Записываем обратно без 8-битной квантизации
            linearSrgbF16.copyPixelsFromBuffer(ShortBuffer.wrap(half, 0, total))
            // После тонмапа явно помечаем bitmap как Linear sRGB
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                linearSrgbF16.setColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))
            }
            Logger.i("IO", "hdr.tonemap.done",
                mapOf("space" to srcColorSpace.name, "headroomMax" to headroomMax,
                    "premul" to isPremul, "headroom" to headroom,
                    "alreadyLinear" to alreadyLinearFromConnector, "pqNormNits" to pqNormNits))
            return true
        } finally {
            // Гарантированно усечём пул даже при исключениях
            HalfBufferPool.trimIfOversized()
        }
    }

    // ===== BT.2100 — PQ (ST.2084) EOTF =====
    // Нормируем до [0..1] для нашего пайплайна. Коэффициенты из стандарта.
    private fun pqToLinear(v: Float, normNits: Float): Float {
        val m1 = 2610f / 16384f
        val m2 = 2523f / 32f
        val c1 = 3424f / 4096f
        val c2 = 2413f / 128f
        val c3 = 2392f / 128f
        val vp = max(v, 0f).toDouble().pow(1.0 / m2).toFloat()
        val num = max(vp - c1, 0f)
        val den = c2 - c3 * vp
        val l = (num / den).toDouble().pow(1.0 / m1).toFloat() // относительная яркость (0..1) ≈ (0..10000 нит)
        // Масштабируем так, чтобы 1.0 ≈ normNits (по умолчанию 1000 нит), без жёсткого клипа
        return l * (10000f / normNits)
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