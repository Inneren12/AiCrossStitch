package com.appforcross.editor.filters

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import com.appforcross.editor.logging.Logger
import kotlin.math.abs
import android.util.Half
import java.nio.ShortBuffer
import kotlin.math.abs
import com.appforcross.editor.util.HalfBufferPool

/** Оценка блокинга JPEG и лёгкий deblocking до остальных шагов. */
object Deblocking8x8 {

    data class Blockiness(val vertical: Float, val horizontal: Float, val mean: Float)

    /** Простейшая метрика блокинга по границам 8x8 по люме (linear RGB). */
    @SuppressLint("HalfFloat")
    fun measureBlockinessLinear(bitmap: Bitmap): Blockiness {
        val w = bitmap.width
        val h = bitmap.height
        val total = w * h * 4
        val half = HalfBufferPool.obtain(total)
        val sb = ShortBuffer.wrap(half, 0, total)
        bitmap.copyPixelsToBuffer(sb)
        var vSum = 0.0; var vCnt = 0
        var hSum = 0.0; var hCnt = 0
        // Вертикальные границы x=8,16,...
        for (y in 0 until h) {
            var x = 8
            while (x < w) {
                val iR = ((y * w + (x - 1)) * 4)
                val iL = ((y * w + x) * 4)
                val l0 = 0.2126f*Half.toFloat(half[iR    ]) + 0.7152f*Half.toFloat(half[iR+1]) + 0.0722f*Half.toFloat(half[iR+2])
                val l1 = 0.2126f*Half.toFloat(half[iL    ]) + 0.7152f*Half.toFloat(half[iL+1]) + 0.0722f*Half.toFloat(half[iL+2])
                vSum += abs((l1 - l0).toDouble()); vCnt++; x += 8
            }
        }
        // Горизонтальные границы y=8,16,...
        var yb = 8
        while (yb < h) {
            val rowT = (yb - 1) * w
            val rowB = yb * w
            var x = 0
            while (x < w) {
                val iT = ((rowT + x) * 4)
                val iB = ((rowB + x) * 4)
                val l0 = 0.2126f*Half.toFloat(half[iT    ]) + 0.7152f*Half.toFloat(half[iT+1]) + 0.0722f*Half.toFloat(half[iT+2])
                val l1 = 0.2126f*Half.toFloat(half[iB    ]) + 0.7152f*Half.toFloat(half[iB+1]) + 0.0722f*Half.toFloat(half[iB+2])
                hSum += abs((l1 - l0).toDouble()); hCnt++; x++
            }
            yb += 8
        }
        HalfBufferPool.trimIfOversized()
        val v = if (vCnt > 0) (vSum / vCnt).toFloat() else 0f
        val hmean = if (hCnt > 0) (hSum / hCnt).toFloat() else 0f
        return Blockiness(v, hmean, (v + hmean) * 0.5f)
    }

    /** Мягкое сглаживание вдоль границ 8x8 (по 1–2 пикселя по обе стороны). */
    fun weakDeblockInPlaceLinear(bitmap: Bitmap, strength: Float = 0.5f) {
        val w = bitmap.width
        val h = bitmap.height
        val total = w * h * 4
        val half = HalfBufferPool.obtain(total)
        val sb = ShortBuffer.wrap(half, 0, total)
        bitmap.copyPixelsToBuffer(sb)
        sb.rewind()
        // Вертикальные границы
        for (y in 0 until h) {
            var x = 8
            while (x < w) {
                val iL = ((y * w + (x - 1)) * 4)
                val iR = ((y * w + x) * 4)
                val rAvg = (Half.toFloat(half[iL    ]) + Half.toFloat(half[iR    ])) * 0.5f
                val gAvg = (Half.toFloat(half[iL + 1]) + Half.toFloat(half[iR + 1])) * 0.5f
                val bAvg = (Half.toFloat(half[iL + 2]) + Half.toFloat(half[iR + 2])) * 0.5f
                val aAvg = (Half.toFloat(half[iL + 3]) + Half.toFloat(half[iR + 3])) * 0.5f
                fun mix(ch: Float, avg: Float) = (ch + (avg - ch) * strength).coerceIn(0f, 1f)
                half[iL    ] = Half.toHalf(mix(Half.toFloat(half[iL    ]), rAvg))
                half[iL + 1] = Half.toHalf(mix(Half.toFloat(half[iL + 1]), gAvg))
                half[iL + 2] = Half.toHalf(mix(Half.toFloat(half[iL + 2]), bAvg))
                half[iL + 3] = Half.toHalf(mix(Half.toFloat(half[iL + 3]), aAvg))
                half[iR    ] = Half.toHalf(mix(Half.toFloat(half[iR    ]), rAvg))
                half[iR + 1] = Half.toHalf(mix(Half.toFloat(half[iR + 1]), gAvg))
                half[iR + 2] = Half.toHalf(mix(Half.toFloat(half[iR + 2]), bAvg))
                half[iR + 3] = Half.toHalf(mix(Half.toFloat(half[iR + 3]), aAvg))
                x += 8
            }
        }
        // Горизонтальные границы
        var yb = 8
        while (yb < h) {
            val rowT = (yb - 1) * w
            val rowB = yb * w
            var x = 0
            while (x < w) {
                val iT = ((rowT + x) * 4)
                val iB = ((rowB + x) * 4)
                val rAvg = (Half.toFloat(half[iT    ]) + Half.toFloat(half[iB    ])) * 0.5f
                val gAvg = (Half.toFloat(half[iT + 1]) + Half.toFloat(half[iB + 1])) * 0.5f
                val bAvg = (Half.toFloat(half[iT + 2]) + Half.toFloat(half[iB + 2])) * 0.5f
                val aAvg = (Half.toFloat(half[iT + 3]) + Half.toFloat(half[iB + 3])) * 0.5f
                fun mix(ch: Float, avg: Float) = (ch + (avg - ch) * strength).coerceIn(0f, 1f)
                half[iT    ] = Half.toHalf(mix(Half.toFloat(half[iT    ]), rAvg))
                half[iT + 1] = Half.toHalf(mix(Half.toFloat(half[iT + 1]), gAvg))
                half[iT + 2] = Half.toHalf(mix(Half.toFloat(half[iT + 2]), bAvg))
                half[iT + 3] = Half.toHalf(mix(Half.toFloat(half[iT + 3]), aAvg))
                half[iB    ] = Half.toHalf(mix(Half.toFloat(half[iB    ]), rAvg))
                half[iB + 1] = Half.toHalf(mix(Half.toFloat(half[iB + 1]), gAvg))
                half[iB + 2] = Half.toHalf(mix(Half.toFloat(half[iB + 2]), bAvg))
                half[iB + 3] = Half.toHalf(mix(Half.toFloat(half[iB + 3]), aAvg))
                x++
            }
            yb += 8
        }
        sb.rewind()
        bitmap.copyPixelsFromBuffer(sb)
        HalfBufferPool.trimIfOversized()
    }
}