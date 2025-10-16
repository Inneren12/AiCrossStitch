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

    /** * Основной входной шаг пайплайна.
     * @param debug необязательный коллбек телеметрии (настройка на устройстве)
     * @param recycleDecoded если true и входной bitmap эксклюзивен (см. Decoder), он будет освобождён
     */
    fun prepare(
        ctx: Context,
        uri: android.net.Uri,
        deblockThreshold: Float = 0.008f,
        debug: ((Map<String, Any>) -> Unit)? = null,
        recycleDecoded: Boolean = true
    ): PrepResult {
        // 1) Декод
        val dec = Decoder.decodeUri(ctx, uri)
        // 2) Приведение к линейному sRGB (F16) с безопасностью для non-exclusive/HARDWARE.
        val isHardware = (android.os.Build.VERSION.SDK_INT >= 26
                && dec.bitmap.config == Bitmap.Config.HARDWARE)
        val requireCopy = (!dec.exclusive) || isHardware
        val alreadyLinear = (android.os.Build.VERSION.SDK_INT >= 26
                && dec.bitmap.config == Bitmap.Config.RGBA_F16
                && dec.bitmap.colorSpace == ColorSpace.get(ColorSpace.Named.LINEAR_SRGB))
        var linear = when {
            // Для разделяемых или аппаратных — всегда создаём собственный буфер
            requireCopy && alreadyLinear -> dec.bitmap.copy(Bitmap.Config.RGBA_F16, /*mutable*/ true)
            requireCopy && !alreadyLinear -> ColorMgmt.toLinearSrgbF16(dec.bitmap, dec.colorSpace)
            // Эксклюзивный и уже линейный: можно использовать как есть, если он мутируем
            alreadyLinear && dec.bitmap.isMutable -> dec.bitmap
            // Эксклюзивный, уже линейный, но immutable — скопируем в mutable
            alreadyLinear -> dec.bitmap.copy(Bitmap.Config.RGBA_F16, /*mutable*/ true)
            // Остальные — конвертируем (возвращает новый RGBA_F16)
            else -> ColorMgmt.toLinearSrgbF16(dec.bitmap, dec.colorSpace)
        }
        // Жёсткая защита от alias исходника: если вдруг linear == исходному bitmap и он immutable — копируем.
        if (linear === dec.bitmap && !linear.isMutable) {
            Logger.w("IO", "linear.alias.immutable",
                mapOf("origExclusive" to dec.exclusive, "cfg" to (dec.bitmap.config?.toString() ?: "null")))
            linear = dec.bitmap.copy(Bitmap.Config.RGBA_F16, /*mutable*/ true)
        }
        // Страховка: если non-exclusive, но в пайплайн попал исходный bitmap — лог и принудительная копия.
        if (!dec.exclusive && dec.bitmap === linear) {
            Logger.w("IO", "decode.exclusive.violation", mapOf("note" to "shared-bitmap-passed-into-stage2"))
            linear = dec.bitmap.copy(Bitmap.Config.RGBA_F16, /*mutable*/ true)
        }
        // Освобождаем исходник ТОЛЬКО если он эксклюзивный и это разрешено политикой.
        if (dec.bitmap !== linear && dec.exclusive && recycleDecoded) {
            try { dec.bitmap.recycle() } catch (_: Throwable) {}
        }
        // 3) HDR тонмап при необходимости (PQ/HLG)
        val wasHdr = HdrTonemap.applyIfNeeded(linear, dec.colorSpace /*, headroom = 3f */)
        // 4) Blockiness + deblock
        val blk = Deblocking8x8.measureBlockinessLinear(linear)
        // Ранний skip: силы ~0 — не тратим проход по изображению
        if (blk.mean >= deblockThreshold) {
            // сила адаптируется к измеренной блочности (простая кривуля)
            val strength = (blk.mean * 12f).coerceIn(0f, 0.7f)
            Deblocking8x8.weakDeblockInPlaceLinear(linear, strength = strength)
            Logger.i("FILTER", "deblock.applied", mapOf("mean" to blk.mean, "v" to blk.vertical, "h" to blk.horizontal, "strength" to strength))
        } else {
            // Одна ветка skip без «early» — меньше шума в логах
            Logger.i("FILTER", "deblock.skipped", mapOf("mean" to blk.mean, "thr" to deblockThreshold))
        }
        // 5) Halo removal
        val halo = HaloRemoval.removeHalosInPlaceLinear(linear, amount = 0.25f, radiusPx = 2)
        // Отладочная телеметрия для настройки на устройстве
        debug?.invoke(
            mapOf(
                "srcCs" to (dec.colorSpace?.name ?: "unknown"),
                "iccConfidence" to dec.iccConfidence,
                "hdrApplied" to wasHdr,
                "blockinessV" to blk.vertical,
                "blockinessH" to blk.horizontal,
                "blockinessMean" to blk.mean,
                "haloScore" to halo
            )
        )
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