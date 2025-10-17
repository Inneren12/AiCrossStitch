package com.appforcross.quant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import com.appforcross.editor.analysis.AnalyzeResult
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.preset.PresetGateResult
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/** Оркестратор квантизации + дизеринга поверх результата PreScale. */
object QuantizeRunner {
    data class Options(
        val kStart: Int = 16,
        val kMax: Int = 32,
        val deltaEMin: Double = 3.0,
        val orderedAmpSkyFlat: Float = 0.28f,
        val orderedAmpSkin: Float = 0.20f,
        val fsCapHiTex: Float = 0.67f,
        val useOrdered: Boolean = true,
        val useFS: Boolean = true
    )
    data class Output(
        val colorPng: String,
        val indexBin: String,
        val paletteJson: String,
        val k: Int,
        val palette: IntArray,
        val deMin: Double,
        val deMed: Double,
        val avgErr: Double
    )

    /**
     * @param preScalePng путь к результату PreScaleRunner (PNG, sRGB)
     * @param analyze      Stage-3 результат (маски) — будут ресемплены в размер PreScale
     */
    fun run(
        ctx: Context,
        preScalePng: String,
        analyze: AnalyzeResult,
        gate: PresetGateResult,
        opt: Options = Options()
    ): Output {
        require(opt.kStart >= 1) { "kStart must be at least 1" }
        require(opt.kMax >= opt.kStart) { "kMax must be >= kStart" }

        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeFile(preScalePng, decodeOpts)
            ?: error("Cannot decode PreScale PNG: $preScalePng")
        val bmp = if (decoded.isMutable && (Build.VERSION.SDK_INT < 26 || decoded.config != Bitmap.Config.HARDWARE)) {
            decoded
        } else {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, true)
            decoded.recycle()
            copy
        }
        val w=bmp.width; val h=bmp.height
        Logger.i("QUANT", "start", mapOf("w" to w, "h" to h, "png" to preScalePng, "kMax" to opt.kMax))

        // Ресемплим маски (Stage-3 были на превью), приводим к размеру bmp
        val masks = resampleMasks(analyze.masks, w, h)

        // Ordered-дизер для Sky/Flat/Skin: как предскринер (по амплитуде из пресета/опций)
        var working = bmp
        if (opt.useOrdered) {
            val ampSkyFlat = opt.orderedAmpSkyFlat
            val ampSkin = opt.orderedAmpSkin
            // применим малую амплитуду на всем изображении, а потом смешаем по маскам
            val odAll = com.appforcross.editor.quant.Dither.ordered(working, ampSkyFlat)
            val odSkin = if (ampSkin == ampSkyFlat) odAll else com.appforcross.editor.quant.Dither.ordered(working, ampSkin)
            working = mixByMasks(working, odAll, masks.sky, 1f)
            working = mixByMasks(working, odAll, masks.flat, 0.7f)
            working = mixByMasks(working, odSkin, masks.skin, 1f)
            if (ampSkin == ampSkyFlat) {
                odAll.recycle()
            } else {
                odAll.recycle()
                odSkin.recycle()
            }
        }

        // 1) Палитра Greedy+ и квантизация
        val qres = PaletteQuant.run(
            src = working,
            masks = masks,
            opts = QuantOptions(kStart = opt.kStart, kMax = opt.kMax, deltaEMin = opt.deltaEMin)
        )
        Logger.i("QUANT", "palette", mapOf(
            "k" to qres.metrics.k, "deMin" to "%.2f".format(qres.metrics.deMin),
            "deMed" to "%.2f".format(qres.metrics.deMed), "avgErr" to "%.3f".format(qres.metrics.avgErr)
        ))

        // 2) FS‑дизер на HiTex (edge-aware), если включен
        val pal = qres.palette
        // Индексы финального изображения
        val idxFinal: IntArray = if (opt.useFS) {
            val edgeMask = bitmapToEdges(working)
            // Надёжно: получаем сразу индексы 0..k-1
            Dither.floydSteinbergIndex(working, pal, edgeMask, cap = opt.fsCapHiTex)
        } else {
            // простое ближайшее (без FS)
            val n = w*h; val idx = IntArray(n)
            val row = IntArray(w)
            var i=0
            for (y in 0 until h) {
                working.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    idx[i++] = nearestIndexColor(row[x], pal)
                }
            }
            idx
        }

        // 3) Сохранения: цветной PNG, индексная карта (bin), палитра (json)
        val colorOut = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        run {
            var p = 0
            for (y in 0 until h) for (x in 0 until w) {
                val pi = idxFinal[p++].coerceIn(0, pal.lastIndex)
                colorOut.setPixel(x, y, pal[pi])
            }
        }
        val suffix = UUID.randomUUID().toString()
        val outColor = File(ctx.cacheDir, "quant_color_$suffix.png")
        FileOutputStream(outColor).use { colorOut.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // не рециклим colorOut раньше времени — его ещё используем ниже для индекса
        colorOut.recycle()
        // индексы: гарантированно 0..(pal.size-1), без LUT и выхода за границы
        val indexBin = File(ctx.cacheDir, "index_$suffix.bin")
        FileOutputStream(indexBin).use { fos ->
            val buf = ByteArray(w * h)
            var pos = 0
            for (i in 0 until idxFinal.size) {
                val pi = idxFinal[i].coerceIn(0, pal.lastIndex)
                buf[pos++] = pi.toByte()
            }
            // pos всегда == w*h, но на всякий случай — только записанное
            fos.write(buf, 0, pos)
        }

        val palJson = File(ctx.cacheDir, "palette_$suffix.json")
        FileOutputStream(palJson).use { fos ->
            val sb = StringBuilder()
            sb.append("{\"k\":").append(pal.size).append(",\"colors\":[")
            pal.forEachIndexed { idx, c ->
                if (idx>0) sb.append(',')
                sb.append("\"#")
                sb.append("%02X%02X%02X".format(Color.red(c), Color.green(c), Color.blue(c)))
                sb.append("\"")
            }
            sb.append("]}")
            fos.write(sb.toString().toByteArray())
        }

        // Диаг‑копии в сессию
        try {
            DiagnosticsManager.currentSessionDir(ctx)?.let { dir ->
                outColor.copyTo(File(dir, outColor.name), overwrite = true)
                indexBin.copyTo(File(dir, indexBin.name), overwrite = true)
                palJson.copyTo(File(dir, palJson.name), overwrite = true)
            }
        } catch (_: Exception) {}

        Logger.i("QUANT", "done", mapOf(
            "colorPng" to outColor.absolutePath,
            "indexBin" to indexBin.absolutePath,
            "palette" to palJson.absolutePath
        ))
        bmp.recycle()
        recycleMasks(masks)
        return Output(
            colorPng = outColor.absolutePath,
            indexBin = indexBin.absolutePath,
            paletteJson = palJson.absolutePath,
            k = qres.metrics.k,
            palette = pal,
            deMin = qres.metrics.deMin,
            deMed = qres.metrics.deMed,
            avgErr = qres.metrics.avgErr
        )
    }

    /** Ищем ближайший индекс палитры по евклиду в sRGB (быстро). */
    private fun nearestIndexColor(color: Int, palette: IntArray): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        val r0 = Color.red(color)
        val g0 = Color.green(color)
        val b0 = Color.blue(color)
        // палитра гарантированно не пустая
        for (i in 0 until palette.size) {
            val c = palette[i]
            val dr = r0 - Color.red(c)
            val dg = g0 - Color.green(c)
            val db = b0 - Color.blue(c)
            val d = dr*dr + dg*dg + db*db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // -------- utils --------
    private fun resampleMasks(m: com.appforcross.editor.analysis.Masks, w:Int, h:Int): com.appforcross.editor.analysis.Masks {
        fun scaleToAlpha(src: Bitmap): Bitmap {
            val scaled = if (src.width == w && src.height == h) {
                src
            } else {
                Bitmap.createScaledBitmap(src, w, h, /* filter = */ false)
            }
            val shouldRecycle = scaled !== src
            if (scaled.config == Bitmap.Config.ALPHA_8 && scaled.isMutable && shouldRecycle) return scaled
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val row = IntArray(w)
            for (y in 0 until h) {
                scaled.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val a = row[x] ushr 24
                    row[x] = (a shl 24)
                }
                out.setPixels(row, 0, w, 0, y, w, 1)
            }
            if (shouldRecycle) {
                scaled.recycle()
            }
            return out
        }
        return com.appforcross.editor.analysis.Masks(
            edge = scaleToAlpha(m.edge),
            flat = scaleToAlpha(m.flat),
            hiTexFine = scaleToAlpha(m.hiTexFine),
            hiTexCoarse = scaleToAlpha(m.hiTexCoarse),
            skin = scaleToAlpha(m.skin),
            sky = scaleToAlpha(m.sky)
        )
    }
    private fun mixByMasks(base: Bitmap, over: Bitmap, mask: Bitmap, strength: Float): Bitmap {
        val w=base.width; val h=base.height
        val rowBase=IntArray(w); val rowOver=IntArray(w); val rowMask=IntArray(w)
        for (y in 0 until h) {
            base.getPixels(rowBase,0,w,0,y,w,1)
            over.getPixels(rowOver,0,w,0,y,w,1)
            mask.getPixels(rowMask,0,w,0,y,w,1)
            for (x in 0 until w) {
                val alpha = (rowMask[x] ushr 24) / 255f * strength
                if (alpha > 0f) {
                    val src = rowBase[x]
                    val dst = rowOver[x]
                    val nr = Color.red(src) + ((Color.red(dst) - Color.red(src)) * alpha).roundToInt()
                    val ng = Color.green(src) + ((Color.green(dst) - Color.green(src)) * alpha).roundToInt()
                    val nb = Color.blue(src) + ((Color.blue(dst) - Color.blue(src)) * alpha).roundToInt()
                    val na = Color.alpha(src)
                    rowBase[x] = Color.argb(
                        na,
                        nr.coerceIn(0, 255),
                        ng.coerceIn(0, 255),
                        nb.coerceIn(0, 255)
                    )
                }
            }
            base.setPixels(rowBase,0,w,0,y,w,1)
        }
        return base
    }
    private fun bitmapToEdges(bmp: Bitmap): BooleanArray {
        val w=bmp.width; val h=bmp.height
        val out = BooleanArray(w*h)
        val row=IntArray(w)
        for (y in 1 until h-1) {
            bmp.getPixels(row,0,w,0,y,w,1)
            for (x in 1 until w-1) {
                val c = row[x]
                val p = row[x-1]
                val l = 0.2126f*Color.red(c)/255f + 0.7152f*Color.green(c)/255f + 0.0722f*Color.blue(c)/255f
                val lp= 0.2126f*Color.red(p)/255f + 0.7152f*Color.green(p)/255f + 0.0722f*Color.blue(p)/255f
                out[y*w+x] = kotlin.math.abs(l-lp) > 0.04f
            }
        }
        return out
    }

    private fun recycleMasks(m: com.appforcross.editor.analysis.Masks) {
        m.edge.recycle()
        m.flat.recycle()
        m.hiTexFine.recycle()
        m.hiTexCoarse.recycle()
        m.skin.recycle()
        m.sky.recycle()
    }
}
