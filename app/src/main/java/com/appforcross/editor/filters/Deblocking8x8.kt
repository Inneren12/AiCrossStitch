package com.appforcross.editor.filters

import android.graphics.Bitmap
import android.graphics.Color
import com.appforcross.editor.logging.Logger
import kotlin.math.abs

/** Оценка блокинга JPEG и лёгкий deblocking до остальных шагов. */
object Deblocking8x8 {

    data class Blockiness(val vertical: Float, val horizontal: Float, val mean: Float)

    /** Простейшая метрика блокинга по границам 8x8 по люме (linear RGB). */
    fun measureBlockinessLinear(bitmap: Bitmap): Blockiness {
        val w = bitmap.width
        val h = bitmap.height
        var vSum = 0.0
        var vCnt = 0
        var hSum = 0.0
        var hCnt = 0
        val px = IntArray(w)
        // вертикальные границы (x = 8,16,...)
        var x = 8
        while (x < w) {
            for (y in 0 until h) {
                bitmap.getPixels(px, 0, w, 0, y, w, 1) // читаем строку
                val c1 = Color.valueOf(px[x])
                val c0 = Color.valueOf(px[x - 1])
                val l1 = 0.2126f * c1.red() + 0.7152f * c1.green() + 0.0722f * c1.blue()
                val l0 = 0.2126f * c0.red() + 0.7152f * c0.green() + 0.0722f * c0.blue()
                vSum += abs(l1 - l0).toDouble()
                vCnt++
            }
            x += 8
        }
        // горизонтальные границы (y = 8,16,...)
        var yb = 8
        while (yb < h) {
            bitmap.getPixels(px, 0, w, 0, yb, w, 1)
            val prev = IntArray(w)
            bitmap.getPixels(prev, 0, w, 0, yb - 1, w, 1)
            for (i in 0 until w) {
                val c1 = Color.valueOf(px[i])
                val c0 = Color.valueOf(prev[i])
                val l1 = 0.2126f * c1.red() + 0.7152f * c1.green() + 0.0722f * c1.blue()
                val l0 = 0.2126f * c0.red() + 0.7152f * c0.green() + 0.0722f * c0.blue()
                hSum += abs(l1 - l0).toDouble()
                hCnt++
            }
            yb += 8
        }
        val v = if (vCnt > 0) (vSum / vCnt).toFloat() else 0f
        val hmean = if (hCnt > 0) (hSum / hCnt).toFloat() else 0f
        return Blockiness(v, hmean, (v + hmean) * 0.5f)
    }

    /** Мягкое сглаживание вдоль границ 8x8 (по 1–2 пикселя по обе стороны). */
    fun weakDeblockInPlaceLinear(bitmap: Bitmap, strength: Float = 0.5f) {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        // вертикали
        var x = 8
        while (x < w) {
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                val cL = Color.valueOf(row[x - 1])
                val cR = Color.valueOf(row[x])
                val r = (cL.red() + cR.red()) * 0.5f
                val g = (cL.green() + cR.green()) * 0.5f
                val b = (cL.blue() + cR.blue()) * 0.5f
                val a = (cL.alpha() + cR.alpha()) * 0.5f
                // смешиваем в сторону среднего (strength 0..1)
                val nl = lerpColor(cL, r, g, b, a, strength)
                val nr = lerpColor(cR, r, g, b, a, strength)
                row[x - 1] = nl
                row[x] = nr
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
            x += 8
        }
        // горизонты
        var yb = 8
        while (yb < h) {
            val rowA = IntArray(w)
            val rowB = IntArray(w)
            bitmap.getPixels(rowA, 0, w, 0, yb - 1, w, 1)
            bitmap.getPixels(rowB, 0, w, 0, yb, w, 1)
            for (i in 0 until w) {
                val cA = Color.valueOf(rowA[i])
                val cB = Color.valueOf(rowB[i])
                val r = (cA.red() + cB.red()) * 0.5f
                val g = (cA.green() + cB.green()) * 0.5f
                val b = (cA.blue() + cB.blue()) * 0.5f
                val a = (cA.alpha() + cB.alpha()) * 0.5f
                rowA[i] = lerpColor(cA, r, g, b, a, strength)
                rowB[i] = lerpColor(cB, r, g, b, a, strength)
            }
            bitmap.setPixels(rowA, 0, w, 0, yb - 1, w, 1)
            bitmap.setPixels(rowB, 0, w, 0, yb, w, 1)
            yb += 8
        }
    }

    private fun lerpColor(c0: Color, r: Float, g: Float, b: Float, a: Float, t: Float): Int {
        val nr = c0.red() + (r - c0.red()) * t
        val ng = c0.green() + (g - c0.green()) * t
        val nb = c0.blue() + (b - c0.blue()) * t
        val na = c0.alpha() + (a - c0.alpha()) * t
        return Color.valueOf(nr, ng, nb, na).toArgb()
    }
}