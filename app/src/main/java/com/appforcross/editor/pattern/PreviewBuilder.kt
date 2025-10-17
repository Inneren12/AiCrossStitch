package com.appforcross.editor.pattern

import android.graphics.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.logging.LogcatKV
object PreviewBuilder {
        /**
         * Быстрое превью: quant_color.png как подложка + сетка (тонкая/жирная).
         * Без символов: быстро и детерминировано.
         *
         * @param quantColorPath путь к quant_color.png (из S7)
         * @param outPath        куда писать pattern_preview.png
         * @param cellPx         размер клетки в пикселях (минимальный визуально комфортный 4–8)
         * @param boldEvery      каждая N-ая жирная линия
         * * @param maxSidePx      ограничение размера итогового превью (защита от OOM)
         */
        fun fromQuantColor(
                quantColorPath: String,
                outPath: String,
                cellPx: Int = 6,
                boldEvery: Int = 10,
                maxSidePx: Int = 2800
        ): Boolean {
            Logger.i("PREVIEW", "build.start", mapOf(
                "src" to quantColorPath,
                "dst" to outPath,
                "cellPx" to cellPx,
                "boldEvery" to boldEvery,
                "maxSidePx" to maxSidePx
            ))
            LogcatKV.i("PREVIEW", "build.start", mapOf(
                "src" to quantColorPath, "dst" to outPath,
                "cellPx" to cellPx, "boldEvery" to boldEvery, "maxSidePx" to maxSidePx
            ))
            val src = BitmapFactory.decodeFile(quantColorPath) ?: return false
            try {
                val w = src.width
                val h = src.height
                val outW = (w * cellPx).coerceAtLeast(1)
                val outH = (h * cellPx).coerceAtLeast(1)
                val scale = min(1f, min(maxSidePx.toFloat() / outW, maxSidePx.toFloat() / outH))
                val dstW = maxOf(1, (outW * scale).toInt())
                val dstH = maxOf(1, (outH * scale).toInt())
                val sizeMeta = mapOf("srcW" to w, "srcH" to h, "dstW" to dstW, "dstH" to dstH, "scale" to scale)
                Logger.i("PREVIEW", "build.size", sizeMeta)
                LogcatKV.i("PREVIEW", "build.size", sizeMeta)

                val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)

                // 1) Подложка — nearest neighbor (без размытия), чтобы клетки были «квадратные»
                val pImg = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
                c.drawBitmap(src, null, Rect(0, 0, dstW, dstH), pImg)

                // 2) Сетка (тонкие и жирные линии)
                val thin = Paint().apply { color = 0x40FFFFFF; strokeWidth = 1f }
                val bold = Paint().apply { color = 0x80FF4040.toInt(); strokeWidth = maxOf(1f, 1.5f * scale) }
                val step = cellPx * scale
                val gridMeta = mapOf("stepPx" to step, "boldEvery" to boldEvery)
                Logger.i("PREVIEW", "build.grid", gridMeta)
                LogcatKV.i("PREVIEW", "build.grid", gridMeta)

                var x = 0f
                var i = 0
                while (x <= dstW) {
                    c.drawLine(x, 0f, x, dstH.toFloat(), if (i % boldEvery == 0) bold else thin)
                    x += step
                    i++
                }
                var y = 0f
                var j = 0
                while (y <= dstH) {
                    c.drawLine(0f, y, dstW.toFloat(), y, if (j % boldEvery == 0) bold else thin)
                    y += step
                    j++
                }

                File(outPath).parentFile?.mkdirs()
                FileOutputStream(File(outPath)).use { outStream ->
                    out.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                }
                val doneMeta = mapOf("dst" to outPath)
                Logger.i("PREVIEW", "build.done", doneMeta)
                LogcatKV.i("PREVIEW", "build.done", doneMeta)

                return true
            } finally {
                src.recycle()
            }
    }
}
