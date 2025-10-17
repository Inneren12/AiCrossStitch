package com.appforcross.editor.quant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = Color.valueOf(src.getPixel(x,y))
                val l = 0.2126f*c.red()+0.7152f*c.green()+0.0722f*c.blue()
                val t = blue64[(y and 63)*64 + (x and 63)]
                val dl = (t-0.5f)*amp*0.1f
                val s = (l+dl).coerceIn(0f,1f)
                val fac = if (l>1e-6f) s/l else 1f
                val nr = (c.red()*fac).coerceIn(0f,1f)
                val ng = (c.green()*fac).coerceIn(0f,1f)
                val nb = (c.blue()*fac).coerceIn(0f,1f)
                out.setPixel(x,y, Color.valueOf(nr,ng,nb,c.alpha()).toArgb())
            }
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
        val R = Array(h) { FloatArray(w) }
        val G = Array(h) { FloatArray(w) }
        val B = Array(h) { FloatArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            val c = Color.valueOf(src.getPixel(x, y))
            R[y][x] = c.red(); G[y][x] = c.green(); B[y][x] = c.blue()
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
        for (yy in 0 until h) {
            val y = if (!serpentine || yy % 2 == 0) yy else (h - 1 - yy)
                val xRange = if (!serpentine || yy % 2 == 0) 0 until w else (w - 1 downTo 0)
                    for (x in xRange) {
                        val idxPix = y * w + x
                        val r = R[y][x]; val g = G[y][x]; val b = B[y][x]
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
                            R[yi][xi] = (R[yi][xi] + er * wr * k).coerceIn(0f, 1f)
                            G[yi][xi] = (G[yi][xi] + eg * wr * k).coerceIn(0f, 1f)
                            B[yi][xi] = (B[yi][xi] + eb * wr * k).coerceIn(0f, 1f)
                        }
                        if (!serpentine || yy % 2 == 0) {
                            add(x + 1, y, 7f / 16f); add(x - 1, y + 1, 3f / 16f); add(x, y + 1, 5f / 16f); add(x + 1, y + 1, 1f / 16f)
                        } else {
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
        val R=Array(h){ FloatArray(w) }; val G=Array(h){ FloatArray(w) }; val B=Array(h){ FloatArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            val c = Color.valueOf(src.getPixel(x,y))
            R[y][x]=c.red(); G[y][x]=c.green(); B[y][x]=c.blue()
        }
        val serpentine = true
        for (yy in 0 until h) {
            val y = if (!serpentine || yy%2==0) yy else (h-1-yy)
                var xRange = if (!serpentine || yy%2==0) 0 until w else (w-1 downTo 0)
                    for (x in xRange) {
                        val idx = y*w + x
                        val r=R[y][x]; val g=G[y][x]; val b=B[y][x]
                        val pal = mapToPalette(r,g,b)
                        out[idx] = pal
                        val pr = Color.red(pal)/255f; val pg=Color.green(pal)/255f; val pb=Color.blue(pal)/255f
                        var er = (r-pr).coerceIn(-cap,cap)
                        var eg = (g-pg).coerceIn(-cap,cap)
                        var eb = (b-pb).coerceIn(-cap,cap)
                        var k = 1f
                        if (edgeMask != null && edgeMask[idx]) k = 0.5f
                        // Раскидываем ошибку (FS 7/16,3/16,5/16,1/16)
                        fun add(xi:Int, yi:Int, wr:Float){
                            if (xi<0||xi>=w||yi<0||yi>=h) return
                            R[yi][xi]=(R[yi][xi] + er*wr*k).coerceIn(0f,1f)
                            G[yi][xi]=(G[yi][xi] + eg*wr*k).coerceIn(0f,1f)
                            B[yi][xi]=(B[yi][xi] + eb*wr*k).coerceIn(0f,1f)
                        }
                        if (!serpentine || yy%2==0) {
                            add(x+1,y,7f/16f); add(x-1,y+1,3f/16f); add(x,y+1,5f/16f); add(x+1,y+1,1f/16f)
                        } else {
                            add(x-1,y,7f/16f); add(x+1,y+1,3f/16f); add(x,y+1,5f/16f); add(x-1,y+1,1f/16f)
                        }
                    }
        }
        return out
    }
}