package com.appforcross.editor.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

object TestImages {
    fun genChecker(
        w: Int,
        h: Int,
        cell: Int,
        c1: Int = Color.BLACK,
        c2: Int = Color.WHITE
    ): Bitmap {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val ry = (y / cell) % 2
            for (x in 0 until w) {
                val rx = (x / cell) % 2
                bm.setPixel(x, y, if ((rx xor ry) == 0) c1 else c2)
            }
        }
        return bm
    }

    fun savePng(bm: Bitmap, out: File) {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { stream ->
            bm.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}
