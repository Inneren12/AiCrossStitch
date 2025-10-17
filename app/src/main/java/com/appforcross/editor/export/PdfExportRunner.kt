package com.appforcross.editor.export

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.Logger
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream

object PdfExportRunner {

    enum class PageSize { A4, A3 }
    enum class Orientation { PORTRAIT, LANDSCAPE }
    enum class RenderMode { SYMBOLS, COLOR }

    data class Options(
        val page: PageSize = PageSize.A4,
        val orientation: Orientation = Orientation.PORTRAIT,
        val cellSizeMm: Float = 3.0f,
        val marginMm: Float = 10f,
        val overlapCells: Int = 8,
        val boldEvery: Int = 10,
        val render: RenderMode = RenderMode.SYMBOLS,
        val includeLegend: Boolean = true
    )

    data class Output(
        val pdfPath: String,
        val pages: Int,
        val cellsPerPageX: Int,
        val cellsPerPageY: Int
    )

    /** Экспорт в память (для Save As). */
    data class OutputBytes(
        val bytes: ByteArray,
        val pages: Int,
        val cellsPerPageX: Int,
        val cellsPerPageY: Int
    )

    /** Результат превью: bitmap + общее кол-во страниц. */
    data class Preview(val bitmap: Bitmap, val totalPages: Int)

    /** Запуск экспорта.
     *  @param indexBinPath — путь к pattern_index.bin (из PatternRunner)
     *  @param colorPngPath — путь к quant_color.png (для размеров W×H)
     *  @param palette — текущая палитра (Int RGB)
     *  @param legendJsonPath — legend (из PatternRunner), чтобы взять символы и DMC‑коды; можно null
     */
    fun run(
        ctx: Context,
        indexBinPath: String,
        colorPngPath: String,
        palette: IntArray,
        legendJsonPath: String?,
        opt: Options = Options()
    ): Output {
        Logger.i("EXPORT", "start", mapOf(
            "page" to opt.page.toString(), "orient" to opt.orientation.toString(),
            "cell_mm" to opt.cellSizeMm, "margin_mm" to opt.marginMm,
            "overlap" to opt.overlapCells, "mode" to opt.render.toString()
        ))
        // --- размеры сетки
        val bmp = BitmapFactory.decodeFile(colorPngPath)
        require(bmp != null) { "Cannot decode color PNG: $colorPngPath" }
        val W = bmp.width
        val H = bmp.height
        bmp.recycle()
        // --- индексы
        val idx = readIndex(indexBinPath, W, H)
        // --- символы/легенда
        val legend = parseLegend(legendJsonPath, palette.size)
        val symbols: CharArray = legend.symbols
        val dmc: Array<LegendEntry?> = legend.entries
        // --- геометрия PDF
        val (pageWmm, pageHmm) = pageMm(opt.page, opt.orientation)
        val ptPerMm = 72f / 25.4f
        val pageWpt = (pageWmm * ptPerMm).roundToInt()
        val pageHpt = (pageHmm * ptPerMm).roundToInt()
        val marginPt = (opt.marginMm * ptPerMm)
        val cellPt = max(1f, opt.cellSizeMm * ptPerMm)
        val usableWpt = pageWpt - 2 * marginPt
        val usableHpt = pageHpt - 2 * marginPt
        val cellsX = max(1, (usableWpt / cellPt).toInt())
        val cellsY = max(1, (usableHpt / cellPt).toInt())
        val stepX = max(1, cellsX - opt.overlapCells)
        val stepY = max(1, cellsY - opt.overlapCells)
        val pagesX = ceil((W - opt.overlapCells).toDouble() / stepX).toInt().coerceAtLeast(1)
        val pagesY = ceil((H - opt.overlapCells).toDouble() / stepY).toInt().coerceAtLeast(1)
        val pageCount = pagesX * pagesY + (if (opt.includeLegend) 1 else 0)
        // --- PDF
        val pdf = PdfDocument()
        var pageNo = 1
        for (py in 0 until pagesY) {
            for (px in 0 until pagesX) {
                val info = PdfDocument.PageInfo.Builder(pageWpt, pageHpt, pageNo).create()
                val page = pdf.startPage(info)
                val c = page.canvas
                // фон
                c.drawColor(Color.WHITE)
                val ox = marginPt
                val oy = marginPt
                // диапазон клеток на этой странице
                val sx = px * stepX
                val sy = py * stepY
                val ex = min(W, sx + cellsX)
                val ey = min(H, sy + cellsY)
                // рисуем клетки
                drawCells(c, idx, W, H, sx, sy, ex, ey, ox, oy, cellPt, palette, symbols, opt)
                // рамка и заголовок
                drawFrameAndHeader(c, pageWpt.toFloat(), pageHpt.toFloat(), marginPt, pageNo, sx, sy, ex, ey)
                pdf.finishPage(page)
                pageNo++
            }
        }
        if (opt.includeLegend) {
            val info = PdfDocument.PageInfo.Builder(pageWpt, pageHpt, pageNo).create()
            val page = pdf.startPage(info)
            val c = page.canvas
            c.drawColor(Color.WHITE)
            drawLegendPage(c, palette, symbols, dmc, marginPt, pageWpt.toFloat(), pageHpt.toFloat())
            drawFooter(c, pageWpt.toFloat(), pageHpt.toFloat(), "Legend")
            pdf.finishPage(page)
        }
        // --- save
        val out = File(ctx.cacheDir, "pattern_export.pdf")
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
        // diag copy
        try { DiagnosticsManager.currentSessionDir(ctx)?.let { out.copyTo(File(it, out.name), overwrite = true) } } catch (_: Exception) {}
        Logger.i("EXPORT", "done", mapOf(
            "pdf" to out.absolutePath, "pages" to pageCount,
            "cellsX" to cellsX, "cellsY" to cellsY
        ))
        return Output(out.absolutePath, pageCount, cellsX, cellsY)
    }

    /** Экспорт в байты (для Save As через SAF). */
    fun runToBytes(
        ctx: Context,
        indexBinPath: String,
        colorPngPath: String,
        palette: IntArray,
        legendJsonPath: String?,
        opt: Options = Options()
    ): OutputBytes {
        val bmp = BitmapFactory.decodeFile(colorPngPath)
        require(bmp != null) { "Cannot decode color PNG: $colorPngPath" }
        val W = bmp.width
        val H = bmp.height
        bmp.recycle()
        val idx = readIndex(indexBinPath, W, H)
        val legend = parseLegend(legendJsonPath, palette.size)
        val symbols = legend.symbols
        val (pageWmm, pageHmm) = pageMm(opt.page, opt.orientation)
        val ptPerMm = 72f / 25.4f
        val pageWpt = (pageWmm * ptPerMm).roundToInt()
        val pageHpt = (pageHmm * ptPerMm).roundToInt()
        val marginPt = (opt.marginMm * ptPerMm)
        val cellPt = max(1f, opt.cellSizeMm * ptPerMm)
        val usableWpt = pageWpt - 2 * marginPt
        val usableHpt = pageHpt - 2 * marginPt
        val cellsX = max(1, (usableWpt / cellPt).toInt())
        val cellsY = max(1, (usableHpt / cellPt).toInt())
        val stepX = max(1, cellsX - opt.overlapCells)
        val stepY = max(1, cellsY - opt.overlapCells)
        val pagesX = ceil((W - opt.overlapCells).toDouble() / stepX).toInt().coerceAtLeast(1)
        val pagesY = ceil((H - opt.overlapCells).toDouble() / stepY).toInt().coerceAtLeast(1)
        val pageCount = pagesX * pagesY + (if (opt.includeLegend) 1 else 0)
        val pdf = PdfDocument()
        var pageNo = 1
        for (py in 0 until pagesY) {
            for (px in 0 until pagesX) {
                val info = PdfDocument.PageInfo.Builder(pageWpt, pageHpt, pageNo).create()
                val page = pdf.startPage(info)
                val c = page.canvas
                c.drawColor(Color.WHITE)
                val ox = marginPt
                val oy = marginPt
                val sx = px * stepX
                val sy = py * stepY
                val ex = min(W, sx + cellsX)
                val ey = min(H, sy + cellsY)
                drawCells(c, idx, W, H, sx, sy, ex, ey, ox, oy, cellPt, palette, symbols, opt)
                drawFrameAndHeader(c, pageWpt.toFloat(), pageHpt.toFloat(), marginPt, pageNo, sx, sy, ex, ey)
                pdf.finishPage(page); pageNo++
            }
        }
        if (opt.includeLegend) {
            val info = PdfDocument.PageInfo.Builder(pageWpt, pageHpt, pageNo).create()
            val page = pdf.startPage(info)
            val c = page.canvas
            c.drawColor(Color.WHITE)
            drawLegendPage(c, palette, symbols, legend.entries, marginPt, pageWpt.toFloat(), pageHpt.toFloat())
            drawFooter(c, pageWpt.toFloat(), pageHpt.toFloat(), "Legend")
            pdf.finishPage(page)
        }
        val baos = ByteArrayOutputStream()
        pdf.writeTo(baos)
        pdf.close()
        return OutputBytes(baos.toByteArray(), pageCount, cellsX, cellsY)
    }

    /** Рендер превью выбранной страницы в Bitmap (масштабируется под maxSidePx). 1‑based pageIndex. */
    fun renderPreviewBitmap(
        ctx: Context,
        indexBinPath: String,
        colorPngPath: String,
        palette: IntArray,
        legendJsonPath: String?,
        opt: Options = Options(),
        pageIndex: Int = 1,
        maxSidePx: Int = 1600
    ): Preview {
        val bmp = BitmapFactory.decodeFile(colorPngPath)
        require(bmp != null) { "Cannot decode color PNG: $colorPngPath" }
        val W = bmp.width
        val H = bmp.height
        bmp.recycle()
        val idx = readIndex(indexBinPath, W, H)
        val legend = parseLegend(legendJsonPath, palette.size)
        val symbols = legend.symbols
        val (pageWmm, pageHmm) = pageMm(opt.page, opt.orientation)
        val ptPerMm = 72f / 25.4f
        val pageWpt = (pageWmm * ptPerMm).roundToInt()
        val pageHpt = (pageHmm * ptPerMm).roundToInt()
        val marginPt = (opt.marginMm * ptPerMm)
        val cellPt = max(1f, opt.cellSizeMm * ptPerMm)
        val usableWpt = pageWpt - 2 * marginPt
        val usableHpt = pageHpt - 2 * marginPt
        val cellsX = max(1, (usableWpt / cellPt).toInt())
        val cellsY = max(1, (usableHpt / cellPt).toInt())
        val stepX = max(1, cellsX - opt.overlapCells)
        val stepY = max(1, cellsY - opt.overlapCells)
        val pagesX = ceil((W - opt.overlapCells).toDouble() / stepX).toInt().coerceAtLeast(1)
        val pagesY = ceil((H - opt.overlapCells).toDouble() / stepY).toInt().coerceAtLeast(1)
        val totalPages = pagesX * pagesY + (if (opt.includeLegend) 1 else 0)
        val clamped = pageIndex.coerceIn(1, max(1, totalPages))
        // масштаб для превью
        val scale = min(maxSidePx / pageWpt.toFloat(), maxSidePx / pageHpt.toFloat()).coerceAtLeast(1f)
        val bw = (pageWpt * scale).roundToInt()
        val bh = (pageHpt * scale).roundToInt()
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        if (clamped <= pagesX * pagesY) {
            val i0 = clamped - 1
            val py = i0 / pagesX
            val px = i0 % pagesX
            val sx = px * stepX
            val sy = py * stepY
            val ex = min(W, sx + cellsX)
            val ey = min(H, sy + cellsY)
            val ox = marginPt * scale
            val oy = marginPt * scale
            val cell = cellPt * scale
            drawCells(c, idx, W, H, sx, sy, ex, ey, ox, oy, cell, palette, symbols, opt)
            drawFrameAndHeader(c, pageWpt * scale, pageHpt * scale, marginPt * scale, clamped, sx, sy, ex, ey)
        } else {
            drawLegendPage(c, palette, symbols, legend.entries, marginPt * scale, pageWpt * scale, pageHpt * scale)
            drawFooter(c, pageWpt * scale, pageHpt * scale, "Legend")
        }
        return Preview(out, totalPages)
    }

    // --------- draw helpers ----------
    private fun drawCells(
        canvas: Canvas,
        idx: IntArray, W: Int, H: Int,
        sx: Int, sy: Int, ex: Int, ey: Int,
        ox: Float, oy: Float, cell: Float,
        palette: IntArray,
        symbols: CharArray,
        opt: Options
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.typeface = Typeface.MONOSPACE
        text.textAlign = Paint.Align.CENTER
        text.textSize = max(8f, cell * 0.75f)
        val grid = Paint()
        grid.color = 0x22000000
        grid.strokeWidth = 0.8f
        val gridBold = Paint()
        gridBold.color = 0x88000000.toInt()
        gridBold.strokeWidth = 1.6f
        var p = sy * W + sx
        for (y in sy until ey) {
            val yy = oy + (y - sy) * cell
            for (x in sx until ex) {
                val xx = ox + (x - sx) * cell
                val ci = idx[y * W + x].coerceAtLeast(0).coerceAtMost(palette.lastIndex)
                if (opt.render == RenderMode.COLOR) {
                    // полутоновая заливка, чтобы символы (если будут) были читабельны
                    val c = palette[ci]
                    val r = (Color.red(c) + 255) / 2
                    val g = (Color.green(c) + 255) / 2
                    val b = (Color.blue(c) + 255) / 2
                    paint.color = Color.rgb(r, g, b)
                    canvas.drawRect(xx, yy, xx + cell, yy + cell, paint)
                } else {
                    // светлая заливка для контраста символов
                    paint.color = 0xFFF7F7F7.toInt()
                    canvas.drawRect(xx, yy, xx + cell, yy + cell, paint)
                    text.color = Color.BLACK
                    val cx = xx + cell / 2f
                    val cy = yy + cell / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f
                    val ch = symbols.getOrNull(ci) ?: '•'
                    canvas.drawText(ch.toString(), cx, cy, text)
                }
            }
        }
        // grid
        val cols = ex - sx
        val rows = ey - sy
        for (i in 0..cols) {
            val x = ox + i * cell
            val bold = ( (sx + i) % opt.boldEvery == 0 )
            canvas.drawLine(x, oy, x, oy + rows * cell, if (bold) gridBold else grid)
        }
        for (j in 0..rows) {
            val y = oy + j * cell
            val bold = ( (sy + j) % opt.boldEvery == 0 )
            canvas.drawLine(ox, y, ox + cols * cell, y, if (bold) gridBold else grid)
        }
    }

    private fun drawFrameAndHeader(
        canvas: Canvas,
        pageW: Float, pageH: Float,
        margin: Float,
        pageNo: Int,
        sx: Int, sy: Int, ex: Int, ey: Int
    ) {
        val frame = Paint()
        frame.style = Paint.Style.STROKE
        frame.color = 0xFF000000.toInt()
        frame.strokeWidth = 1.2f
        canvas.drawRect(margin, margin, pageW - margin, pageH - margin, frame)
        val t = Paint(Paint.ANTI_ALIAS_FLAG)
        t.color = 0xFF000000.toInt()
        t.textSize = 10f
        t.typeface = Typeface.MONOSPACE
        val header = "Page $pageNo  —  cells X:${sx+1}-${ex}  Y:${sy+1}-${ey}"
        canvas.drawText(header, margin + 2f, margin - 4f + t.textSize, t)
        drawFooter(canvas, pageW, pageH, "AiCrossStitch")
    }

    private fun drawFooter(canvas: Canvas, pageW: Float, pageH: Float, text: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x88000000.toInt()
        p.textSize = 9f
        p.typeface = Typeface.MONOSPACE
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, pageW - 8f, pageH - 6f, p)
    }

    private fun drawLegendPage(
        canvas: Canvas,
        palette: IntArray,
        symbols: CharArray,
        dmc: Array<LegendEntry?>,
        margin: Float,
        pageW: Float,
        pageH: Float
    ) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG)
        title.textSize = 16f
        title.typeface = Typeface.DEFAULT_BOLD
        title.color = Color.BLACK
        canvas.drawText("Legend", margin, margin + title.textSize, title)
        val yStart = margin + title.textSize + 8f
        // столбцы по 14–18 строк, исходя из высоты
        val rowH = 18f
        val rowsPerCol = max(14, ((pageH - yStart - margin) / rowH).toInt())
        val cols = ceil(palette.size / rowsPerCol.toDouble()).toInt().coerceAtLeast(1)
        val colW = (pageW - 2 * margin) / cols

        val swatch = Paint()
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.textSize = 11f
        text.typeface = Typeface.MONOSPACE
        val sym = Paint(Paint.ANTI_ALIAS_FLAG)
        sym.textSize = 12f
        sym.typeface = Typeface.MONOSPACE
        sym.textAlign = Paint.Align.CENTER

        var i = 0
        for (col in 0 until cols) {
            val x0 = margin + col * colW
            var y = yStart
            for (r in 0 until rowsPerCol) {
                if (i >= palette.size) break
                val c = palette[i]
                // квадратик-образец
                swatch.color = c
                val rX = x0
                val rW = 14f
                canvas.drawRect(rX, y - 12f, rX + rW, y + 4f, swatch)
                // символ
                val sX = rX + rW + 10f
                sym.color = Color.BLACK
                canvas.drawText((symbols.getOrNull(i) ?: '•').toString(), sX + 6f, y, sym)
                // код и имя
                val infoX = sX + 18f
                val le = dmc.getOrNull(i)
                val line = if (le != null) {
                    if (le.type == "blend" && le.codeB != null) {
                        "DMC ${le.code}+${le.codeB} ${le.name ?: ""}".trim()
                    } else {
                        "DMC ${le.code} ${le.name ?: ""}".trim()
                    }
                } else {
                    "#%02X%02X%02X".format(Color.red(c), Color.green(c), Color.blue(c))
                }
                canvas.drawText(line, infoX, y, text)
                y += rowH
                i++
            }
        }
    }

    // --------- legend parsing ----------
    private data class LegendEntry(
        val type: String,      // single | blend
        val code: String,
        val name: String?,
        val codeB: String? = null,
        val nameB: String? = null
    )
    private data class LegendParsed(
        val symbols: CharArray,
        val entries: Array<LegendEntry?>
    )
    private fun parseLegend(path: String?, k: Int): LegendParsed {
        val symbols = CharArray(k) { '•' }
        val entries = arrayOfNulls<LegendEntry>(k)
        if (path == null) return LegendParsed(symbols, entries)
        return try {
            val root = JSONObject(File(path).readText())
            val arr = root.getJSONArray("entries")
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val idx = e.getInt("idx")
                if (idx in 0 until k) {
                    val s = e.optString("symbol", "•")
                    symbols[idx] = s.firstOrNull() ?: '•'
                    val type = e.optString("type", "single")
                    if (type == "single") {
                        val code = e.optString("code", "")
                        val name = if (e.has("name") && !e.isNull("name")) e.optString("name") else null
                        entries[idx] = LegendEntry(type, code, name, null, null)
                    } else if (type == "blend") {
                        val a = e.optString("code", e.optString("codeA", ""))
                        val b = e.optString("codeB", "")
                        val nameA = if (e.has("name") && !e.isNull("name")) e.optString("name") else e.optString("nameA", null)
                        val nameB = if (e.has("nameB") && !e.isNull("nameB")) e.optString("nameB") else null
                        entries[idx] = LegendEntry("blend", a, nameA, b, nameB)
                    }
                }
            }
            LegendParsed(symbols, entries)
        } catch (_: Exception) {
            LegendParsed(symbols, entries)
        }
    }

    // --------- utils ----------
    private fun pageMm(ps: PageSize, o: Orientation): Pair<Float, Float> {
        val mm = when (ps) {
            PageSize.A4 -> 210f to 297f
            PageSize.A3 -> 297f to 420f
        }
        return if (o == Orientation.PORTRAIT) mm else (mm.second to mm.first)
    }
    private fun readIndex(path: String, w: Int, h: Int): IntArray {
        val b = File(path).readBytes()
        require(b.size >= w * h) { "index.bin too small: ${b.size} < ${w*h}" }
        val out = IntArray(w * h)
        var p = 0
        for (i in 0 until w * h) out[p++] = b[i].toInt() and 0xFF
        return out
    }
}