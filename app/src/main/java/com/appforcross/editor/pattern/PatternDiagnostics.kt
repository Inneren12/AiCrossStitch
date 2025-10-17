package com.appforcross.editor.pattern

import android.graphics.BitmapFactory
import com.appforcross.editor.logging.Logger
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.appforcross.editor.logging.LogcatKV

/**
 * Диагностика согласованности данных между quant_color.png, pattern_index.bin и палитрой.
 * Пишет подробные логи через Logger (теги: DIAG).
 */
object PatternDiagnostics {

    data class IndexStats(
        val elemSizeBytes: Int,
        val bytesRead: Long,
        val minIndex: Int,
        val maxIndex: Int,
        val sampleUnique: Int
    )

    /**
     * Главная точка входа. Безопасно к null — просто логирует, что отсутствует вход.
     *
     * @param quantColorPath путь к quant_color.png (S7 выход)
     * @param indexBinPath   путь к pattern_index.bin (S9 выход)
     * @param paletteSize    размер палитры (K), если известен
     */
    fun logQuantAndIndexConsistency(
        quantColorPath: String?,
        indexBinPath: String?,
        paletteSize: Int?
    ) {
        val meta = mutableMapOf<String, Any?>(
            "quant.path" to quantColorPath,
            "index.path" to indexBinPath,
            "palette.size" to (paletteSize ?: -1)
        )
        // --- размеры quant_color.png ---
        if (quantColorPath != null) {
            val (qw, qh) = readImageSize(quantColorPath)
            meta["quant.w"] = qw
            meta["quant.h"] = qh
            if (qw <= 0 || qh <= 0) {
                Logger.w("DIAG", "quant.read.fail", mapOf("path" to quantColorPath))
            }
        } else {
            Logger.w("DIAG", "quant.missing", emptyMap())
        }

        // --- проверка pattern_index.bin ---
        if (indexBinPath != null) {
            val f = File(indexBinPath)
            meta["index.exists"] = f.exists()
            if (!f.exists()) {
                Logger.w("DIAG", "index.missing", meta)
                LogcatKV.w("DIAG", "index.missing", meta)
                Logger.i("DIAG", "preview.consistency", meta)
                LogcatKV.i("DIAG", "preview.consistency", meta)
                return
            }
            meta["index.len"] = f.length()

            val qw = meta["quant.w"] as? Int ?: -1
            val qh = meta["quant.h"] as? Int ?: -1
            val px = if (qw > 0 && qh > 0) qw * qh else -1
            meta["expect.px"] = px

            val elem = guessElemSizeBytes(f.length(), px)
            meta["index.elemSize"] = elem
            if (elem == 0) {
                Logger.w("DIAG", "index.size.mismatch", meta)
                LogcatKV.w("DIAG", "index.size.mismatch", meta)
                Logger.i("DIAG", "preview.consistency", meta)
                LogcatKV.i("DIAG", "preview.consistency", meta)
                return
            }

            val stats = computeIndexStats(f, elem)
            meta["index.min"] = stats.minIndex
            meta["index.max"] = stats.maxIndex
            meta["index.sampleUnique"] = stats.sampleUnique
            meta["index.bytesRead"] = stats.bytesRead

            val pal = paletteSize ?: -1
            if (pal > 0) {
                val inRange = stats.minIndex >= 0 && stats.maxIndex < pal
                val msg = if (inRange) "index.range.ok" else "index.range.out"
                val m = meta + ("palette.size" to pal)
                Logger.i("DIAG", msg, m)
                LogcatKV.i("DIAG", msg, m)
            }
        } else {
            Logger.w("DIAG", "index.null.path", emptyMap())
        }

        Logger.i("DIAG", "preview.consistency", meta)
        LogcatKV.i("DIAG", "preview.consistency", meta)
        /**
         * Полная проверка: размеры quant_color.png + СРАЗУ ДВА индексных файла:
         * - quantIndex (S7), ищется как соседний с quant_color.png: <dir>/index.bin
         * - patternIndex (S9), путь передаётся явно
         */
        fun logFullConsistency(
            quantColorPath: String?,
            patternIndexPath: String?,
            paletteSizeQuant: Int?
        ) {
            val root = mutableMapOf<String, Any?>(
                "quant.path" to quantColorPath,
                "pIndex.path" to patternIndexPath,
                "palette.quant" to (paletteSizeQuant ?: -1)
            )
            // размеры quant_color.png
            if (quantColorPath != null) {
                val (qw, qh) = readImageSize(quantColorPath)
                root["quant.w"] = qw; root["quant.h"] = qh
                if (qw <= 0 || qh <= 0) {
                    Logger.w("DIAG","quant.read.fail", mapOf("path" to quantColorPath))
                    LogcatKV.w("DIAG","quant.read.fail", mapOf("path" to quantColorPath))
                }
            }
            // квант-индекс как соседний index.bin
            val qIndexPath = quantColorPath?.let { File(it).parentFile?.resolve("index.bin")?.absolutePath }
            if (qIndexPath != null && File(qIndexPath).exists()) {
                val px = (root["quant.w"] as? Int ?: -1) * (root["quant.h"] as? Int ?: -1)
                val qStats = computeIndexStatsForFile(qIndexPath, px)
                root["qIndex.path"] = qIndexPath
                root["qIndex.len"] = qStats.bytesRead
                root["qIndex.elem"] = qStats.elemSizeBytes
                root["qIndex.min"]  = qStats.minIndex
                root["qIndex.max"]  = qStats.maxIndex
                root["qIndex.unique"] = qStats.sampleUnique
                Logger.i("DIAG","quant.index.stats", root)
                LogcatKV.i("DIAG","quant.index.stats", root)
            } else {
                    Logger.w("DIAG","quant.index.missing", mapOf("qIndex.path" to qIndexPath))
                LogcatKV.w("DIAG","quant.index.missing", mapOf("qIndex.path" to qIndexPath))
            }
            // паттерн-индекс
            if (patternIndexPath != null && File(patternIndexPath).exists()) {
                val px = (root["quant.w"] as? Int ?: -1) * (root["quant.h"] as? Int ?: -1)
                val pStats = computeIndexStatsForFile(patternIndexPath, px)
                root["pIndex.len"] = pStats.bytesRead
                root["pIndex.elem"] = pStats.elemSizeBytes
                root["pIndex.min"]  = pStats.minIndex
                root["pIndex.max"]  = pStats.maxIndex
                root["pIndex.unique"] = pStats.sampleUnique
                Logger.i("DIAG","pattern.index.stats", root)
                LogcatKV.i("DIAG","pattern.index.stats", root)
            } else {
                    Logger.w("DIAG","pattern.index.missing", mapOf("pIndex.path" to patternIndexPath))
                LogcatKV.w("DIAG","pattern.index.missing", mapOf("pIndex.path" to patternIndexPath))
                }
            Logger.i("DIAG","full.consistency", root)
            LogcatKV.i("DIAG","full.consistency", root)
        }
    }

    private fun readImageSize(path: String): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        return (opts.outWidth to opts.outHeight)
    }

    private fun guessElemSizeBytes(fileLen: Long, pxCount: Int): Int {
        if (pxCount <= 0) return 0
        return when (fileLen) {
            pxCount.toLong() -> 1
            (pxCount.toLong() * 2) -> 2
            (pxCount.toLong() * 4) -> 4
            else -> 0
        }
    }

    private fun computeIndexStats(file: File, elemSize: Int): IndexStats {
        var minVal = Int.MAX_VALUE
        var maxVal = Int.MIN_VALUE
        val seen = HashSet<Int>()
        var bytesRead = 0L
        FileInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            var read = ins.read(buf)
            while (read > 0) {
                bytesRead += read
                when (elemSize) {
                    1 -> {
                        for (i in 0 until read) {
                            val v = buf[i].toInt() and 0xFF
                            if (v < minVal) minVal = v
                            if (v > maxVal) maxVal = v
                            if (seen.size < 2048) seen += v
                        }
                    }
                    2 -> {
                        val bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                        while (bb.remaining() >= 2) {
                            val v = bb.short.toInt() and 0xFFFF
                            if (v < minVal) minVal = v
                            if (v > maxVal) maxVal = v
                            if (seen.size < 2048) seen += v
                        }
                    }
                    else -> { // 4
                        val bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                        while (bb.remaining() >= 4) {
                            val v = bb.int
                            if (v < minVal) minVal = v
                            if (v > maxVal) maxVal = v
                            if (seen.size < 2048) seen += v
                        }
                    }
                                    }
                read = ins.read(buf)
            }
        }
        if (minVal == Int.MAX_VALUE) minVal = 0
        if (maxVal == Int.MIN_VALUE) maxVal = 0
        return IndexStats(elemSize, bytesRead, minVal, maxVal, seen.size)
    }
    private fun computeIndexStatsForFile(path: String, pxCount: Int): IndexStats {
        val f = File(path)
        val elem = guessElemSizeBytes(f.length(), pxCount)
        return computeIndexStats(f, elem)
    }
}