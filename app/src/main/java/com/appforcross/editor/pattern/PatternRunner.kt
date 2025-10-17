package com.appforcross.editor.pattern

import android.content.Context
import android.graphics.*
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.Logger
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.math.*

object PatternRunner {

    data class Options(
        val minRunFlat: Int = 4,       // минимальная длина прогона на плоских/в целом
        val minRunEdge: Int = 3,       // минимальная длина прогона на кромках (щадяще)
        val islandKill: Int = 2,       // удалить островки < N клеток
        val crfLambda: Float = 0.8f,   // сила Поттса: 0..1 (чем выше — меньше «конфетти»)
        val previewMaxPx: Int = 1024,  // предел по большей стороне для png превью
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

    /** Главный метод: берём индексную карту + палитру, чистим топологию, назначаем символы и отрисовываем превью. */
    fun run(
        ctx: Context,
        palette: IntArray,
        indexBinPath: String,
        colorPngPath: String,
        catalogJsonPath: String? = null,
        opt: Options = Options()
    ): Output {
        Logger.i("PATTERN", "start", mapOf("k" to palette.size, "index" to indexBinPath, "color" to colorPngPath))
        // 0) читаем размеры из цветного PNG и индексы из bin
        val bmp = BitmapFactory.decodeFile(colorPngPath)
        require(bmp != null) { "Cannot decode color PNG: $colorPngPath" }
        val w = bmp.width
        val h = bmp.height
        val idx = readIndexBin(indexBinPath, w, h)
        // 1) быстрая edge‑маска по цветному PNG
        val edgeMask = makeEdgeMask(bmp)
        bmp.recycle()
        // 2) чистка: min‑run (строки/столбцы), island‑kill, CRF‑пасс
        val idx1 = idx.copyOf()
        minRunSmoothing(idx1, w, h, edgeMask, opt.minRunFlat, opt.minRunEdge)
        islandCleanup(idx1, w, h, opt.islandKill)
        crfPottsPass(idx1, w, h, edgeMask, opt.crfLambda)
        // 3) метрики
        val changesPer100 = measureThreadChangesPer100(idx1, w, h)
        val islandsPer1000 = measureSmallIslandsPer1000(idx1, w, h)
        val runMed = measureRunMedian(idx1, w, h)
        // 4) символизация + легенда (подтягиваем коды из catalog JSON, если есть)
        val legend = buildLegend(palette, catalogJsonPath)
        val legendFile = File(ctx.cacheDir, "pattern_legend.json")
        FileOutputStream(legendFile).use { it.write(legend.toString().toByteArray()) }
        // 5) сохраняем итоговую индексную карту
        val outIndex = File(ctx.cacheDir, "pattern_index.bin")
        FileOutputStream(outIndex).use { fos ->
            val buf = ByteArray(w * h)
            var p = 0
            for (i in 0 until idx1.size) buf[p++] = idx1[i].coerceIn(0, palette.lastIndex).toByte()
            fos.write(buf, 0, p)
        }
        // 6) превью
        val prev = renderPreview(idx1, w, h, palette, legend, maxSide = opt.previewMaxPx, drawGrid = opt.drawGrid)
        val prevFile = File(ctx.cacheDir, "pattern_preview.png")
        FileOutputStream(prevFile).use { prev.compress(Bitmap.CompressFormat.PNG, 100, it) }
        prev.recycle()
        // 7) diag‑копии и логи
        try {
            DiagnosticsManager.currentSessionDir(ctx)?.let { d ->
                legendFile.copyTo(File(d, legendFile.name), overwrite = true)
                outIndex.copyTo(File(d, outIndex.name), overwrite = true)
                prevFile.copyTo(File(d, prevFile.name), overwrite = true)
            }
        } catch (_: Exception) {}
        Logger.i("PATTERN", "done", mapOf(
            "index" to outIndex.absolutePath,
            "legend" to legendFile.absolutePath,
            "preview" to prevFile.absolutePath,
            "changesPer100" to "%.2f".format(changesPer100),
            "islandsPer1000" to "%.2f".format(islandsPer1000),
            "runMed" to "%.2f".format(runMed)
        ))
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
    private fun readIndexBin(path: String, w: Int, h: Int): IntArray {
        val bytes = File(path).readBytes()
        require(bytes.size >= w * h) { "index.bin size ${bytes.size} < w*h=${w*h}" }
        val out = IntArray(w * h)
        var p = 0
        for (i in 0 until w * h) out[p++] = bytes[i].toInt() and 0xFF
        return out
    }

    // ---------- Edge mask ----------
    private fun makeEdgeMask(src: Bitmap): BooleanArray {
        val w = src.width; val h = src.height
        val out = BooleanArray(w * h)
        val row = IntArray(w)
        // простой градиент по яркости
        fun lum(c: Int): Int = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)).toInt()
        val L = IntArray(w * h)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) L[y * w + x] = lum(row[x])
        }
        // порог — перцентиль 85 от |∇|
        val mags = ArrayList<Int>(w * h)
        for (y in 1 until h) for (x in 1 until w) {
            val gx = L[y * w + x] - L[y * w + (x - 1)]
            val gy = L[y * w + x] - L[(y - 1) * w + x]
            mags.add(abs(gx) + abs(gy))
        }
        mags.sort()
        val thr = mags[ (mags.size * 0.85).toInt().coerceIn(0, mags.lastIndex) ]
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = L[y * w + x] - L[y * w + (x - 1)]
            val gy = L[y * w + x] - L[(y - 1) * w + x]
            out[y * w + x] = (abs(gx) + abs(gy)) >= thr
        }
        return out
    }

    // ---------- Topology passes ----------
    private fun minRunSmoothing(idx: IntArray, w: Int, h: Int, edge: BooleanArray, minFlat: Int, minEdge: Int) {
        // по строкам
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
        // по столбцам
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
            // BFS компонент
            var count = 0
            q.clear()
            q.add(start)
            vis[start] = true
            val comp = ArrayList<Int>(16)
            val borderNeighbors = HashMap<Int, Int>(8) // цвет → частота
            while (!q.isEmpty()) {
                val p = q.removeFirst()
                comp.add(p); count++
                val y = p / w; val x = p % w
                for (d in dirs) {
                    val np = p + d
                    if (!onGrid(np)) continue
                    // проверка границ по строкам при ±1
                    if ((d == 1 && (x + 1) % w == 0) || (d == -1 && x % w == 0)) continue
                    if (!vis[np]) {
                        if (idx[np] == color) {
                            vis[np] = true; q.add(np)
                        } else {
                            borderNeighbors[idx[np]] = (borderNeighbors[idx[np]] ?: 0) + 1
                        }
                    }
                }
            }
            if (count in 1 until killBelow) {
                // заменить на наиболее частого соседа
                val repl = borderNeighbors.entries.maxByOrNull { it.value }?.key
                if (repl != null) {
                    for (p in comp) idx[p] = repl
                }
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
                // кандидаты — текущий и цвета соседей (до 4)
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
        // простая Поттс‑энергия: штраф за несовпадение с соседями, усиленный на кромках
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
                for (x in 1 until w) {
                    if (idx[y * w + x] != idx[y * w + (x - 1)]) changes++
                }
            } else {
                for (x in w - 2 downTo 0) {
                    if (idx[y * w + x] != idx[y * w + (x + 1)]) changes++
                }
            }
        }
        val total = w * h
        return changes * 100.0 / max(1, total)
    }

    private fun measureSmallIslandsPer1000(idx: IntArray, w: Int, h: Int): Double {
        // считаем 1‑2 клеточные компоненты
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
            while (!q.isEmpty()) {
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
            o.put("symbol", symbols[i].toString())
            cat[i]?.let { m -> // может быть Single или Blend
                o.put("brand", m.brand)
                o.put("type", m.type)
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
        palette: IntArray,
        legend: JSONObject,
        maxSide: Int = 1024,
        drawGrid: Boolean = true
    ): Bitmap {
        // вычислим масштаб клетки
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
        val syms = CharArray(palette.size) { ' ' }
        run {
            val arr = legend.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val idxi = e.getInt("idx")
                val s = e.getString("symbol")
                syms[idxi] = s.firstOrNull() ?: '•'
            }
        }
        // клетки
        var p = 0
        for (y in 0 until h) {
            val yy = y * scale
            for (x in 0 until w) {
                val xx = x * scale
                val ci = idx[p++].coerceAtLeast(0).coerceAtMost(palette.lastIndex)
                paint.color = palette[ci]
                // заливка с высоким альфа для светлых: слегка бледним, чтобы символ был контрастнее
                val r = Color.red(paint.color)
                val g = Color.green(paint.color)
                val b = Color.blue(paint.color)
                val shade = Color.rgb(
                    (r + 255) / 2,
                    (g + 255) / 2,
                    (b + 255) / 2
                )
                paint.color = shade
                canvas.drawRect(Rect(xx, yy, xx + scale, yy + scale), paint)
                // символ
                val cx = xx + scale / 2f
                val cy = yy + scale / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
                canvas.drawText(syms[ci].toString(), cx, cy, text)
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
}
