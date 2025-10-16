package com.appforcross.editor.analysis

import android.content.Context
import android.graphics.*
import android.net.Uri
import com.appforcross.editor.io.Decoder
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.diagnostics.DiagnosticsManager
import kotlin.math.*

/** Результаты Stage-3: превью, маски, метрики и классификация. */
data class AnalyzeResult(
    val preview: Bitmap,
    val metrics: Metrics,
    val masks: Masks,
    val decision: SceneDecision
    )

data class Masks(
    val edge: Bitmap,          // 8-бит, белое = кромка
    val flat: Bitmap,          // 8-бит, белое = низкая текстурность
    val hiTexFine: Bitmap,     // 8-бит, белое = мелкая фактура
    val hiTexCoarse: Bitmap,   // 8-бит, белое = крупная фактура
    val skin: Bitmap,          // 8-бит маска кожи
    val sky: Bitmap            // 8-бит маска неба
    )

data class Metrics(
    val width: Int, val height: Int,
    val lMed: Double,              // медиана яркости (linear luma)
    val drP99minusP1: Double,      // перцентильный диапазон
    val satLoPct: Double,          // доля тёмных клипов
    val satHiPct: Double,          // доля светлых клипов
    val castOK: Double,            // sqrt(mean(A^2)+mean(B^2)) в OKLab (оценка оттеночного сдвига)
    val noiseY: Double,            // MAD по Лапласу (люма)
    val noiseC: Double,            // MAD по OKLab A/B
    val edgeRate: Double,          // доля пикселей с |∇L| > T
    val varLap: Double,            // дисперсия Лапласиана (резкость)
    val hazeScore: Double,         // тёмный канал (ниже = больше дымки)
    val flatPct: Double,           // доля плоских областей
    val gradP95Sky: Double,        // 95-й перцентиль ∥∇L∥ в sky-маске
    val gradP95Skin: Double,       // 95-й перцентиль ∥∇L∥ в skin-маске
    val colors5bit: Int,           // число уникальных цветов в 5-бит кванте
    val top8Coverage: Double,      // доля пикселей топ-8 цветов
    val checker2x2: Double         // доля «шахматных» 2×2 (признак пиксель-арта)
    )

enum class SceneKind { PHOTO, DISCRETE }
data class SceneDecision(val kind: SceneKind, val subtype: String?, val confidence: Double)

object Stage3Analyze {
    private const val PREVIEW_LONG_SIDE = 1024

    /** Точка входа: строим превью, считаем маски и метрики, выдаём классификацию. */
    fun run(ctx: Context, uri: Uri): AnalyzeResult {
        // 0) Декодим исходник (с EXIF-поворотом) и строим превью ≤1024
        val dec = Decoder.decodeUri(ctx, uri)
        val preview = buildPreview(dec.bitmap, PREVIEW_LONG_SIDE)
        Logger.i("ANALYZE", "preview.built", mapOf("w" to preview.width, "h" to preview.height))

        // 1) Подготовка плоскостей (linear luma + OKLab A/B для шума/каста)
        val planes = toWorkingPlanes(preview)

        // 2) Градиенты, Лаплас, ориентации
        val sob = sobel(planes.luma)
        val lap = laplacian(planes.luma)

        // 3) Маски
        val edgeMask = buildEdgeMask(sob.mag, preview.width, preview.height)
        val (var7, var3, var9) = localVariance3_7_9(planes.luma, preview.width, preview.height)
        val flatMask = buildFlatMask(var7, preview.width, preview.height)
        val hiTexFine = thresholdMask(var3, quantile(var3, 0.70), preview.width, preview.height)
        val hiTexCoarse = thresholdMask(var9, quantile(var9, 0.70), preview.width, preview.height)
        val skinMask = skinMask(preview)
        val skyMask = skyMask(preview, var7)

        val masks = Masks(
            edge = maskToBitmap(edgeMask, preview.width, preview.height),
            flat = maskToBitmap(flatMask, preview.width, preview.height),
            hiTexFine = maskToBitmap(hiTexFine, preview.width, preview.height),
            hiTexCoarse = maskToBitmap(hiTexCoarse, preview.width, preview.height),
            skin = maskToBitmap(skinMask, preview.width, preview.height),
            sky = maskToBitmap(skyMask, preview.width, preview.height)
        )
        Logger.i("ANALYZE", "masks.coverage", mapOf(
            "edge_pct" to maskPct(edgeMask),
            "flat_pct" to maskPct(flatMask),
            "hitex_fine_pct" to maskPct(hiTexFine),
            "hitex_coarse_pct" to maskPct(hiTexCoarse),
            "skin_pct" to maskPct(skinMask),
            "sky_pct" to maskPct(skyMask)
        ))

        // 4) Метрики
        val l = planes.luma
        val lMed = median(l)
        val dr = percentile(l, 0.99) - percentile(l, 0.01)
        val satLo = l.count { it <= 0.01f }.toDouble() / l.size
        val satHi = l.count { it >= 0.99f }.toDouble() / l.size
        val cast = sqrt(planes.okA.map { it*it }.average() + planes.okB.map { it*it }.average())
        val noiseY = madAbs(lap)                                     // шум люмы
        val noiseC = 0.5 * (madAbs(planes.okA) + madAbs(planes.okB)) // шум хромы (OKLab A/B)
        val edgeRate = maskPct(edgeMask)
        val varLap = variance(lap)
        val haze = 1.0 - darkChannelScore(preview) // выше = меньше дымки; нам удобнее «обратная» шкала
        val flatPct = maskPct(flatMask)
        val gradP95Sky = percentileMasked(sob.mag, skyMask, 0.95)
        val gradP95Skin = percentileMasked(sob.mag, skinMask, 0.95)
        val (colors5, top8cov) = colors5bitAndTop8(preview)
        val checker = checker2x2(preview)

        val metrics = Metrics(
            preview.width, preview.height,
            lMed.toDouble(), dr.toDouble(),
            satLo, satHi, cast,
            noiseY, noiseC, edgeRate, varLap,
            haze, flatPct, gradP95Sky, gradP95Skin,
            colors5, top8cov, checker
        )
        Logger.i("ANALYZE", "metrics", mapOf(
            "L_med" to metrics.lMed, "DR" to metrics.drP99minusP1,
            "SatLo" to metrics.satLoPct, "SatHi" to metrics.satHiPct,
            "CastOK" to "%.3f".format(metrics.castOK),
            "NoiseY" to "%.3f".format(metrics.noiseY),
            "NoiseC" to "%.3f".format(metrics.noiseC),
            "EdgeRate" to "%.3f".format(metrics.edgeRate),
            "VarLap" to "%.3f".format(metrics.varLap),
            "HazeScore" to "%.3f".format(metrics.hazeScore),
            "FlatPct" to "%.3f".format(metrics.flatPct),
            "GradP95_sky" to "%.3f".format(metrics.gradP95Sky),
            "GradP95_skin" to "%.3f".format(metrics.gradP95Skin),
            "Colors5" to metrics.colors5bit,
            "Top8Cov" to "%.3f".format(metrics.top8Coverage),
            "Checker2x2" to "%.3f".format(metrics.checker2x2)
        ))

        // 5) Классификация сцены
        val decision = classify(metrics)
        Logger.i("ANALYZE", "scene.decision", mapOf(
            "kind" to decision.kind.name, "subtype" to (decision.subtype ?: "-"),
            "confidence" to "%.2f".format(decision.confidence)
        ))

        // 6) Сохраняем маски в diag/ (удобно для проверки)
        try {
            DiagnosticsManager.currentSessionDir(ctx)?.let { dir ->
                savePng(masks.edge, File(dir, "mask_edge.png"))
                savePng(masks.flat, File(dir, "mask_flat.png"))
                savePng(masks.hiTexFine, File(dir, "mask_hitex_fine.png"))
                savePng(masks.hiTexCoarse, File(dir, "mask_hitex_coarse.png"))
                savePng(masks.skin, File(dir, "mask_skin.png"))
                savePng(masks.sky, File(dir, "mask_sky.png"))
                savePng(preview, File(dir, "preview.png"))
            }
        } catch (_: Exception) { /* diag-сохранение опционально */ }

        return AnalyzeResult(preview, metrics, masks, decision)
    }

    // -------- Превью ----------
    private fun buildPreview(src: Bitmap, longSide: Int): Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val scale = if (sw >= sh) longSide / sw else longSide / sh
        if (scale >= 1f) return src.copy(Bitmap.Config.ARGB_8888, false)
        val w = max(1, (sw * scale).roundToInt())
        val h = max(1, (sh * scale).roundToInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private data class WorkingPlanes(val luma: FloatArray, val okA: FloatArray, val okB: FloatArray)
    private fun toWorkingPlanes(bmp: Bitmap): WorkingPlanes {
        val w = bmp.width; val h = bmp.height; val n = w*h
        val l = FloatArray(n); val a = FloatArray(n); val b = FloatArray(n)
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var idx = y*w
            for (x in 0 until w) {
                val c = row[x]
                val rf = Color.red(c)/255f
                val gf = Color.green(c)/255f
                val bf = Color.blue(c)/255f
                // в линейную яркость
                val r = srgbToLinear(rf); val g = srgbToLinear(gf); val bl = srgbToLinear(bf)
                l[idx] = (0.2126f*r + 0.7152f*g + 0.0722f*bl)
                // OKLab A/B (приближённо, без конвертора)
                val ok = rgbLinearToOKLab(r, g, bl)
                a[idx] = ok[1]; b[idx] = ok[2]
                idx++
            }
        }
        return WorkingPlanes(l, a, b)
    }

    // OKLab из линейного RGB (сокращённая форма)
    private fun rgbLinearToOKLab(r: Float, g: Float, b: Float): FloatArray {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val l_ = cbrtF(l); val m_ = cbrtF(m); val s_ = cbrtF(s)
        val L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
        val A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
        val B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        return floatArrayOf(L, A, B)
    }
    private fun cbrtF(x: Float) = if (x <= 0f) 0f else x.pow(1f/3f)
    private fun srgbToLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    // -------- Градиенты и Лаплас --------
    private data class Sobel(val gx: FloatArray, val gy: FloatArray, val mag: FloatArray)
    private fun sobel(l: FloatArray): Sobel {
        val n = l.size; val w = guessW(l); val h = n / w
        fun idx(x:Int,y:Int)=y*w+x
        val gx = FloatArray(n); val gy = FloatArray(n); val mag=FloatArray(n)
        for (y in 1 until h-1) for (x in 1 until w-1) {
            val a = l[idx(x-1,y-1)]; val b = l[idx(x,y-1)]; val c = l[idx(x+1,y-1)]
            val d = l[idx(x-1,y  )]; val e = l[idx(x,y  )]; val f = l[idx(x+1,y  )]
            val g = l[idx(x-1,y+1)]; val h0= l[idx(x,y+1)]; val i = l[idx(x+1,y+1)]
            val sx = (-a + c) + (-2f*d + 2f*f) + (-g + i)
            val sy = ( a + 2f*b + c) - ( g + 2f*h0 + i)
            val m = sqrt(sx*sx + sy*sy)
            val k = idx(x,y)
            gx[k]=sx; gy[k]=sy; mag[k]=m
        }
        return Sobel(gx, gy, mag)
    }
    private fun laplacian(l: FloatArray): FloatArray {
        val n = l.size; val w = guessW(l); val h = n / w
        fun idx(x:Int,y:Int)=y*w+x
        val out = FloatArray(n)
        for (y in 1 until h-1) for (x in 1 until w-1) {
            val c = l[idx(x,y)]
            val v = l[idx(x-1,y)] + l[idx(x+1,y)] + l[idx(x,y-1)] + l[idx(x,y+1)] - 4f*c
            out[idx(x,y)] = v
        }
        return out
    }
    private fun guessW(arr: FloatArray): Int {
        // Внутренний хак: ширину восстановим по делимости — нам передают только растровые данные
        // Здесь это безопасно: вызываем только с bitmap.width*bitmap.height.
        // Для скорости полагаем, что ширина кратна 2^k. Найдём ближайшую к sqrt(n).
        val n = arr.size
        val s = sqrt(n.toDouble()).roundToInt()
        for (w in s downTo 1) if (n % w == 0) return w
        return s
    }

    // -------- Маски --------
    private fun buildEdgeMask(grad: FloatArray, w: Int, h: Int): BooleanArray {
        val q85 = quantile(grad, 0.85f)
        val thr = max(0.02f, q85 * 0.5f)
        val out = BooleanArray(grad.size)
        for (i in grad.indices) out[i] = grad[i] > thr
        return out
    }
    private fun buildFlatMask(var7: FloatArray, w: Int, h: Int): BooleanArray {
        val tau = quantile(var7, 0.35f)
        val out = BooleanArray(var7.size)
        for (i in var7.indices) out[i] = var7[i] < tau
        return out
    }

    private fun localVariance3_7_9(l: FloatArray, w: Int, h: Int): Triple<FloatArray, FloatArray, FloatArray> {
        fun integral(a: FloatArray): Pair<FloatArray, FloatArray> {
            val S = FloatArray(a.size); val SS = FloatArray(a.size)
            fun id(x:Int,y:Int)=y*w+x
            var rowSum = 0f; var rowSumSq = 0f
            for (y in 0 until h) {
                rowSum = 0f; rowSumSq = 0f
                for (x in 0 until w) {
                    val v = a[id(x,y)]
                    rowSum += v; rowSumSq += v*v
                    val upS = if (y>0) S[id(x,y-1)] else 0f
                    val upSS = if (y>0) SS[id(x,y-1)] else 0f
                    S[id(x,y)] = upS + rowSum
                    SS[id(x,y)] = upSS + rowSumSq
                }
            }
            return S to SS
        }
        fun boxVar(S: FloatArray, SS: FloatArray, r: Int): FloatArray {
            val out = FloatArray(w*h)
            fun id(x:Int,y:Int)=y*w+x
            for (y in 0 until h) for (x in 0 until w) {
                val x0 = (x - r).coerceIn(0, w-1)
                val y0 = (y - r).coerceIn(0, h-1)
                val x1 = (x + r).coerceIn(0, w-1)
                val y1 = (y + r).coerceIn(0, h-1)
                val a = S[id(x1,y1)]
                val b = if (x0>0) S[id(x0-1,y1)] else 0f
                val c = if (y0>0) S[id(x1,y0-1)] else 0f
                val d = if (x0>0 && y0>0) S[id(x0-1,y0-1)] else 0f
                val sum = a - b - c + d
                val a2 = SS[id(x1,y1)]
                val b2 = if (x0>0) SS[id(x0-1,y1)] else 0f
                val c2 = if (y0>0) SS[id(x1,y0-1)] else 0f
                val d2 = if (x0>0 && y0>0) SS[id(x0-1,y0-1)] else 0f
                val sum2 = a2 - b2 - c2 + d2
                val n = (x1-x0+1)*(y1-y0+1)
                val mean = sum / n
                out[id(x,y)] = (sum2 / n) - mean*mean
            }
            return out
        }
        val (S, SS) = integral(l)
        val var3 = boxVar(S, SS, 1)
        val var7 = boxVar(S, SS, 3)
        val var9 = boxVar(S, SS, 4)
        return Triple(var3, var7, var9)
    }

    private fun thresholdMask(field: FloatArray, thr: Float, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(field.size)
        for (i in field.indices) out[i] = field[i] > thr
        return out
    }

    private fun skinMask(bmp: Bitmap): BooleanArray {
        val w=bmp.width; val h=bmp.height; val n=w*h
        val out = BooleanArray(n)
        val row = IntArray(w)
        var i=0
        for (y in 0 until h) {
            bmp.getPixels(row,0,w,0,y,w,1)
            for (x in 0 until w) {
                val c=row[x]
                val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
                val yv = 0.299*r + 0.587*g + 0.114*b
                val cb = (-0.168736*r - 0.331264*g + 0.5*b + 128).toInt()
                val cr = (0.5*r - 0.418688*g - 0.081312*b + 128).toInt()
                val cond = r>95 && g>40 && b>20 &&
                        (max(max(r,g),b) - min(min(r,g),b) > 15) &&
                        abs(r-g) > 15 && r>g && r>b &&
                        (cr in 133..173) && (cb in 77..127)
                out[i++] = cond
            }
        }
        return morphOpenClose(out, w, h, radius=1)
    }

    private fun skyMask(bmp: Bitmap, var7: FloatArray): BooleanArray {
        val w=bmp.width; val h=bmp.height; val n=w*h
        val out = BooleanArray(n)
        val row = IntArray(w)
        var i=0
        val tauTex = quantile(var7, 0.50f) // «не слишком текстурно»
        for (y in 0 until h) {
            bmp.getPixels(row,0,w,0,y,w,1)
            for (x in 0 until w) {
                val c=row[x]
                val rf=Color.red(c)/255f; val gf=Color.green(c)/255f; val bf=Color.blue(c)/255f
                val maxc = max(rf, max(gf,bf)); val minc = min(rf, min(gf,bf))
                val delta = maxc-minc
                val v = maxc
                val s = if (maxc<=1e-6f) 0f else delta/maxc
                var hdeg = 0f
                if (delta>1e-5f) {
                    val hue = when (maxc) {
                        rf -> ((gf - bf) / delta + (if (gf<bf) 6 else 0))
                        gf -> ((bf - rf) / delta + 2)
                        else -> ((rf - gf) / delta + 4)
                    }
                    hdeg = (hue*60f) % 360f
                }
                val idx = i
                val isBlueHue = (hdeg >= 190f && hdeg <= 250f)
                val cond = isBlueHue && s in 0.1f..0.8f && v >= 0.55f && var7[idx] <= tauTex
                out[i++] = cond
            }
        }
        return morphOpenClose(out, w, h, radius=1)
    }

    private fun morphOpenClose(mask: BooleanArray, w:Int, h:Int, radius:Int): BooleanArray {
        fun erode(src:BooleanArray):BooleanArray {
            val out=BooleanArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var ok=true
                loop@ for (dy in -radius..radius) for (dx in -radius..radius) {
                    val xx=(x+dx).coerceIn(0,w-1); val yy=(y+dy).coerceIn(0,h-1)
                    if (!src[yy*w+xx]) { ok=false; break@loop }
                }
                out[y*w+x]=ok
            }
            return out
        }
        fun dilate(src:BooleanArray):BooleanArray {
            val out=BooleanArray(src.size)
            for (y in 0 until h) for (x in 0 until w) {
                var ok=false
                loop@ for (dy in -radius..radius) for (dx in -radius..radius) {
                    val xx=(x+dx).coerceIn(0,w-1); val yy=(y+dy).coerceIn(0,h-1)
                    if (src[yy*w+xx]) { ok=true; break@loop }
                }
                out[y*w+x]=ok
            }
            return out
        }
        // open (erode→dilate) + close (dilate→erode)
        val opened = dilate(erode(mask))
        return erode(dilate(opened))
    }

    // -------- Метрики и утилиты --------
    private fun median(a: FloatArray): Float {
        val copy = a.copyOf(); copy.sort()
        val m = copy.size/2
        return if (copy.size%2==1) copy[m] else 0.5f*(copy[m-1]+copy[m])
    }
    private fun percentile(a: FloatArray, p: Double): Float {
        val copy = a.copyOf(); copy.sort()
        val idx = ((copy.size-1) * p).coerceIn(0.0, (copy.size-1).toDouble())
        val i0 = floor(idx).toInt(); val i1 = ceil(idx).toInt()
        if (i0==i1) return copy[i0]
        val t = (idx - i0)
        return (copy[i0]*(1.0-t) + copy[i1]*t).toFloat()
    }
    private fun quantile(a: FloatArray, p: Float) = percentile(a, p.toDouble())
    private fun variance(a: FloatArray): Double {
        val mean = a.average()
        var acc=0.0
        for (v in a) { val d=v-mean; acc+=d*d }
        return acc/a.size
    }
    private fun madAbs(a: FloatArray): Double {
        val med = median(a)
        val dev = FloatArray(a.size) { abs(a[it]-med) }
        return median(dev).toDouble()
    }
    private fun percentileMasked(field: FloatArray, mask: BooleanArray, p: Double): Double {
        val v = ArrayList<Float>(field.size)
        for (i in field.indices) if (mask[i]) v.add(field[i])
        if (v.isEmpty()) return 0.0
        val arr = v.toFloatArray(); arr.sort()
        val idx = ((arr.size-1) * p).coerceIn(0.0, (arr.size-1).toDouble())
        val i0 = floor(idx).toInt(); val i1 = ceil(idx).toInt()
        return if (i0==i1) arr[i0].toDouble() else (arr[i0]*(1.0-(idx-i0)) + arr[i1]*(idx-i0)).toDouble()
    }
    private fun darkChannelScore(bmp: Bitmap, r:Int=7): Double {
        val w=bmp.width; val h=bmp.height
        val row = IntArray(w)
        var sum=0.0; var n=0
        for (y in 0 until h) {
            bmp.getPixels(row,0,w,0,y,w,1)
            for (x in 0 until w) {
                var mn=1f
                for (dy in -r..r) for (dx in -r..r) {
                    val xx=(x+dx).coerceIn(0,w-1); val yy=(y+dy).coerceIn(0,h-1)
                    val c = if (dx==0 && dy==0) row[x] else bmp.getPixel(xx,yy)
                    val rf=Color.red(c)/255f; val gf=Color.green(c)/255f; val bf=Color.blue(c)/255f
                    val m = min(rf, min(gf,bf))
                    if (m<mn) mn=m
                }
                sum+=mn; n++
            }
        }
        return (sum/n)
    }
    private fun maskToBitmap(mask: BooleanArray, w:Int, h:Int): Bitmap {
        val out = Bitmap.createBitmap(w,h,Bitmap.Config.ALPHA_8)
        val arr = ByteArray(mask.size)
        for (i in mask.indices) arr[i] = if (mask[i]) 0xFF.toByte() else 0x00
        out.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(arr))
        return out
    }
    private fun maskPct(mask: BooleanArray): Double = mask.count { it }.toDouble() / mask.size
    private fun savePng(bmp: Bitmap, file: java.io.File) {
        java.io.FileOutputStream(file).use { fos -> bmp.compress(Bitmap.CompressFormat.PNG, 100, fos) }
    }

    private fun colors5bitAndTop8(bmp: Bitmap): Pair<Int, Double> {
        val w=bmp.width; val h=bmp.height
        val hist = IntArray(1 shl 15) // 5+5+5
        val row = IntArray(w)
        var total=0
        for (y in 0 until h) {
            bmp.getPixels(row,0,w,0,y,w,1)
            for (x in 0 until w) {
                val c=row[x]
                val r=(Color.red(c) shr 3) and 31
                val g=(Color.green(c) shr 3) and 31
                val b=(Color.blue(c) shr 3) and 31
                val code=(r shl 10) or (g shl 5) or b
                hist[code]++
                total++
            }
        }
        var unique=0
        val counts=ArrayList<Int>()
        for (i in hist.indices) if (hist[i]>0){ unique++; counts.add(hist[i]) }
        counts.sortDescending()
        val top=counts.take(8).sum()
        val cov = if (total>0) top.toDouble()/total else 0.0
        return unique to cov
    }

    private fun checker2x2(bmp: Bitmap): Double {
        val w=bmp.width; val h=bmp.height
        if (w<2 || h<2) return 0.0
        val rowA = IntArray(w); val rowB = IntArray(w)
        var cnt=0; var total=0
        for (y in 0 until h-1) {
            bmp.getPixels(rowA,0,w,0,y,w,1)
            bmp.getPixels(rowB,0,w,0,y+1,w,1)
            for (x in 0 until w-1) {
                val a=rowA[x]; val b=rowA[x+1]
                val c=rowB[x]; val d=rowB[x+1]
                total++
                val uniq = hashSetOf(a,b,c,d).size
                if (uniq<=2) cnt++ // «шахматка/квазипаттерн»
            }
        }
        return cnt.toDouble()/total
    }

    // -------- Классификация сцены --------
    private fun classify(m: Metrics): SceneDecision {
        // Стартовые правила (эвристики v0.1)
        var scoreDiscrete = 0
        var votes = 0
        // 1) Мало цветов + высокий охват топ-8 → DISCRETE
        votes++; if (m.colors5bit <= 64 && m.top8Coverage >= 0.70) scoreDiscrete++
        // 2) Пиксель-арт → DISCRETE
        votes++; if (m.checker2x2 >= 0.06 && m.edgeRate >= 0.10) scoreDiscrete++
        // 3) Лайн-арт/слова: ровные кромки + немного цветов
        votes++; if (m.edgeRate >= 0.06 && m.colors5bit <= 128 && m.varLap >= 0.002) scoreDiscrete++
        // 4) Фото-градиенты: высокая энтропия/градиенты → PHOTO
        votes++; if (m.flatPct <= 0.55 && m.colors5bit >= 128) { /* favor photo */ } else scoreDiscrete++
        val conf = abs(scoreDiscrete - (votes - scoreDiscrete)).toDouble()/votes
        val kind = if (scoreDiscrete > votes/2) SceneKind.DISCRETE else SceneKind.PHOTO
        val subtype = when {
            kind==SceneKind.DISCRETE && m.checker2x2 >= 0.06 -> "PIXEL_ART"
            kind==SceneKind.DISCRETE && m.top8Coverage >= 0.70 -> "LOGO/FLAG"
            kind==SceneKind.PHOTO && m.gradP95Skin > m.gradP95Sky && m.flatPct<0.5 -> "PORTRAIT/LIVING"
            else -> null
        }
        return SceneDecision(kind, subtype, conf)
    }
}