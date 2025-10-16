package com.appforcross.editor.color

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.os.Build
import com.appforcross.editor.logging.Logger
import kotlin.math.*
import android.util.Half
import com.appforcross.editor.util.HalfBufferPool
import java.nio.ShortBuffer
import java.nio.IntBuffer

/** Конверсия в **linear sRGB** (RGBA_F16), далее все фильтры — в линейном RGB. */
object ColorMgmt {

    /** Преобразовать bitmap (в любом поддерживаемом ColorSpace) в **linear sRGB RGBA_F16**. */
    @SuppressLint("HalfFloat")
    fun toLinearSrgbF16(src: Bitmap, srcCs: ColorSpace?): Bitmap {
        val w = src.width
        val h = src.height
        // [FAST PATH] Уже линейный RGBA_F16 — возврат без лишних копий (или только до mutable)
        if (Build.VERSION.SDK_INT >= 26 &&
            src.config == Bitmap.Config.RGBA_F16 &&
            src.colorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_SRGB)) {
            if (src.isMutable) return src
            val cp = src.copy(Bitmap.Config.RGBA_F16, /*mutable*/ true)
            cp.setColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))
            return cp
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16)
        if (Build.VERSION.SDK_INT >= 26) {
            out.setColorSpace(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))
        }
        if (Build.VERSION.SDK_INT >= 26 && srcCs != null) {
            // Полностью плавающая обработка: читаем float-компоненты и пишем half‑float без 8‑бит квантизации.
            // Конвертация блоком, без getColor на пиксель
            val dst = ColorSpace.get(ColorSpace.Named.LINEAR_SRGB)
            val connector = if (srcCs == dst) null else ColorSpace.connect(srcCs, dst)
            val total = w * h * 4
            val halfOut = HalfBufferPool.obtain(total)
            try {
                when (src.config) {
                    Bitmap.Config.RGBA_F16 -> {
                        // Вход — half-float: читаем блоком и конвертируем через connector
                        val halfIn = HalfBufferPool.obtain(total)
                        try {
                            src.copyPixelsToBuffer(ShortBuffer.wrap(halfIn, 0, total))
                            var p = 0
                            while (p < total) {
                                val r = Half.toFloat(halfIn[p    ])
                                val g = Half.toFloat(halfIn[p + 1])
                                val b = Half.toFloat(halfIn[p + 2])
                                if (connector != null) {
                                    val v = connector.transform(r, g, b)
                                    halfOut[p    ] = Half.toHalf(v[0])
                                    halfOut[p + 1] = Half.toHalf(v[1])
                                    halfOut[p + 2] = Half.toHalf(v[2])
                                } else {
                                    halfOut[p    ] = Half.toHalf(r)
                                    halfOut[p + 1] = Half.toHalf(g)
                                    halfOut[p + 2] = Half.toHalf(b)
                                }
                                halfOut[p + 3] = halfIn[p + 3] // alpha перенесём как есть
                                p += 4
                            }
                        } finally {
                            // Усушка — в внешнем finally
                        }
                    }
                    else -> {
                        // Вход — 8-бит (обычно sRGB). Читаем цельным блоком.
                        val ints = IntArray(w * h)
                        src.copyPixelsToBuffer(IntBuffer.wrap(ints))
                        var p = 0
                        for (c in ints) {
                            val a = Color.alpha(c) / 255f
                            val rN = Color.red(c)   / 255f
                            val gN = Color.green(c) / 255f
                            val bN = Color.blue(c)  / 255f
                            if (connector != null) {
                                val v = connector.transform(rN, gN, bN)
                                halfOut[p++] = Half.toHalf(v[0])
                                halfOut[p++] = Half.toHalf(v[1])
                                halfOut[p++] = Half.toHalf(v[2])
                            } else {
                                // sRGB → linear sRGB по формуле
                                halfOut[p++] = Half.toHalf(srgbToLinear(rN))
                                halfOut[p++] = Half.toHalf(srgbToLinear(gN))
                                halfOut[p++] = Half.toHalf(srgbToLinear(bN))
                            }
                            halfOut[p++] = Half.toHalf(a)
                        }
                    }
                }
                out.copyPixelsFromBuffer(ShortBuffer.wrap(halfOut, 0, total))
                Logger.i("COLOR", "gamut.convert", mapOf("src" to srcCs.name, "dst" to "Linear sRGB (F16)"))
            } finally {
                // Всегда подрезаем пул, даже при исключениях
                HalfBufferPool.trimIfOversized()
            }
        } else {
            // Fallback (<26 или нет ColorSpace): считаем, что вход — sRGB 8‑бит и переводим в линейку.
            val total = w * h * 4
            val half = HalfBufferPool.obtain(total)
            try {
                val ints = IntArray(w * h)
                src.copyPixelsToBuffer(IntBuffer.wrap(ints))
                var p = 0
                for (c in ints) {
                    val a = Color.alpha(c) / 255f
                    val r = srgbToLinear(Color.red(c) / 255f)
                    val g = srgbToLinear(Color.green(c) / 255f)
                    val b = srgbToLinear(Color.blue(c) / 255f)
                    half[p++] = Half.toHalf(r)
                    half[p++] = Half.toHalf(g)
                    half[p++] = Half.toHalf(b)
                    half[p++] = Half.toHalf(a)
                }
                out.copyPixelsFromBuffer(ShortBuffer.wrap(half, 0, total))
            } finally {
                // Подрезка слишком больших буферов — всегда
                HalfBufferPool.trimIfOversized()
            }
            Logger.w("COLOR", "gamut.assume_srgb", mapOf("dst" to "Linear sRGB (F16)"))
        }
        return out
    }

    /** sRGB → linear sRGB */
    fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    /** linear sRGB → sRGB */
    fun linearToSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    // ===== OKLab (используем позже для палитры; сейчас — утилиты) =====
    data class OKLab(val L: Float, val a: Float, val b: Float)

    fun rgbLinearToOKLab(r: Float, g: Float, b: Float): OKLab {
        // https://bottosson.github.io/posts/oklab/
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val l_ = cbrtF(l); val m_ = cbrtF(m); val s_ = cbrtF(s)
        val L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
        val A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
        val B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        return OKLab(L, A, B)
    }

    fun oklabToRgbLinear(L: Float, A: Float, B: Float): FloatArray {
        val l_ = L + 0.3963377774f * A + 0.2158037573f * B
        val m_ = L - 0.1055613458f * A - 0.0638541728f * B
        val s_ = L - 0.0894841775f * A - 1.2914855480f * B
        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_
        val r = +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return floatArrayOf(r, g, b)
    }

    // Кубический корень с сохранением знака: для отрицательных линейных яркостей OKLab.
    private fun cbrtF(x: Float): Float = when {
        x == 0f -> 0f
        x > 0f -> x.pow(1f / 3f)
        else -> -(-x).pow(1f / 3f)
    }
}