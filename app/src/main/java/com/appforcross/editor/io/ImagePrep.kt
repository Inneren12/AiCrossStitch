package com.appforcross.editor.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import com.appforcross.editor.color.ColorMgmt
import com.appforcross.editor.color.HdrTonemap
import com.appforcross.editor.filters.Deblocking8x8
import com.appforcross.editor.filters.HaloRemoval
import com.appforcross.editor.logging.Logger

/** Этап 2: загрузка → цвет → HDR → deblocking → halo. Возвращает linear sRGB RGBA_F16. */
object ImagePrep {

    data class PrepResult(
        val linearF16: Bitmap,
        val srcColorSpace: ColorSpace?,
        val iccConfidence: Boolean,
        val wasHdrTonemapped: Boolean,
        val blockiness: Deblocking8x8.Blockiness,
        val haloScore: Float
    )

    /**
     * Основной входной шаг пайплайна.
     * @param deblockThreshold порог блокинга (средний) для включения сглаживания, по умолчанию 0.008
     */
    fun prepare(ctx: Context, uri: android.net.Uri, deblockThreshold: Float = 0.008f): PrepResult {
        // 1) Декод
        val dec = Decoder.decodeUri(ctx, uri)
        // 2) В линейный sRGB (F16)
        val linear = ColorMgmt.toLinearSrgbF16(dec.bitmap, dec.colorSpace)
        // 3) HDR тонмап при необходимости (PQ/HLG)
        val wasHdr = HdrTonemap.applyIfNeeded(linear, dec.colorSpace)
        // 4) Blockiness + deblock
        val blk = Deblocking8x8.measureBlockinessLinear(linear)
        if (blk.mean >= deblockThreshold) {
            Deblocking8x8.weakDeblockInPlaceLinear(linear, strength = 0.5f)
            Logger.i("FILTER", "deblock.applied", mapOf("mean" to blk.mean, "v" to blk.vertical, "h" to blk.horizontal, "strength" to 0.5))
        } else {
            Logger.i("FILTER", "deblock.skipped", mapOf("mean" to blk.mean))
        }
        // 5) Halo removal
        val halo = HaloRemoval.removeHalosInPlaceLinear(linear, amount = 0.25f, radiusPx = 2)
        return PrepResult(
            linearF16 = linear,
            srcColorSpace = dec.colorSpace,
            iccConfidence = dec.iccConfidence,
            wasHdrTonemapped = wasHdr,
            blockiness = blk,
            haloScore = halo
        )
    }
}