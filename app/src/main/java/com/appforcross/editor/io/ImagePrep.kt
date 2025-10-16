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
        // 2) В линейный sRGB (F16). Избегаем двойного преобразования, если уже RGBA_F16 + Linear sRGB.
        val linear = if (android.os.Build.VERSION.SDK_INT >= 26 &&
            dec.bitmap.config == Bitmap.Config.RGBA_F16 &&
            dec.bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_SRGB)
            ) {
            // обеспечим mutability, чтобы фильтры могли писать in-place
            dec.bitmap.copy(Bitmap.Config.RGBA_F16, /*mutable*/ true)
        } else {
                ColorMgmt.toLinearSrgbF16(dec.bitmap, dec.colorSpace)
            }
        // освободим исходник, если он не тот же объект
        if (dec.bitmap !== linear) {
            try { dec.bitmap.recycle() } catch (_: Throwable) {}
        }
        // 3) HDR тонмап при необходимости (PQ/HLG)
        val wasHdr = HdrTonemap.applyIfNeeded(linear, dec.colorSpace)
        // 4) Blockiness + deblock
        val blk = Deblocking8x8.measureBlockinessLinear(linear)
        if (blk.mean >= deblockThreshold) {
            // сила адаптируется к измеренной блочности (простая кривуля)
            val strength = (blk.mean * 12f).coerceIn(0f, 0.7f)
            Deblocking8x8.weakDeblockInPlaceLinear(linear, strength = strength)
            Logger.i("FILTER", "deblock.applied", mapOf("mean" to blk.mean, "v" to blk.vertical, "h" to blk.horizontal, "strength" to strength))
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