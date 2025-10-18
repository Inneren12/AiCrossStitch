package com.appforcross.editor.pattern

import android.content.Context
import android.graphics.*
import android.util.Log
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.Logger
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HashSet

object PatternRunner {

    data class Options(
        val minRunFlat: Int = 4,       // минимальная длина прогона на плоских/в целом
        val minRunEdge: Int = 3,       // минимальная длина прогона на кромках (щадяще)
        val islandKill: Int = 2,       // удалить островки < N клеток
        val crfLambda: Float = 0.8f,   // сила Поттса: 0..1 (чем выше — меньше «конфетти»)
        val previewMaxPx: Int = 1024,  // предел по большей стороне для png-превью
        val drawGrid: Boolean = true   // рисовать тонкую сетку на превью
    )

    data class Output(
        val indexBin: String,
        val legendJson: String,
        val previewPng: String,
        val changesPer100: Double,
        val smallIslandsPer1000: Double,
        val runMedian: Double
    )

    // ---------- Log helpers ----------
    private fun logKV(tag: String, event: String, meta: Map<String, Any?> = emptyMap()) {
        Logger.i(tag, event, meta)
        if (meta.isEmpty()) {
            Log.i("AiX/$tag", event)
        } else {
            val sb = StringBuilder(event)
            meta.entries.sortedBy { it.key }.forEach { (k, v) ->
                sb.append(' ').append(k).append('=').append(v)
            }
            Log.i("AiX/$tag", sb.toString())
        }
    }

    private fun idxStats(a: IntArray): Triple<Int, Int, Int> {
        if (a.isEmpty()) return Triple(0, 0, 0)
        var minV = Int.MAX_VALUE
        var maxV = Int.MIN_VALUE
        val seen = HashSet<Int>()
        for (v in a) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            if (seen.size < 4096) seen += v
        }
        if (minV == Int.MAX_VALUE) minV = 0
        if (maxV == Int.MIN_VALUE) maxV = 0
        return Triple(minV, maxV, seen.size)
    }

    // ========= CORE S9: quant→legend LUT + safe writer =========

    /** Строит LUT: quantIndex -> legendIndex (оба массивы RGB, IntArray) */
    private fun buildQuantToLegendLut(quantPalette: IntArray, legendRgb: IntArray): IntArray {
        if (legendRgb.isEmpty()) return IntArray(quantPalette.size) { 0 }
        val byRgb = HashMap<Int, Int>(legendRgb.size).apply {
            legendRgb.forEachIndexed { i, c -> put(c, i) }
        }
        var exact = 0
        var fallback = 0
        val lut = IntArray(quantPalette.size) { 0 }
        for (qi in quantPalette.indices) {
            val rgb = quantPalette[qi]
            val li = byRgb[rgb]
            if (li != null) {
                exact++
                lut[qi] = li
            } else {
                fallback++
                lut[qi] = nearestLegend(rgb, legendRgb)
            }
        }
        Logger.i(
            "PATTERN", "lut.stats",
            mapOf("quantK" to quantPalette.size, "legendK" to legendRgb.size, "exact" to exact, "fallback" to fallback)
        )
        return lut
    }

    private fun nearestLegend(rgb: Int, legendRgb: IntArray): Int {
        if (legendRgb.isEmpty()) return 0
        var best = 0
        var bestD = Int.MAX_VALUE
        val r = (rgb ushr 16) and 0xFF
        val g = (rgb ushr 8) and 0xFF
        val b = rgb and 0xFF
        legendRgb.forEachIndexed { idx, c ->
            val er = (c ushr 16) and 0xFF
            val eg = (c ushr 8) and 0xFF
            val eb = c and 0xFF
            val dr = r - er
            val dg = g - eg
            val db = b - eb
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = idx }
        }
        return best
    }

    /** Восстанавливает усреднённую квант-палитру по цветному PNG и индексам */
    private fun buildQuantPaletteFromColor(colorBmp: Bitmap, indices: IntArray, fallbackPalette: IntArray): IntArray {
        val k = fallbackPalette.size
        if (k == 0) return IntArray(0)
        val sumR = LongArray(k)
        val sumG = LongArray(k)
        val sumB = LongArray(k)
        val cnt = IntArray(k)
        val row = IntArray(colorBmp.width)
        var p = 0
        for (y in 0 until colorBmp.height) {
            colorBmp.getPixels(row, 0, colorBmp.width, 0, y, colorBmp.width, 1)
            for (x in 0 until colorBmp.width) {
                val qi = indices[p++].coerceIn(0, k - 1)
                val c = row[x]
                sumR[qi] += Color.red(c).toLong()
                sumG[qi] += Color.green(c).toLong()
                sumB[qi] += Color.blue(c).toLong()
                cnt[qi]++
            }
        }
        val out = IntArray(k)
        for (i in 0 until k) {
            out[i] = if (cnt[i] > 0) {
                val r = (sumR[i] / cnt[i]).toInt().coerceIn(0, 255)
                val g = (sumG[i] / cnt[i]).toInt().coerceIn(0, 255)
                val b = (sumB[i] / cnt[i]).toInt().coerceIn(0, 255)
                Color.rgb(r, g, b)
            } else {
                fallbackPalette[i]
            }
        }
        return out
    }

    /** Читает индексную карту (1/2/4 байта на пиксель, LE) */
    private fun readQuantIndex(path: String, px: Int): IntArray {
        val f = File(path)
        val len = f.length()
        val elem = when (len) {
            px.toLong() -> 1
            (px * 2L) -> 2
            (px * 4L) -> 4
            else -> error("index.len=$len px=$px")
        }
        val buf = ByteArray(px * elem)
        FileInputStream(f).use { ins ->
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
        }
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val out = IntArray(px)
        when (elem) {
            1 -> for (i in 0 until px) out[i] = bb.get().toInt() and 0xFF
            2 -> for (i in 0 until px) out[i] = bb.getShort().toInt() and 0xFFFF
            else -> for (i in 0 until px) out[i] = bb.getInt()
        }
        return out
    }

    /** Пишет pattern_index.bin (1 байт/пикс) + лог min/max/unique */
    private fun writePatternIndex(dstPath: String, indices: IntArray) {
        val bytes = ByteArray(indices.size) { i -> indices[i].coerceIn(0, 255).toByte() }
        FileOutputStream(File(dstPath)).use { it.write(bytes) }
        var minV = Int.MAX_VALUE
        var maxV = Int.MIN_VALUE
        val seen = HashSet<Int>()
        for (v in indices) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            seen += v
        }
        if (minV == Int.MAX_VALUE) minV = 0
        if (maxV == Int.MIN_VALUE) maxV = 0
        Logger.i("PATTERN", "write.stats", mapOf("px" to indices.size, "min" to minV, "max" to maxV, "unique" to seen.size))
    }

    /** Главный метод */
    fun run(
        ctx: Context,
        palette: IntArray,
        indexBinPath: String,
        colorPngPath: String,
        catalogJsonPath: String? = null,
        opt: Options = Options()
    ): Output {
        Logger.i("PATTERN", "start", mapOf("k" to palette.size, "index" to indexBinPath, "color" to colorPngPath))
        require(palette.isNotEmpty()) { "Palette is empty; cannot build pattern" }

        // 0) размеры и парный index_<ID>.bin
        val bmp = BitmapFactory.decodeFile(colorPngPath)
        require(bmp != null) { "Cannot decode color PNG: $colorPngPath" }
        val w = bmp.width
        val h = bmp.height
        val resolvedIndexPath = resolveIndexPair(indexBinPath, colorPngPath, w, h)
        val origIdx = File(indexBinPath)
        val resIdx = File(resolvedIndexPath)
        logKV("PATTERN", "input", mapOf(
            "color" to File(colorPngPath).name,
            "index.orig" to origIdx.name, "exists.orig" to origIdx.exists(), "len.orig" to origIdx.length(),
            "index.use" to resIdx.name, "exists.use" to resIdx.exists(), "len.use" to resIdx.length(),
            "w" to w, "h" to h
        ))
        val idx = readQuantIndex(resolvedIndexPath, w * h)
        val (preMin, preMax, preUniq) = idxStats(idx)
        logKV("PATTERN", "idx.stats.preMap", mapOf("min" to preMin, "max" to preMax, "unique" to preUniq))

        // 1) edge-mask
        val edgeMask = makeEdgeMask(bmp)

        // 2) топология
        val idx1 = idx.copyOf()
        minRunSmoothing(idx1, w, h, edgeMask, opt.minRunFlat, opt.minRunEdge)
        islandCleanup(idx1, w, h, opt.islandKill)
        crfPottsPass(idx1, w, h, edgeMask, opt.crfLambda)

        // 3) метрики
        val changesPer100 = measureThreadChangesPer100(idx1, w, h)
        val islandsPer1000 = measureSmallIslandsPer1000(idx1, w, h)
        val runMed = measureRunMedian(idx1, w, h)

        // 3b) квант-палитра
        val quantPalette = buildQuantPaletteFromColor(bmp, idx1, palette)
        bmp.recycle()

        // 4) ЛЕГЕНДА/КАТАЛОГ ОБЯЗАТЕЛЬНЫ: RGB ниток из assets + palette_catalog.json
        if (catalogJsonPath.isNullOrEmpty() || !File(catalogJsonPath).exists()) {
            Logger.e("PATTERN", "legend.missing.file", mapOf("path" to catalogJsonPath))
            throw IllegalStateException("Catalog map JSON is missing: $catalogJsonPath")
        }
        val legend = buildLegend(palette, catalogJsonPath) // твой JSON с символами/кодами
        val legendFile = File(ctx.cacheDir, "pattern_legend.json")
        FileOutputStream(legendFile).use { it.write(legend.toString().toByteArray()) }

        val legendRgb: IntArray = readLegendThreadRgbStrict(ctx, catalogJsonPath, palette.size)
        logKV("PATTERN", "legend.size", mapOf("legendK" to legendRgb.size, "catalog" to catalogJsonPath))

        // 5) LUT → remap → write (строго, без подмен)
        val outIndex = File(ctx.cacheDir, "pattern_index.bin")
        val lut = buildQuantToLegendLut(quantPalette, legendRgb)
        logKV("PATTERN", "lut.stats", mapOf("quantK" to quantPalette.size, "legendK" to legendRgb.size))

        val idxMapped = IntArray(idx1.size) { p ->
            val qi = idx1[p]
            if (qi in 0 until lut.size) lut[qi] else {
                Logger.e("PATTERN", "lut.out.of.range", mapOf("qi" to qi, "lutSize" to lut.size))
                throw IllegalStateException("LUT out-of-range: qi=$qi lutSize=${lut.size}")
            }
        }
        val (postMin, postMax, postUniq) = idxStats(idxMapped)
        logKV("PATTERN", "idx.stats.postMap", mapOf("min" to postMin, "max" to postMax, "unique" to postUniq))
        if (preUniq > 1 && postUniq <= 1) {
            Logger.e("PATTERN", "lut.degenerate", mapOf("preUnique" to preUniq, "postUnique" to postUniq, "legendK" to legendRgb.size))
            throw IllegalStateException("LUT collapse: preUnique=$preUniq postUnique=$postUniq legendK=${legendRgb.size}")
        }

        writePatternIndex(outIndex.absolutePath, idxMapped)
        run {
            val (wMin, wMax, wUniq) = idxStats(idxMapped)
            logKV("PATTERN", "write.stats.mirror", mapOf("px" to idxMapped.size, "min" to wMin, "max" to wMax, "unique" to wUniq))
        }

        // 6) превью: цветами ниток (legendRgb)
        val prev = renderPreview(idxMapped, w, h, legendRgb, legend, maxSide = opt.previewMaxPx, drawGrid = opt.drawGrid)
        val prevFile = File(ctx.cacheDir, "pattern_preview.png")
        FileOutputStream(prevFile).use { out -> prev.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prev.recycle()

        // 7) diag-копии
        try {
            DiagnosticsManager.currentSessionDir(ctx)?.let { d ->
                legendFile.copyTo(File(d, legendFile.name), overwrite = true)
                outIndex.copyTo(File(d, outIndex.name), overwrite = true)
                prevFile.copyTo(File(d, prevFile.name), overwrite = true)
            }
        } catch (_: Exception) { /* ignore */ }

        Logger.i(
            "PATTERN", "done",
            mapOf(
                "index" to outIndex.absolutePath,
                "legend" to legendFile.absolutePath,
                "preview" to prevFile.absolutePath,
                "changesPer100" to "%.2f".format(changesPer100),
                "islandsPer1000" to "%.2f".format(islandsPer1000),
                "runMed" to "%.2f".format(runMed)
            )
        )
        return Output(
            indexBin = outIndex.absolutePath,
            legendJson = legendFile.absolutePath,
            previewPng = prevFile.absolutePath,
            changesPer100 = changesPer100,
            smallIslandsPer1000 = islandsPer1000,
            runMedian = runMed
        )
    }

    // ---------- I/O ----------
    private fun readIndexBin(path: String, w: Int, h: Int): IntArray = readQuantIndex(path, w * h)

    // ---------- Edge mask ----------
    private fun makeEdgeMask(src: Bitmap): BooleanArray {
        val w = src.width
        val h = src.height
        val out = BooleanArray(w * h)
        val row = IntArray(w)
        fun lum(c: Int): Int = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)).toInt()
        val L = IntArray(w * h)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) L[y * w + x] = lum(row[x])
        }
        val mags = ArrayList<Int>(w * h)
        for (y in 1 until h) for (x in 1 until w) {
            val gx = L[y * w + x] - L[y * w + (x - 1)]
            val gy = L[y * w + x] - L[(y - 1) * w + x]
            mags.add(abs(gx) + abs(gy))
        }
        mags.sort()
        val thr = if (mags.isEmpty()) Int.MAX_VALUE else {
            val pos = (mags.size * 0.85).toInt().coerceIn(0, mags.lastIndex)
            val cand = mags[pos]
            if (cand == 0) mags.firstOrNull { it > 0 } ?: Int.MAX_VALUE else cand
        }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = L[y * w + x] - L[y * w + (x - 1)]
            val gy = L[y * w + x] - L[(y - 1) * w + x]
            out[y * w + x] = (abs(gx) + abs(gy)) >= thr
        }
        return out
    }

    // ---------- Topology ----------
    private fun minRunSmoothing(idx: IntArray, w: Int, h: Int, edge: BooleanArray, minFlat: Int, minEdge: Int) {
        // строки
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                val c = idx[y * w + x]
                var x2 = x
                var hasEdge = false
                while (x2 < w && idx[y * w + x2] == c) {
                    if (edge[y * w + x2]) hasEdge = true
                    x2++
                }
                val run = x2 - x
                val minR = if (hasEdge) minEdge else minFlat
                if (run < minR) {
                    val left = if (x > 0) idx[y * w + (x - 1)] else c
                    val right = if (x2 < w) idx[y * w + x2] else c
                    val repl = if (x > 0 && x2 < w && left == right) left else if (x > 0) left else right
                    for (t in x until x2) idx[y * w + t] = repl
                }
                x = x2
            }
        }
        // столбцы
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                val c = idx[y * w + x]
                var y2 = y
                var hasEdge = false
                while (y2 < h && idx[y2 * w + x] == c) {
                    if (edge[y2 * w + x]) hasEdge = true
                    y2++
                }
                val run = y2 - y
                val minR = if (hasEdge) minEdge else minFlat
                if (run < minR) {
                    val up = if (y > 0) idx[(y - 1) * w + x] else c
                    val dn = if (y2 < h) idx[y2 * w + x] else c
                    val repl = if (y > 0 && y2 < h && up == dn) up else if (y > 0) up else dn
                    for (t in y until y2) idx[t * w + x] = repl
                }
                y = y2
            }
        }
    }

    private fun islandCleanup(idx: IntArray, w: Int, h: Int, killBelow: Int) {
        if (killBelow <= 0) return
        val vis = BooleanArray(w * h)
        val q: ArrayDeque<Int> = ArrayDeque()
        val dirs = intArrayOf(1, -1, w, -w)
        fun onGrid(p: Int) = p >= 0 && p < w * h
        for (start in 0 until w * h) {
            if (vis[start]) continue
            val color = idx[start]
            var count = 0
            q.clear()
            q.add(start)
            vis[start] = true
            val comp = ArrayList<Int>(16)
            val border = HashMap<Int, Int>(8)
            while (q.isNotEmpty()) {
                val p = q.removeFirst()
                comp.add(p); count++
                val y = p / w; val x = p % w
                for (d in dirs) {
                    val np = p + d
                    if (!onGrid(np)) continue
                    if ((d == 1 && (x + 1) % w == 0) || (d == -1 && x % w == 0)) continue
                    if (!vis[np]) {
                        if (idx[np] == color) {
                            vis[np] = true; q.add(np)
                        } else {
                            border[idx[np]] = (border[idx[np]] ?: 0) + 1
                        }
                    }
                }
            }
            if (count in 1 until killBelow) {
                val repl = border.entries.maxByOrNull { it.value }?.key
                if (repl != null) for (p in comp) idx[p] = repl
            }
        }
    }

    private fun crfPottsPass(idx: IntArray, w: Int, h: Int, edge: BooleanArray, lambda: Float) {
        if (lambda <= 0f) return
        val out = idx.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                val c0 = idx[p]
                var bestC = c0
                var bestE = energyAt(idx, w, h, x, y, c0, edge, lambda)
                val cand = HashSet<Int>(5)
                cand.add(c0)
                if (x > 0) cand.add(idx[p - 1])
                if (x + 1 < w) cand.add(idx[p + 1])
                if (y > 0) cand.add(idx[p - w])
                if (y + 1 < h) cand.add(idx[p + w])
                for (c in cand) {
                    val e = energyAt(idx, w, h, x, y, c, edge, lambda)
                    if (e < bestE) { bestE = e; bestC = c }
                }
                out[p] = bestC
            }
        }
        System.arraycopy(out, 0, idx, 0, idx.size)
    }

    private fun energyAt(idx: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int, edge: BooleanArray, lambda: Float): Float {
        var e = 0f
        val p = y * w + x
        fun add(nx: Int, ny: Int) {
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) return
            val q = ny * w + nx
            val wEdge = if (edge[q] || edge[p]) 1.5f else 1f
            if (idx[q] != c) e += lambda * wEdge
        }
        add(x - 1, y); add(x + 1, y); add(x, y - 1); add(x, y + 1)
        return e
    }

    // ---------- Metrics ----------
    private fun measureThreadChangesPer100(idx: IntArray, w: Int, h: Int): Double {
        var changes = 0
        for (y in 0 until h) {
            val serp = (y % 2 == 0)
            if (serp) {
                for (x in 1 until w) if (idx[y * w + x] != idx[y * w + (x - 1)]) changes++
            } else {
                for (x in w - 2 downTo 0) if (idx[y * w + x] != idx[y * w + (x + 1)]) changes++
            }
        }
        val total = w * h
        return changes * 100.0 / max(1, total)
    }

    private fun measureSmallIslandsPer1000(idx: IntArray, w: Int, h: Int): Double {
        val vis = BooleanArray(w * h)
        var small = 0
        val q: ArrayDeque<Int> = ArrayDeque()
        val dirs = intArrayOf(1, -1, w, -w)
        fun onGrid(p: Int) = p >= 0 && p < w * h
        for (start in 0 until w * h) {
            if (vis[start]) continue
            val color = idx[start]
            var count = 0
            q.clear(); q.add(start); vis[start] = true
            while (q.isNotEmpty()) {
                val p = q.removeFirst(); count++
                val x = p % w
                for (d in dirs) {
                    val np = p + d
                    if (!onGrid(np)) continue
                    if ((d == 1 && (x + 1) % w == 0) || (d == -1 && x % w == 0)) continue
                    if (!vis[np] && idx[np] == color) { vis[np] = true; q.add(np) }
                }
            }
            if (count <= 2) small++
        }
        val total = w * h
        return small * 1000.0 / max(1, total)
    }

    private fun measureRunMedian(idx: IntArray, w: Int, h: Int): Double {
        val runs = ArrayList<Int>(w * h / 8)
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                val c = idx[y * w + x]
                var x2 = x + 1
                while (x2 < w && idx[y * w + x2] == c) x2++
                runs.add(x2 - x)
                x = x2
            }
        }
        if (runs.isEmpty()) return 0.0
        runs.sort()
        val m = runs.size / 2
        return if (runs.size % 2 == 1) runs[m].toDouble() else 0.5 * (runs[m - 1] + runs[m])
    }

    // ---------- Legend & symbols ----------
    private fun buildLegend(palette: IntArray, catJsonPath: String?): JSONObject {
        val symbols = Symbolizer.assign(palette.size)
        val legend = JSONObject()
        legend.put("k", palette.size)
        val arr = org.json.JSONArray()
        val cat = parseCatalogMap(catJsonPath)
        for (i in palette.indices) {
            val o = JSONObject()
            o.put("idx", i)
            o.put("rgb", rgbHex(palette[i]))
            o.put("symbol", symbols[i])
            cat[i]?.let { m ->
                o.put("brand", m.brand)
                o.put("type", m.type)          // single | blend
                o.put("code", m.code)
                o.put("name", m.name ?: JSONObject.NULL)
                if (m.type == "blend") {
                    o.put("codeB", m.codeB)
                    o.put("nameB", m.nameB ?: JSONObject.NULL)
                }
            }
            arr.put(o)
        }
        legend.put("entries", arr)
        return legend
    }

    private data class CatMatch(
        val brand: String,
        val type: String,      // single | blend
        val code: String,
        val name: String?,
        val codeB: String? = null,
        val nameB: String? = null
    )

    private fun parseCatalogMap(path: String?): Map<Int, CatMatch> {
        if (path == null) return emptyMap()
        return try {
            val json = File(path).readText()
            val root = JSONObject(json)
            val brand = root.optString("brand", "DMC")
            val items = HashMap<Int, CatMatch>()
            val arr = root.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val idx = e.getInt("idx")
                val type = e.getString("type")
                if (type == "single") {
                    val code = e.getString("code")
                    val name = if (e.has("name") && !e.isNull("name")) e.getString("name") else null
                    items[idx] = CatMatch(brand, type, code, name)
                } else if (type == "blend") {
                    val codeA = e.getString("codeA")
                    val codeB = e.getString("codeB")
                    val nameA = if (e.has("nameA") && !e.isNull("nameA")) e.getString("nameA") else null
                    val nameB = if (e.has("nameB") && !e.isNull("nameB")) e.getString("nameB") else null
                    items[idx] = CatMatch(brand, type, codeA, nameA, codeB, nameB)
                }
            }
            items
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun rgbHex(rgb: Int): String =
        "#%02X%02X%02X".format(Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    // ---------- Preview ----------
    private fun renderPreview(
        idx: IntArray, w: Int, h: Int,
        palette: IntArray,              // цвета ниток (legendRgb)
        legend: JSONObject,
        maxSide: Int = 1024,
        drawGrid: Boolean = true
    ): Bitmap {
        val scale = max(1, min(maxSide / max(w, h), 16))
        val bw = max(1, min(maxSide, w * scale))
        val bh = max(1, min(maxSide, h * scale))
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.color = Color.BLACK
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.MONOSPACE
        text.textSize = (scale * 0.8f).coerceAtLeast(8f)

        val syms = Array(palette.size) { "" }
        run {
            val arr = legend.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val idxi = e.getInt("idx")
                val s = e.getString("symbol")
                if (idxi in syms.indices) syms[idxi] = s.ifEmpty { "•" }
            }
        }

        var p = 0
        for (y in 0 until h) {
            val yy = y * scale
            for (x in 0 until w) {
                val xx = x * scale
                val ci = idx[p++].coerceAtLeast(0).coerceAtMost(palette.lastIndex)
                paint.color = palette[ci]
                val r = Color.red(paint.color)
                val g = Color.green(paint.color)
                val b = Color.blue(paint.color)
                val shade = Color.rgb((r + 255) / 2, (g + 255) / 2, (b + 255) / 2) // чуть бледнее ради контраста символа
                paint.color = shade
                canvas.drawRect(Rect(xx, yy, xx + scale, yy + scale), paint)
                val cx = xx + scale / 2f
                val cy = yy + scale / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
                val symbol = syms[ci].ifEmpty { "•" }
                canvas.drawText(symbol, cx, cy, text)
            }
        }
        if (drawGrid && scale >= 6) {
            val grid = Paint()
            grid.color = 0x33000000
            grid.strokeWidth = 1f
            for (x in 0..w) {
                val xx = x * scale + 0.5f
                canvas.drawLine(xx, 0f, xx, bh.toFloat(), grid)
            }
            for (y in 0..h) {
                val yy = y * scale + 0.5f
                canvas.drawLine(0f, yy, bw.toFloat(), yy, grid)
            }
        }
        return bmp
    }

    /**
     * Если передали «голый» /cache/index.bin или чужой путь — подхватим парный
     * index_<ID>.bin по имени quant_color_<ID>.png и проверим длину w*h.
     */
    private fun resolveIndexPair(indexPath: String, colorPath: String, w: Int, h: Int): String {
        val expect = w.toLong() * h
        val idx = File(indexPath)
        if (idx.exists() && idx.length() == expect) return idx.absolutePath

        val colorName = File(colorPath).name
        val id = colorName.removePrefix("quant_color_").removeSuffix(".png")
        if (id.isNotEmpty() && id != colorName) {
            val paired = File(File(colorPath).parentFile, "index_${id}.bin")
            if (paired.exists() && paired.length() == expect) {
                logKV("PATTERN", "pair.match", mapOf("color" to colorName, "use" to paired.name))
                return paired.absolutePath
            }
        }
        logKV("PATTERN", "pair.miss", mapOf("index" to idx.name, "color" to colorName, "expectPx" to expect, "len" to idx.length()))
        return indexPath
    }
}

/* ---------- STRICT DMC/brand RGB from assets + catalog join ---------- */

/**
 * Читает thread RGB для каждого idx из palette_catalog.json и assets/palettes/<brand>.json.
 * Требования: на КАЖДЫЙ idx есть либо single code, либо blend (codeA+codeB).
 * Иначе кидаем IllegalStateException (никаких подмен/фоллбеков).
 */
private fun readLegendThreadRgbStrict(ctx: Context, catPath: String, k: Int): IntArray {
    val root = JSONObject(File(catPath).readText())
    val catArr = root.optJSONArray("entries")
        ?: throw IllegalStateException("Catalog JSON has no 'entries': $catPath")

    // Бренд из каталога
    val brand = root.optString("brand", "DMC").lowercase()
    val brandFile = when (brand) {
        "dmc"      -> "palettes/dmc.json"
        "anchor"   -> "palettes/anchor.json"
        "toho"     -> "palettes/toho.json"
        "preciosa" -> "palettes/preciosa.json"
        else       -> "palettes/dmc.json"
    }
    val codeToRgb = loadBrandRgbFromAssets(ctx, brandFile) // code -> RGB (Int)

    val out = IntArray(k) { 0 }
    var filled = 0
    for (i in 0 until catArr.length()) {
        val e = catArr.getJSONObject(i)
        val idx = e.getInt("idx")
        if (idx !in 0 until k) throw IllegalStateException("Catalog idx=$idx out of [0,$k)")
        val type = e.optString("type", "single")
        val rgb = when (type) {
            "single" -> {
                val code = e.optString("code", null) ?: throw IllegalStateException("Entry idx=$idx has no 'code'")
                codeToRgb[code] ?: throw IllegalStateException("No RGB for code='$code' in $brandFile")
            }
            "blend" -> {
                val a = e.optString("codeA", null) ?: throw IllegalStateException("Entry idx=$idx has no 'codeA'")
                val b = e.optString("codeB", null) ?: throw IllegalStateException("Entry idx=$idx has no 'codeB'")
                val ra = codeToRgb[a] ?: throw IllegalStateException("No RGB for codeA='$a' in $brandFile")
                val rb = codeToRgb[b] ?: throw IllegalStateException("No RGB for codeB='$b' in $brandFile")
                avgRgb(ra, rb)
            }
            else -> throw IllegalStateException("Unknown type='$type' at idx=$idx")
        }
        out[idx] = rgb
        filled++
    }
    if (filled != k) {
        throw IllegalStateException("Catalog RGB size mismatch: filled=$filled expected=$k (brand=$brand)")
    }
    return out
}

private fun loadBrandRgbFromAssets(ctx: Context, assetPath: String): Map<String, Int> {
    // формат assets: { id,name,type,"colors":[{ "code":"150", "name":"...", "rgb":"B6114C" }, ...] }
    ctx.assets.open(assetPath).use { ins ->
        val txt = ins.bufferedReader().readText()
        val root = JSONObject(txt)
        val arr = root.getJSONArray("colors")
        val map = HashMap<String, Int>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val code = e.getString("code")
            var hex = e.getString("rgb").trim()
            if (!hex.startsWith("#")) hex = "#$hex"
            val rgb = Color.parseColor(hex)
            map[code] = rgb
        }
        return map
    }
}

private fun avgRgb(a: Int, b: Int): Int {
    val r = (Color.red(a) + Color.red(b)) / 2
    val g = (Color.green(a) + Color.green(b)) / 2
    val bl = (Color.blue(a) + Color.blue(b)) / 2
    return Color.rgb(r, g, bl)
}
