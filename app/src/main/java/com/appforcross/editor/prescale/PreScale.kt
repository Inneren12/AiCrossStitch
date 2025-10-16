package com.appforcross.editor.prescale

import android.graphics.Bitmap
import android.graphics.Color
import com.appforcross.editor.analysis.Masks
import com.appforcross.editor.color.ColorMgmt
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.preset.PresetGateResult
import com.appforcross.editor.preset.PresetSpec
import com.appforcross.editor.preset.ScaleFilter
import kotlin.math.*

object PreScale {
    data class FR(
        val ssim: Double,
        val edgeKeep: Double,
        val banding: Double,
        val de95: Double
    )
    data class Result(
        val out: Bitmap,
        val wst: Int,
        val hst: Int,
        val fr: FR,
        val passed: Boolean
    )

    /**
     * Выполнить пред‑скейл:
     * 1) адресные фильтры (NR / texture / unify / pre‑USM);
     * 2) AA префильтр σ(r);
     * 3) ресемпл (Lanczos3/Mitchell) с micro‑phase;
     * 4) FR‑метрики (SSIM / EdgeKeep / Banding / ΔE95);
     * 5) пост‑обработка (деринг + лок. контраст).
     *
     * @param linearF16   вход в линейном sRGB (RGBA_F16) из Stage‑2
     * @param preset      выбранный PresetSpec (из Stage‑4)
     * @param norm        sigmaBase/edge/flat, нормализованные под r (из Stage‑4)
     * @param masksPrev   маски Stage‑3 (в превью‑размере) — будут апскейлены
     * @param targetWst   целевая ширина в стежках
     */
    fun run(
        linearF16: Bitmap,
        preset: PresetSpec,
        norm: PresetGateResult.Normalized,
        masksPrev: Masks,
        targetWst: Int
    ): Result {
        // Толерантность: если вдруг не RGBA_F16 — конвертируем (лучше, чем упасть).
        val src0: Bitmap = if (linearF16.config == Bitmap.Config.RGBA_F16) {
            linearF16
        } else {
                Logger.w("PRESCALE", "force.to_f16", mapOf("from" to linearF16.config.toString()))
            // Полагаем sRGB и переводим в линейный F16
            val w = linearF16.width; val h = linearF16.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16)
            val row = IntArray(w)
            for (y in 0 until h) {
                linearF16.getPixels(row, 0, w, 0, y, w, 1)
                out.setPixels(row, 0, w, 0, y, w, 1)
            }
            out
            }
        val w = src0.width
        val h = src0.height
        // вычислим целевую высоту в стежках с сохранением пропорций
        val r = w.toDouble() / targetWst.toDouble()
        val targetHst = max(1, (h / r).roundToInt())
        Logger.i("PRESCALE", "params", mapOf("wpx" to w, "hpx" to h, "Wst" to targetWst, "Hst" to targetHst, "r" to r))

        // 0) Апскейлим маски превью к исходному размеру
        val mEdgeSrc = upscaleMaskTo(src0, masksPrev.edge)
        val mFlatSrc = upscaleMaskTo(src0, masksPrev.flat)
        val mSkinSrc = upscaleMaskTo(src0, masksPrev.skin)
        val mSkySrc  = upscaleMaskTo(src0, masksPrev.sky)
        val mHiSrc   = orMask(upscaleMaskTo(src0, masksPrev.hiTexFine), upscaleMaskTo(src0, masksPrev.hiTexCoarse))

        // 1) Адресная фильтрация в исходном размере
        val src = src0.copy(Bitmap.Config.RGBA_F16, true)
        lumaNR(src, preset.nr, mEdgeSrc, mFlatSrc)           // люма NR
        chromaNR(src, preset.nr)                             // хрома NR (упрощённо)
        textureSmooth(src, preset.texture, mFlatSrc, mHiSrc) // «анти‑песок» по плоским
        unifySkinSky(src, preset.unify, mSkinSrc, mSkySrc)   // унификация кожи/неба
        preSharpenUSM(src, preset.edges, mEdgeSrc)           // мягкий USM по кромкам

        // 2) Антиалиас префильтр σ(r) (маско‑зависимые множители уже в norm)
        aaPrefilter(src, norm, mEdgeSrc, mFlatSrc)

        // 3) Ресемпл с micro‑phase: выберем фазу с лучшим «балансом» (edge - ringing)
        val phaseTrials = max(1, preset.scale.microPhaseTrials)
        val candidates = buildPhaseSet(phaseTrials)
        var bestOut: Bitmap? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bestPhase = Pair(0.0, 0.0)

        for ((ox, oy) in candidates) {
            val out = when (preset.scale.filter) {
                ScaleFilter.EWA_L3     -> resizeLanczos3(src, targetWst, targetHst, ox, oy)
                ScaleFilter.EWA_Mitchell -> resizeMitchell(src, targetWst, targetHst, ox, oy)
            }
            val score = edgeEnergyMinusRinging(out)
            if (score > bestScore) {
                bestScore = score; bestOut = out; bestPhase = Pair(ox, oy)
            } else {
                out.recycle()
            }
        }
        Logger.i("PRESCALE", "phase.best", mapOf("ox" to bestPhase.first, "oy" to bestPhase.second, "score" to bestScore))
        val out0 = bestOut ?: resizeMitchell(src, targetWst, targetHst, 0.0, 0.0)

        // 4) FR‑метрики на целевом размере
        val fr = frChecks(src, out0)
        val pass = (fr.ssim >= preset.verify.ssimMin
                && fr.edgeKeep >= preset.verify.edgeKeepMin
                && fr.banding <= preset.verify.bandingMax
                && fr.de95 <= preset.verify.de95Max)
        Logger.i("PRESCALE", "fr", mapOf(
            "ssim" to "%.4f".format(fr.ssim),
            "edgeKeep" to "%.4f".format(fr.edgeKeep),
            "banding" to "%.4f".format(fr.banding),
            "de95" to "%.3f".format(fr.de95),
            "pass" to pass
        ))

        // 5) Пост‑обработка на целевом размере
        val out1 = out0.copy(Bitmap.Config.RGBA_F16, true)
        if (preset.post.dering) deRingingAlongEdges(out1)
        if (preset.post.localContrast) localContrastOnHiTex(out1)

        return Result(out = out1, wst = targetWst, hst = targetHst, fr = fr, passed = pass)
    }

    // ---------- Маски ----------
    private fun upscaleMaskTo(dstSize: Bitmap, srcMask: Bitmap): BooleanArray {
        val W = dstSize.width; val H = dstSize.height
        val out = BooleanArray(W*H)
        val mw = srcMask.width; val mh = srcMask.height
        // билинейная выборка альфы
        for (y in 0 until H) {
            val v = (y.toDouble() * (mh.toDouble()/H)).coerceIn(0.0, mh-1.00001)
            val y0 = floor(v).toInt(); val y1 = min(mh-1, y0+1); val ty = (v - y0)
            for (x in 0 until W) {
                val u = (x.toDouble() * (mw.toDouble()/W)).coerceIn(0.0, mw-1.00001)
                val x0 = floor(u).toInt(); val x1 = min(mw-1, x0+1); val tx = (u - x0)
                val c00 = srcMask.getPixel(x0, y0) ushr 24
                val c10 = srcMask.getPixel(x1, y0) ushr 24
                val c01 = srcMask.getPixel(x0, y1) ushr 24
                val c11 = srcMask.getPixel(x1, y1) ushr 24
                val a0 = c00*(1.0-tx) + c10*tx
                val a1 = c01*(1.0-tx) + c11*tx
                val a = (a0*(1.0-ty) + a1*ty) / 255.0
                out[y*W+x] = a > 0.5
            }
        }
        return out
    }
    private fun orMask(a:BooleanArray,b:BooleanArray):BooleanArray{
        val out=BooleanArray(a.size)
        for(i in out.indices) out[i]=a[i]||b[i]
        return out
    }

    // ---------- 1) Адресные фильтры ----------
    private fun lumaNR(bmp: Bitmap, nr: PresetSpec.NRParams, edge: BooleanArray, flat: BooleanArray) {
        val w=bmp.width; val h=bmp.height; val N=w*h
        val px = IntArray(w)
        // простая guided‑подобная фильтрация: box blur с меньшим весом на кромках
        val r = nr.lumaRadius.coerceIn(1, 8)
        val eps = nr.lumaEps.toFloat().coerceIn(0.0005f, 0.02f)
        val out = IntArray(N)
        val src = IntArray(N)
        bmp.getPixels(src,0,w,0,0,w,h)
        // посчитаем линейную яркость
        val L = FloatArray(N)
        for(i in 0 until N){
            val c = Color.valueOf(src[i]); L[i]=0.2126f*c.red()+0.7152f*c.green()+0.0722f*c.blue()
        }
        val mean = boxBlur(L,w,h,r)
        val varr = varianceField(L,mean,w,h,r)
        // guided update
        for(y in 0 until h) for(x in 0 until w){
            val i=y*w+x
            val a = varr[i]/(varr[i]+eps)
            val b = mean[i]*(1f-a)
            val c = Color.valueOf(src[i])
            val l = a*L[i]+b
            // смешиваем сильнее по Flat, слабее на Edges
            val k = when {
                edge[i] -> 0.2f
                flat[i] -> 1.0f
                else -> 0.5f
            }
            val scale = if (L[i]>1e-6f) (l/L[i])*k + (1f-k) else 1f
            val nrR = (c.red()*scale).coerceIn(0f,1f)
            val nrG = (c.green()*scale).coerceIn(0f,1f)
            val nrB = (c.blue()*scale).coerceIn(0f,1f)
            out[i]=Color.valueOf(nrR,nrG,nrB,c.alpha()).toArgb()
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }
    private fun chromaNR(bmp: Bitmap, nr: PresetSpec.NRParams) {
        // грубая хрома‑SMOOTH: усреднение U/V компонентой через лёгкий блур
        val w=bmp.width; val h=bmp.height
        val src = IntArray(w*h); bmp.getPixels(src,0,w,0,0,w,h)
        val R=FloatArray(w*h); val G=FloatArray(w*h); val B=FloatArray(w*h); val A=FloatArray(w*h)
        for(i in src.indices){
            val c=Color.valueOf(src[i]); R[i]=c.red(); G[i]=c.green(); B[i]=c.blue(); A[i]=c.alpha()
        }
        val r=2
        val Rb=boxBlur(R,w,h,r); val Gb=boxBlur(G,w,h,r); val Bb=boxBlur(B,w,h,r)
        val out=IntArray(w*h)
        val gain = nr.chromaGain.toFloat().coerceIn(0.6f,2.0f)
        for(i in src.indices){
            val newR = (R[i]*(1f-gain)+Rb[i]*gain).coerceIn(0f,1f)
            val newG = (G[i]*(1f-gain)+Gb[i]*gain).coerceIn(0f,1f)
            val newB = (B[i]*(1f-gain)+Bb[i]*gain).coerceIn(0f,1f)
            out[i]=Color.valueOf(newR,newG,newB,A[i]).toArgb()
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }
    private fun textureSmooth(bmp: Bitmap, t: PresetSpec.TextureParams, flat:BooleanArray, hi:BooleanArray){
        // лёгкое усреднение по плоским, ослабление по hi‑texture
        val w=bmp.width; val h=bmp.height
        val src = IntArray(w*h); bmp.getPixels(src,0,w,0,0,w,h)
        val out = IntArray(w*h)
        val r = 1
        val R=FloatArray(w*h); val G=FloatArray(w*h); val B=FloatArray(w*h); val A=FloatArray(w*h)
        for(i in src.indices){ val c=Color.valueOf(src[i]); R[i]=c.red();G[i]=c.green();B[i]=c.blue();A[i]=c.alpha() }
        val Rb=boxBlur(R,w,h,r); val Gb=boxBlur(G,w,h,r); val Bb=boxBlur(B,w,h,r)
        for(i in src.indices){
            val wFlat = t.smoothFlat.toFloat().coerceIn(0f,1f)
            val wNon  = t.smoothNonFlat.toFloat().coerceIn(0f,1f)
            val k = when {
                flat[i] && !hi[i] -> wFlat
                hi[i] -> wNon*0.5f
                else -> wNon
            }
            val nrR = (R[i]*(1f-k)+Rb[i]*k).coerceIn(0f,1f)
            val nrG = (G[i]*(1f-k)+Gb[i]*k).coerceIn(0f,1f)
            val nrB = (B[i]*(1f-k)+Bb[i]*k).coerceIn(0f,1f)
            out[i]=Color.valueOf(nrR,nrG,nrB,A[i]).toArgb()
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }
    private fun unifySkinSky(bmp: Bitmap, u: PresetSpec.UnifyParams, skin:BooleanArray, sky:BooleanArray){
        val w=bmp.width; val h=bmp.height
        val src=IntArray(w*h); bmp.getPixels(src,0,w,0,0,w,h)
        val out=IntArray(w*h)
        for(i in src.indices){
            val c=Color.valueOf(src[i])
            var r=c.red(); var g=c.green(); var b=c.blue()
            // Skin: чуть снижаем насыщенность, сглаживаем тон
            if (skin[i]) {
                val hsv = rgbToHsv(r,g,b)
                hsv[1] = (hsv[1] + (u.skinSatDelta/100.0).toFloat()).coerceIn(0f,1f)
                // tone_smooth эмулируем лёгким сжатием S
                hsv[1] = (hsv[1]*(1f - u.skinToneSmooth.toFloat().coerceIn(0f,0.6f))).coerceIn(0f,1f)
                val rgb = hsvToRgb(hsv)
                r=rgb[0]; g=rgb[1]; b=rgb[2]
            }
            // Sky: поджать V и подвести hue к моде (приближённо − лёгкий сдвиг к синему)
            if (sky[i]) {
                val hsv = rgbToHsv(r,g,b)
                val targetHue = 210f/360f // к синему
                hsv[0] = lerp(hsv[0], targetHue, u.skyHueShiftToMode.toFloat().coerceIn(0f,0.2f))
                hsv[2] = (hsv[2] + (u.skyVdelta/100.0).toFloat()).coerceIn(0f,1f)
                val rgb = hsvToRgb(hsv)
                r=rgb[0]; g=rgb[1]; b=rgb[2]
            }
            out[i]=Color.valueOf(r,g,b,c.alpha()).toArgb()
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }
    private fun preSharpenUSM(bmp: Bitmap, e: PresetSpec.EdgeParams, edge:BooleanArray){
        val w=bmp.width; val h=bmp.height
        val src = IntArray(w*h); bmp.getPixels(src,0,w,0,0,w,h)
        val out = IntArray(w*h)
        val radius = e.preSharpenRadius.coerceIn(0.6,2.0).toFloat()
        val blurred = gaussRgb(src,w,h, radius)
        for(i in src.indices){
            val c0=Color.valueOf(src[i]); val cb=Color.valueOf(blurred[i])
            val dr=c0.red()-cb.red(); val dg=c0.green()-cb.green(); val db=c0.blue()-cb.blue()
            val mag = abs(dr)+abs(dg)+abs(db)
            val thr = (e.preSharpenThreshold/255f).coerceIn(0f,1f)
            val amt = (e.preSharpenAmount).toFloat().coerceIn(0f,1f)
            val k = if(edge[i] && mag>thr) amt else 0f
            val nr = (c0.red()   + dr*k).coerceIn(0f,1f)
            val ng = (c0.green() + dg*k).coerceIn(0f,1f)
            val nb = (c0.blue()  + db*k).coerceIn(0f,1f)
            out[i]=Color.valueOf(nr,ng,nb,c0.alpha()).toArgb()
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }

    // ---------- 2) AA префильтр ----------
    private fun aaPrefilter(bmp: Bitmap, n: PresetGateResult.Normalized, edge:BooleanArray, flat:BooleanArray){
        val w=bmp.width; val h=bmp.height
        val src=IntArray(w*h); bmp.getPixels(src,0,w,0,0,w,h)
        val out=IntArray(w*h)
        val base = n.sigmaBase.toFloat()
        if (base <= 1e-6f) return
        val sigEdge = max(0f, (n.sigmaEdge).toFloat())
        val sigFlat = max(0f, (n.sigmaFlat).toFloat())
        // два размытия для смешивания
        val blurEdge = if (sigEdge>1e-6f) gaussRgb(src,w,h,sigEdge) else src
        val blurFlat = if (sigFlat>1e-6f) gaussRgb(src,w,h,sigFlat) else src
        for(i in src.indices){
            val c0 = Color.valueOf(src[i])
            val ce = Color.valueOf(blurEdge[i])
            val cf = Color.valueOf(blurFlat[i])
            val useE = if (edge[i]) 1 else 0
            val useF = if (flat[i]) 1 else 0
            val r = mix3(c0.red(), ce.red(), cf.red(), useE, useF)
            val g = mix3(c0.green(), ce.green(), cf.green(), useE, useF)
            val b = mix3(c0.blue(), ce.blue(), cf.blue(), useE, useF)
            out[i]=Color.valueOf(r,g,b,c0.alpha()).toArgb()
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }
    private fun mix3(orig:Float, edge:Float, flat:Float, e:Int, f:Int):Float{
        return when {
            f==1 -> flat
            e==1 -> 0.5f*orig + 0.5f*edge
            else -> 0.5f*orig + 0.5f*flat*0.5f + 0.5f*edge*0.5f
        }.coerceIn(0f,1f)
    }

    // ---------- 3) Downscale с micro‑phase ----------
    private fun buildPhaseSet(trials:Int): List<Pair<Double,Double>> =
        when (trials.coerceIn(1,8)) {
            1 -> listOf(0.0 to 0.0)
            2 -> listOf(0.0 to 0.0, 0.5 to 0.5)
            3 -> listOf(0.0 to 0.0, 0.5 to 0.0, 0.0 to 0.5)
            4 -> listOf(0.0 to 0.0, 0.5 to 0.0, 0.0 to 0.5, 0.5 to 0.5)
            else -> listOf(0.0 to 0.0, 0.25 to 0.25, 0.5 to 0.0, 0.0 to 0.5, 0.5 to 0.5, 0.75 to 0.25)
        }

    private fun resizeMitchell(src: Bitmap, W:Int, H:Int, ox:Double, oy:Double): Bitmap {
        val out = Bitmap.createBitmap(W,H,Bitmap.Config.RGBA_F16)
        val w = src.width.toDouble(); val h = src.height.toDouble()
        val sx = w / W; val sy = h / H
        val row = IntArray(src.width)
        for (y in 0 until H) {
            val v = (y+oy)*sy
            for (x in 0 until W) {
                val u = (x+ox)*sx
                val c = sampleMitchell(src, u, v)
                out.setPixel(x,y,c)
            }
        }
        return out
    }
    private fun resizeLanczos3(src: Bitmap, W:Int, H:Int, ox:Double, oy:Double): Bitmap {
        val out = Bitmap.createBitmap(W,H,Bitmap.Config.RGBA_F16)
        val w = src.width.toDouble(); val h = src.height.toDouble()
        val sx = w / W; val sy = h / H
        for (y in 0 until H) {
            val v = (y+oy)*sy
            for (x in 0 until W) {
                val u = (x+ox)*sx
                val c = sampleLanczos(src, u, v, 3.0)
                out.setPixel(x,y,c)
            }
        }
        return out
    }

    private fun sampleMitchell(bmp: Bitmap, u: Double, v: Double): Int {
        val x0 = floor(u).toInt(); val y0 = floor(v).toInt()
        var r=0.0; var g=0.0; var b=0.0; var a=0.0; var wsum=0.0
        for (j in -2..2) for (i in -2..2) {
            val x = (x0+i).coerceIn(0, bmp.width-1)
            val y = (y0+j).coerceIn(0, bmp.height-1)
            val wx = mitchell1D(u - (x0+i)); val wy = mitchell1D(v - (y0+j))
            val w = wx*wy
            val c = Color.valueOf(bmp.getPixel(x,y))
            r += c.red()*w; g += c.green()*w; b += c.blue()*w; a += c.alpha()*w; wsum+=w
        }
        if (wsum <= 1e-8) return Color.valueOf(0f,0f,0f,0f).toArgb()
        return Color.valueOf((r/wsum).toFloat(),(g/wsum).toFloat(),(b/wsum).toFloat(),(a/wsum).toFloat()).toArgb()
    }
    private fun mitchell1D(x: Double): Double {
        val B = 1.0/3.0; val C = 1.0/3.0
        val ax = abs(x)
        return when {
            ax < 1 -> ((12 - 9*B - 6*C)*ax*ax*ax + (-18 + 12*B + 6*C)*ax*ax + (6 - 2*B)).div(6.0)
            ax < 2 -> ((-B - 6*C)*ax*ax*ax + (6*B + 30*C)*ax*ax + (-12*B - 48*C)*ax + (8*B + 24*C)).div(6.0)
            else -> 0.0
        }
    }

    private fun sampleLanczos(bmp: Bitmap, u: Double, v: Double, a: Double): Int {
        val x0 = floor(u).toInt(); val y0 = floor(v).toInt()
        var r=0.0; var g=0.0; var b=0.0; var aacc=0.0; var wsum=0.0
        val rad = ceil(a).toInt()
        for (j in -rad..rad) for (i in -rad..rad) {
            val x = (x0+i).coerceIn(0, bmp.width-1)
            val y = (y0+j).coerceIn(0, bmp.height-1)
            val wx = lanczosWeight(u - (x0+i), a)
            val wy = lanczosWeight(v - (y0+j), a)
            val w = wx*wy
            if (w == 0.0) continue
            val c = Color.valueOf(bmp.getPixel(x,y))
            r += c.red()*w; g += c.green()*w; b += c.blue()*w; aacc += c.alpha()*w; wsum+=w
        }
        if (wsum <= 1e-8) return Color.valueOf(0f,0f,0f,0f).toArgb()
        return Color.valueOf((r/wsum).toFloat(),(g/wsum).toFloat(),(b/wsum).toFloat(),(aacc/wsum).toFloat()).toArgb()
    }
    private fun sinc(x: Double): Double = if (abs(x) < 1e-8) 1.0 else sin(Math.PI*x)/(Math.PI*x)
    private fun lanczosWeight(x: Double, a: Double): Double {
        val ax = abs(x)
        return if (ax < a) sinc(x)*sinc(x/a) else 0.0
    }

    // «энергия кромок – рингинг»: быстрый скоринг для выбора фазы
    private fun edgeEnergyMinusRinging(bmp: Bitmap): Double {
        val w=bmp.width; val h=bmp.height
        val row=IntArray(w)
        var edgeEnergy=0.0; var ring=0.0
        for (y in 1 until h-1) {
            bmp.getPixels(row,0,w,0,y,w,1)
            var prev = Color.valueOf(bmp.getPixel(0,y))
            for (x in 1 until w-1) {
                val c = Color.valueOf(row[x])
                val l = 0.2126*c.red()+0.7152*c.green()+0.0722*c.blue()
                val lp= 0.2126*prev.red()+0.7152*prev.green()+0.0722*prev.blue()
                val g = abs(l-lp)
                edgeEnergy += g
                // прокси‑рингинг: избыточные локальные экстремумы
                if (x>1) {
                    val c0 = Color.valueOf(row[x-1])
                    val l0 = 0.2126*c0.red()+0.7152*c0.green()+0.0722*c0.blue()
                    if ((l0-l)*(lp-l0) > 0) ring += 0.5
                }
                prev = c
            }
        }
        return edgeEnergy - ring*0.2
    }

    // ---------- 4) FR‑метрики ----------
    private fun frChecks(src: Bitmap, out: Bitmap): FR {
        // Референс: билатеральный low‑pass + Mitchell ресемпл (тут будет просто Mitchell напрямую)
        val ref = resizeMitchell(src, out.width, out.height, 0.0, 0.0)
        val ssim = ssimApprox(out, ref)
        val edgeKeep = edgeKeepRatio(src, out)
        val band = bandingIndex(ref, out)
        val de95 = deltaE95(ref, out)
        ref.recycle()
        return FR(ssim, edgeKeep, band, de95)
    }

    private fun ssimApprox(a: Bitmap, b: Bitmap): Double {
        val w=a.width; val h=a.height
        var muA=0.0; var muB=0.0; var cnt=0
        val ra=IntArray(w); val rb=IntArray(w)
        // средние по яркости
        for(y in 0 until h){
            a.getPixels(ra,0,w,0,y,w,1)
            b.getPixels(rb,0,w,0,y,w,1)
            for(x in 0 until w){
                val ca=Color.valueOf(ra[x]); val cb=Color.valueOf(rb[x])
                val la=0.2126*ca.red()+0.7152*ca.green()+0.0722*ca.blue()
                val lb=0.2126*cb.red()+0.7152*cb.green()+0.0722*cb.blue()
                muA+=la; muB+=lb; cnt++
            }
        }
        muA/=cnt; muB/=cnt
        var sA=0.0; var sB=0.0; var cov=0.0
        for(y in 0 until h){
            a.getPixels(ra,0,w,0,y,w,1)
            b.getPixels(rb,0,w,0,y,w,1)
            for(x in 0 until w){
                val ca=Color.valueOf(ra[x]); val cb=Color.valueOf(rb[x])
                val la=0.2126*ca.red()+0.7152*ca.green()+0.0722*ca.blue()
                val lb=0.2126*cb.red()+0.7152*cb.green()+0.0722*cb.blue()
                sA+=(la-muA)*(la-muA)
                sB+=(lb-muB)*(lb-muB)
                cov+=(la-muA)*(lb-muB)
            }
        }
        sA/= (cnt-1); sB/=(cnt-1); cov/=(cnt-1)
        val c1=0.01*0.01; val c2=0.03*0.03
        val num=(2*muA*muB + c1) * (2*cov + c2)
        val den=(muA*muA + muB*muB + c1) * (sA + sB + c2)
        return (num/den).coerceIn(0.0,1.0)
    }
    private fun edgeKeepRatio(src: Bitmap, out: Bitmap): Double {
        fun edgeRate(b:Bitmap):Double{
            val w=b.width; val h=b.height; val row=IntArray(w)
            var edges=0; var total=0
            for(y in 0 until h){
                b.getPixels(row,0,w,0,y,w,1)
                for(x in 1 until w){
                    val c=Color.valueOf(row[x]); val p=Color.valueOf(row[x-1])
                    val l=0.2126*c.red()+0.7152*c.green()+0.0722*c.blue()
                    val lp=0.2126*p.red()+0.7152*p.green()+0.0722*p.blue()
                    if (abs(l-lp) > 0.03) edges++
                    total++
                }
            }
            return edges.toDouble()/total
        }
        val srcDown = resizeMitchell(src, out.width, out.height, 0.0, 0.0)
        val eSrc = edgeRate(srcDown)
        val eOut = edgeRate(out)
        srcDown.recycle()
        return if (eSrc<=1e-8) 1.0 else (eOut/eSrc).coerceIn(0.0, 1.5)
    }
    private fun bandingIndex(ref: Bitmap, out: Bitmap): Double {
        // На плоских полях «стоп‑градиент»: доля пикселей с |∇L| почти 0 в out при умеренном ∇ в ref
        val w=out.width; val h=out.height
        val rRow=IntArray(w); val oRow=IntArray(w)
        var bad=0; var tot=0
        for(y in 0 until h){
            ref.getPixels(rRow,0,w,0,y,w,1)
            out.getPixels(oRow,0,w,0,y,w,1)
            for(x in 1 until w-1){
                val r0=Color.valueOf(rRow[x-1]); val r1=Color.valueOf(rRow[x+1])
                val o0=Color.valueOf(oRow[x-1]); val o1=Color.valueOf(oRow[x+1])
                val gr = abs(luma(r1)-luma(r0))
                val go = abs(luma(o1)-luma(o0))
                if (gr>0.01) { // был градиент
                    tot++
                    if (go < 0.003) bad++
                }
            }
        }
        return if (tot==0) 0.0 else bad.toDouble()/tot
    }
    private fun deltaE95(a: Bitmap, b: Bitmap): Double {
        val w=a.width; val h=a.height
        val ra=IntArray(w); val rb=IntArray(w)
        val list = ArrayList<Double>(w*h/4)
        for(y in 0 until h){
            a.getPixels(ra,0,w,0,y,w,1)
            b.getPixels(rb,0,w,0,y,w,1)
            for(x in 0 until w){
                val ca=Color.valueOf(ra[x]); val cb=Color.valueOf(rb[x])
                val okA = rgbLinToOKLab(ca.red(),ca.green(),ca.blue())
                val okB = rgbLinToOKLab(cb.red(),cb.green(),cb.blue())
                val d = sqrt((okA[0]-okB[0]).pow(2)+ (okA[1]-okB[1]).pow(2)+ (okA[2]-okB[2]).pow(2).toDouble())
                list.add(d)
            }
        }
        list.sort()
        val idx = (list.size*0.95).toInt().coerceIn(0, max(0,list.size-1))
        return list[idx]
    }

    // ---------- 5) Пост‑обработка ----------
    private fun deRingingAlongEdges(bmp: Bitmap){
        val w=bmp.width; val h=bmp.height
        val src=IntArray(w*h); bmp.getPixels(src,0,w,0,0,w,h)
        val out=IntArray(w*h)
        // лёгкий bilateral‑подобный: усредним, сохраняя кромки по локальному градиенту
        val r=1
        for(y in 0 until h){
            for(x in 0 until w){
                val idx=y*w+x
                val c=Color.valueOf(src[idx])
                var accR=0.0; var accG=0.0; var accB=0.0; var wsum=0.0
                val l0 = luma(c)
                for(dy in -r..r) for(dx in -r..r){
                    val xx=(x+dx).coerceIn(0,w-1); val yy=(y+dy).coerceIn(0,h-1)
                    val cc=Color.valueOf(src[yy*w+xx])
                    val lt = luma(cc)
                    val wg = exp(-((lt-l0)*(lt-l0))*50.0) // edge‑aware
                    val ws = if (dx==0 && dy==0) 1.0 else 0.6
                    val wgt = wg*ws
                    accR += cc.red()*wgt; accG += cc.green()*wgt; accB += cc.blue()*wgt; wsum+=wgt
                }
                out[idx]=Color.valueOf((accR/wsum).toFloat(),(accG/wsum).toFloat(),(accB/wsum).toFloat(),c.alpha()).toArgb()
            }
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }
    private fun localContrastOnHiTex(bmp: Bitmap){
        // простая «CLAHE‑лайт»: USM с малым радиусом везде
        val w=bmp.width; val h=bmp.height
        val src=IntArray(w*h); bmp.getPixels(src,0,w,0,0,w,h)
        val blur=gaussRgb(src,w,h, 0.8f)
        val out=IntArray(w*h)
        for(i in src.indices){
            val c0=Color.valueOf(src[i]); val cb=Color.valueOf(blur[i])
            val nr = (c0.red()*1.06f - cb.red()*0.06f).coerceIn(0f,1f)
            val ng = (c0.green()*1.06f - cb.green()*0.06f).coerceIn(0f,1f)
            val nb = (c0.blue()*1.06f - cb.blue()*0.06f).coerceIn(0f,1f)
            out[i]=Color.valueOf(nr,ng,nb,c0.alpha()).toArgb()
        }
        bmp.setPixels(out,0,w,0,0,w,h)
    }

    // ---------- утилиты ----------
    private fun luma(c: Color): Double = (0.2126*c.red()+0.7152*c.green()+0.0722*c.blue()).toDouble()
    private fun lerp(a:Float,b:Float,t:Float)=a+(b-a)*t

    private fun rgbToHsv(r:Float,g:Float,b:Float):FloatArray{
        val maxc = max(r, max(g,b)); val minc = min(r, min(g,b)); val d=maxc-minc
        var h=0f
        val s = if (maxc<=1e-6f) 0f else d/maxc
        val v = maxc
        if (d>1e-6f) {
            h = when (maxc) {
                r -> ( (g-b)/d + (if (g<b) 6 else 0) )/6f
                g -> ( (b-r)/d + 2 )/6f
                else -> ( (r-g)/d + 4 )/6f
            }
        }
        return floatArrayOf(h,s,v)
    }
    private fun hsvToRgb(hsv:FloatArray):FloatArray{
        val h=hsv[0]*6f; val s=hsv[1]; val v=hsv[2]
        val i=floor(h.toDouble()).toInt(); val f=h-i
        val p=v*(1f-s); val q=v*(1f-s*f); val t=v*(1f-s*(1f-f))
        val (r,g,b)=when(i%6){
            0-> floatArrayOf(v,t,p); 1-> floatArrayOf(q,v,p); 2-> floatArrayOf(p,v,t);
            3-> floatArrayOf(p,q,v); 4-> floatArrayOf(t,p,v); else-> floatArrayOf(v,p,q)
        }
        return floatArrayOf(r,g,b)
    }

    private fun boxBlur(src: FloatArray, w:Int, h:Int, r:Int): FloatArray {
        if (r<=0) return src.copyOf()
        val tmp=FloatArray(w*h); val out=FloatArray(w*h)
        // по X
        for(y in 0 until h){
            var acc=0f
            for(x in -r..r) acc += src[y*w + x.coerceIn(0,w-1)]
            for(x in 0 until w){
                tmp[y*w+x]= acc/(2*r+1)
                val xm = (x-r).coerceIn(0,w-1)
                val xp = (x+r+1).coerceIn(0,w-1)
                acc += src[y*w+xp] - src[y*w+xm]
            }
        }
        // по Y
        for(x in 0 until w){
            var acc=0f
            for(y in -r..r) acc += tmp[y.coerceIn(0,h-1)*w + x]
            for(y in 0 until h){
                out[y*w+x]= acc/(2*r+1)
                val ym = (y-r).coerceIn(0,h-1)
                val yp = (y+r+1).coerceIn(0,h-1)
                acc += tmp[yp*w+x] - tmp[ym*w+x]
            }
        }
        return out
    }
    private fun varianceField(L:FloatArray, mean:FloatArray, w:Int, h:Int, r:Int):FloatArray{
        val N=w*h; val out=FloatArray(N)
        // var ≈ E[L^2] - mean^2 (где E[L^2] через тот же boxBlur)
        val L2=FloatArray(N){ L[it]*L[it] }
        val mean2=boxBlur(L2,w,h,r)
        for(i in 0 until N) out[i]=max(0f, mean2[i]-mean[i]*mean[i])
        return out
    }

    private fun gaussRgb(src:IntArray,w:Int,h:Int,sigma:Float):IntArray{
        val r = max(1, (sigma*2).roundToInt())
        val k = gaussKernel1D(sigma, r)
        val tmpR=FloatArray(w*h); val tmpG=FloatArray(w*h); val tmpB=FloatArray(w*h); val A=FloatArray(w*h)
        for(i in src.indices){ val c=Color.valueOf(src[i]); tmpR[i]=c.red(); tmpG[i]=c.green(); tmpB[i]=c.blue(); A[i]=c.alpha() }
        // X
        val xr=FloatArray(w*h); val xg=FloatArray(w*h); val xb=FloatArray(w*h)
        for(y in 0 until h){
            for(x in 0 until w){
                var ar=0.0; var ag=0.0; var ab=0.0; var ws=0.0
                for(dx in -r..r){
                    val xx=(x+dx).coerceIn(0,w-1); val wgt=k[dx+r]
                    val idx=y*w+xx
                    ar+=tmpR[idx]*wgt; ag+=tmpG[idx]*wgt; ab+=tmpB[idx]*wgt; ws+=wgt
                }
                val id=y*w+x; xr[id]=(ar/ws).toFloat(); xg[id]=(ag/ws).toFloat(); xb[id]=(ab/ws).toFloat()
            }
        }
        // Y
        val out=IntArray(w*h)
        for(x in 0 until w){
            for(y in 0 until h){
                var ar=0.0; var ag=0.0; var ab=0.0; var ws=0.0
                for(dy in -r..r){
                    val yy=(y+dy).coerceIn(0,h-1); val wgt=k[dy+r]; val idx=yy*w+x
                    ar+=xr[idx]*wgt; ag+=xg[idx]*wgt; ab+=xb[idx]*wgt; ws+=wgt
                }
                val id=y*w+x
                out[id]=Color.valueOf((ar/ws).toFloat(),(ag/ws).toFloat(),(ab/ws).toFloat(),A[id]).toArgb()
            }
        }
        return out
    }
    private fun gaussKernel1D(sigma: Float, radius: Int): DoubleArray {
        val k=DoubleArray(radius*2+1)
        val s2 = 2.0*sigma*sigma
        var sum=0.0
        for(i in -radius..radius){
            val v= exp(-(i*i)/s2)
            k[i+radius]=v; sum+=v
        }
        for(i in k.indices) k[i]/=sum
        return k
    }

    private fun rgbLinToOKLab(r:Float,g:Float,b:Float):FloatArray{
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val l_ = cbrtF(l); val m_ = cbrtF(m); val s_ = cbrtF(s)
        val L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
        val A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
        val B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        return floatArrayOf(L,A,B)
    }
    private fun cbrtF(x:Float)= if (x<=0f) 0f else x.pow(1f/3f)
    }