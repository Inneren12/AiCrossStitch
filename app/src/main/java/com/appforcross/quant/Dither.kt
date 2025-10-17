package com.appforcross.quant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/** Дизеринг: ordered (64×64 «псевдо blue-noise») + FS (серпантин) с edge-aware ослаблением. */
object Dither {
    /** Ordered‑маска 64×64 (генерируем детерминированно). Значения 0..1. */
    private fun blueMask64(): FloatArray {
        val w=64; val h=64; val n=w*h
        val m = FloatArray(n)
        var seed = 1234567u
        fun rnd(): Float { seed = seed*1664525u + 1013904223u; return ((seed shr 8) and 0xFFFFu).toFloat()/65535f }
        // равномерный шум + 2 итерации разрежения (очень упрощённый void-and-cluster)
        for (i in 0 until n) m[i]=rnd()
        repeat(2) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y*w+x
                var acc=0f; var cnt=0
                for (dy in -2..2) for (dx in -2..2) {
                    if (dx==0 && dy==0) continue
                    val xx=(x+dx+w)%w; val yy=(y+dy+h)%h
                    acc+=m[yy*w+xx]; cnt++
                }
                val avg = acc/max(1,cnt)
                // немного отталкиваемся от окружения
                m[i] = (0.7f*m[i] + 0.3f*(1f-avg)).coerceIn(0f,1f)
            }
        }
        return m
    }
    private val blue64 by lazy { blueMask64() }

    /** Ordered-дизер (по L* прокси): amp 0..1. */
    fun ordered(src: Bitmap, amp: Float): Bitmap {
        val w=src.width; val h=src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val inRow = IntArray(w)
        val outRow = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(inRow, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = inRow[x]
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                val a = Color.alpha(c)
                val l = 0.2126f*r + 0.7152f*g + 0.0722f*b
                val t = blue64[(y and 63)*64 + (x and 63)]
                val dl = (t-0.5f)*amp*0.1f
                val s = (l+dl).coerceIn(0f,1f)
                val fac = if (l>1e-6f) s / l else 1f
                val nr = (r*fac).coerceIn(0f,1f)
                val ng = (g*fac).coerceIn(0f,1f)
                val nb = (b*fac).coerceIn(0f,1f)
                outRow[x] = Color.argb(
                    a,
                    (nr*255f + 0.5f).toInt().coerceIn(0,255),
                    (ng*255f + 0.5f).toInt().coerceIn(0,255),
                    (nb*255f + 0.5f).toInt().coerceIn(0,255)
                )
            }
            out.setPixels(outRow, 0, w, 0, y, w, 1)
        }
        return out
    }

    /** FS (серпантин), который возвращает **индексы** палитры 0..(k-1).
     *  edgeMask — ослабление переноса ошибки на кромках; cap — предел рассылки ошибки.
     */
    fun floydSteinbergIndex(
        src: Bitmap,
        palette: IntArray,
        edgeMask: BooleanArray? = null,
        cap: Float = 0.67f
    ): IntArray {
        val w = src.width; val h = src.height
        val outIdx = IntArray(w * h)
        // рабочий буфер RGB в float [0..1]
        val n = w * h
        val R = FloatArray(n)
        val G = FloatArray(n)
        val B = FloatArray(n)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val c = row[x]
                R[base + x] = Color.red(c) / 255f
                G[base + x] = Color.green(c) / 255f
                B[base + x] = Color.blue(c) / 255f
            }
        }
        fun nearestIndex(r: Float, g: Float, b: Float): Int {
            var best = 0
            var bestD = Float.MAX_VALUE
            val rr = r * 255f; val gg = g * 255f; val bb = b * 255f
            for (i in 0 until palette.size) {
                val c = palette[i]
                val dr = rr - Color.red(c)
                val dg = gg - Color.green(c)
                val db = bb - Color.blue(c)
                val d = dr*dr + dg*dg + db*db
                if (d < bestD) { bestD = d; best = i }
            }
            return best
        }
        val serpentine = true
        for (y in 0 until h) {
            val base = y * w
            val forward = !serpentine || y % 2 == 0
            if (forward) {
                for (x in 0 until w) {
                    val idxPix = base + x
                    val r = R[idxPix]; val g = G[idxPix]; val b = B[idxPix]
                    val pi = nearestIndex(r, g, b)                 // 0..k-1
                    outIdx[idxPix] = pi
                    val pc = palette[pi]
                    val pr = Color.red(pc) / 255f
                    val pg = Color.green(pc) / 255f
                    val pb = Color.blue(pc) / 255f
                    var er = (r - pr).coerceIn(-cap, cap)
                    var eg = (g - pg).coerceIn(-cap, cap)
                    var eb = (b - pb).coerceIn(-cap, cap)
                    val k = if (edgeMask != null && edgeMask[idxPix]) 0.5f else 1f
                    fun add(xi: Int, yi: Int, wr: Float) {
                        if (xi < 0 || xi >= w || yi < 0 || yi >= h) return
                        val target = yi * w + xi
                        R[target] = (R[target] + er * wr * k).coerceIn(0f, 1f)
                        G[target] = (G[target] + eg * wr * k).coerceIn(0f, 1f)
                        B[target] = (B[target] + eb * wr * k).coerceIn(0f, 1f)
                    }
                    add(x + 1, y, 7f / 16f); add(x - 1, y + 1, 3f / 16f); add(x, y + 1, 5f / 16f); add(x + 1, y + 1, 1f / 16f)
                }
            } else {
                for (x in w - 1 downTo 0) {
                    val idxPix = base + x
                    val r = R[idxPix]; val g = G[idxPix]; val b = B[idxPix]
                    val pi = nearestIndex(r, g, b)                 // 0..k-1
                    outIdx[idxPix] = pi
                    val pc = palette[pi]
                    val pr = Color.red(pc) / 255f
                    val pg = Color.green(pc) / 255f
                    val pb = Color.blue(pc) / 255f
                    var er = (r - pr).coerceIn(-cap, cap)
                    var eg = (g - pg).coerceIn(-cap, cap)
                    var eb = (b - pb).coerceIn(-cap, cap)
                    val k = if (edgeMask != null && edgeMask[idxPix]) 0.5f else 1f
                    fun add(xi: Int, yi: Int, wr: Float) {
                        if (xi < 0 || xi >= w || yi < 0 || yi >= h) return
                        val target = yi * w + xi
                        R[target] = (R[target] + er * wr * k).coerceIn(0f, 1f)
                        G[target] = (G[target] + eg * wr * k).coerceIn(0f, 1f)
                        B[target] = (B[target] + eb * wr * k).coerceIn(0f, 1f)
                    }
                    add(x - 1, y, 7f / 16f); add(x + 1, y + 1, 3f / 16f); add(x, y + 1, 5f / 16f); add(x - 1, y + 1, 1f / 16f)
                }
            }
        }
        return outIdx
    }

    /** FS (серпантин), edge-aware: по сильным градиентам уменьшаем перенос ошибки. */
    fun floydSteinberg(
        src: Bitmap,
        mapToPalette: (r:Float,g:Float,b:Float)->Int,
        edgeMask: BooleanArray? = null,
        cap: Float = 0.67f
    ): IntArray {
        val w=src.width; val h=src.height; val n=w*h
        val out = IntArray(n)
        // Рабочий буфер float RGB
        val R = FloatArray(n); val G = FloatArray(n); val B = FloatArray(n)
        val row = IntArray(w)
        for (y in 0 until h) {
            src.getPixels(row,0,w,0,y,w,1)
            val base = y*w
            for (x in 0 until w) {
                val c = row[x]
                R[base+x] = Color.red(c)/255f
                G[base+x] = Color.green(c)/255f
                B[base+x] = Color.blue(c)/255f
            }
        }
        val serpentine = true
        for (y in 0 until h) {
            val base = y*w
            val forward = !serpentine || y%2==0
            if (forward) {
                for (x in 0 until w) {
                    val idx = base + x
                    val r=R[idx]; val g=G[idx]; val b=B[idx]
                    val pal = mapToPalette(r,g,b)
                    out[idx] = pal
                    val pr = Color.red(pal)/255f; val pg=Color.green(pal)/255f; val pb=Color.blue(pal)/255f
                    var er = (r-pr).coerceIn(-cap,cap)
                    var eg = (g-pg).coerceIn(-cap,cap)
                    var eb = (b-pb).coerceIn(-cap,cap)
                    var k = if (edgeMask != null && edgeMask[idx]) 0.5f else 1f
                    fun add(xi:Int, yi:Int, wr:Float){
                        if (xi<0||xi>=w||yi<0||yi>=h) return
                        val target = yi*w + xi
                        R[target]=(R[target] + er*wr*k).coerceIn(0f,1f)
                        G[target]=(G[target] + eg*wr*k).coerceIn(0f,1f)
                        B[target]=(B[target] + eb*wr*k).coerceIn(0f,1f)
                    }
                    add(x+1,y,7f/16f); add(x-1,y+1,3f/16f); add(x,y+1,5f/16f); add(x+1,y+1,1f/16f)
                }
            } else {
                for (x in w-1 downTo 0) {
                    val idx = base + x
                    val r=R[idx]; val g=G[idx]; val b=B[idx]
                    val pal = mapToPalette(r,g,b)
                    out[idx] = pal
                    val pr = Color.red(pal)/255f; val pg=Color.green(pal)/255f; val pb=Color.blue(pal)/255f
                    var er = (r-pr).coerceIn(-cap,cap)
                    var eg = (g-pg).coerceIn(-cap,cap)
                    var eb = (b-pb).coerceIn(-cap,cap)
                    var k = if (edgeMask != null && edgeMask[idx]) 0.5f else 1f
                    fun add(xi:Int, yi:Int, wr:Float){
                        if (xi<0||xi>=w||yi<0||yi>=h) return
                        val target = yi*w + xi
                        R[target]=(R[target] + er*wr*k).coerceIn(0f,1f)
                        G[target]=(G[target] + eg*wr*k).coerceIn(0f,1f)
                        B[target]=(B[target] + eb*wr*k).coerceIn(0f,1f)
                    }
                    add(x-1,y,7f/16f); add(x+1,y+1,3f/16f); add(x,y+1,5f/16f); add(x-1,y+1,1f/16f)
                }
            }
        }
        return out
    }
}