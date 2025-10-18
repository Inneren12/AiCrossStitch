package com.appforcross.editor.pipeline

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashSet

object IndexIo {
    fun readIndexBin(path: String, w: Int, h: Int): IntArray {
        val file = File(path)
        val pixels = w * h
        val length = file.length()
        val elemSize = when (length) {
            pixels.toLong() -> 1
            pixels * 2L -> 2
            pixels * 4L -> 4
            else -> error("index.len=$length px=$pixels")
        }
        val buffer = ByteArray(pixels * elemSize)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read <= 0) break
                offset += read
            }
        }
        val bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val out = IntArray(pixels)
        when (elemSize) {
            1 -> for (i in 0 until pixels) out[i] = bb.get().toInt() and 0xFF
            2 -> for (i in 0 until pixels) out[i] = bb.getShort().toInt() and 0xFFFF
            else -> for (i in 0 until pixels) out[i] = bb.getInt()
        }
        return out
    }

    data class Stats(val min: Int, val max: Int, val unique: Int)

    fun stats(a: IntArray): Stats {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        val seen = HashSet<Int>()
        for (v in a) {
            if (v < min) min = v
            if (v > max) max = v
            if (seen.size < 4096) {
                seen += v
            }
        }
        if (min == Int.MAX_VALUE) min = 0
        if (max == Int.MIN_VALUE) max = 0
        return Stats(min, max, seen.size)
    }
}
