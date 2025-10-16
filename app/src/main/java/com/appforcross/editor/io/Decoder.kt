package com.appforcross.editor.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.appforcross.editor.logging.Logger
import android.content.res.AssetFileDescriptor
import java.io.InputStream
import java.util.concurrent.Callable
data class DecodedImage(
        val bitmap: Bitmap,                 // как прочитали (ARGB_8888 или RGBA_F16)
        val colorSpace: ColorSpace?,        // исходное пространство
        val iccConfidence: Boolean,         // true если ColorSpace определён надёжно
        val rotated: Boolean,               // применён поворот по EXIF
        val width: Int,
        val height: Int,
        val mime: String?,
        val exclusive: Boolean
    )

object Decoder {

    private const val MAX_DEC_MP = 16_000_000 // ~16 Мп ограничение по памяти для декода

    fun decodeUri(ctx: Context, uri: Uri): DecodedImage {
        Logger.i("IO", "decode.start", mapOf("uri" to uri.toString()))
        val mime = safeMime(ctx.contentResolver, uri)
        var srcBmp: Bitmap
        var srcCs: ColorSpace?
        val exifOrientation: Int
        var decodeExclusive = false

        if (Build.VERSION.SDK_INT >= 28) {
            // EXIF берём через AFD один раз...
            val resolver = ctx.contentResolver
            resolver.openAssetFileDescriptor(uri, "r").use { afd ->
                exifOrientation =
                    if (afd == null) ExifInterface.ORIENTATION_UNDEFINED else readExifOrientation(afd)
            }
            // ...а Source создаём через Callable<AFD>, чтобы декодер сам управлял ресурсом.
            val source = ImageDecoder.createSource(java.util.concurrent.Callable {
                resolver.openAssetFileDescriptor(uri, "r")
                    ?: throw IllegalArgumentException("openAssetFileDescriptor returned null")
            })
            var outCs: ColorSpace? = null
            srcBmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        // Если HDR/широкий гамут, просим F16, иначе достаточно 8888
                    val cs = info.colorSpace
                    outCs = cs
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    decoder.setTargetColorSpace(cs ?: ColorSpace.get(ColorSpace.Named.SRGB))
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    // Ограничаем размер декода для очень больших исходников (~16 Мп)
                    val w0 = info.size.width
                    val h0 = info.size.height
                    val mp = 1L * w0 * h0
                    if (mp > MAX_DEC_MP) {
                        val scale = kotlin.math.sqrt(MAX_DEC_MP.toDouble() / mp.toDouble())
                        val tw = kotlin.math.max(1, (w0 * scale).toInt())
                        val th = kotlin.math.max(1, (h0 * scale).toInt())
                        decoder.setTargetSize(tw, th)
                    }
                    if (cs?.isWideGamut == true || cs == ColorSpace.get(ColorSpace.Named.BT2020_HLG) || cs == ColorSpace.get(ColorSpace.Named.BT2020_PQ)) {
                        decoder.setTargetColorSpace(cs)
                    }
            }
            srcCs = outCs
            // Новый буфер из ImageDecoder (SOFTWARE) обычно мутируем и эксклюзивен:
            decodeExclusive = srcBmp.isMutable && (
                    Build.VERSION.SDK_INT < 26 || srcBmp.config != Bitmap.Config.HARDWARE
                    )
        } else {
            // Fallback: BitmapFactory (+ inSampleSize для лимита 16 Мп)
            exifOrientation = readExifOrientation(ctx.contentResolver, uri)
            // 1) измеряем bounds
            val ob = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri).use { ins ->
                BitmapFactory.decodeStream(ins, null, ob)
            }
            val w0 = ob.outWidth
            val h0 = ob.outHeight
            var sample = 1
            if (w0 > 0 && h0 > 0) {
                while ((1L * (w0 / sample) * (h0 / sample)) > MAX_DEC_MP) sample = sample shl 1
            }
            // 2) основной декод
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            ctx.contentResolver.openInputStream(uri).use { ins ->
                srcBmp = BitmapFactory.decodeStream(ins, null, opts)
                    ?: throw IllegalArgumentException("decodeStream failed")
            }
            srcCs = if (Build.VERSION.SDK_INT >= 26) srcBmp.colorSpace else ColorSpace.get(ColorSpace.Named.SRGB)
            // BitmapFactory.decodeStream создаёт новый софт-буфер → безопасно мутировать
            decodeExclusive = srcBmp.isMutable && (
                    Build.VERSION.SDK_INT < 26 || srcBmp.config != Bitmap.Config.HARDWARE
                    )
        }

        // Возвращаем НОВЫЙ bitmap при необходимости поворота, не мутируем исходный in-place.
        val (outBmp, rotated) = applyExifRotate(exifOrientation, srcBmp)
        if (rotated && outBmp !== srcBmp) {
            // освобождаем исходный буфер, если был создан новый
            try { srcBmp.recycle() } catch (_: Throwable) {}
        }

        // Эксклюзивность результата: либо была у исходника (без поворота),
        // либо у созданного "rotated" кадра, если он мутируем и не HARDWARE.
        val outExclusive = (
                if (rotated) outBmp.isMutable else decodeExclusive
                ) && (Build.VERSION.SDK_INT < 26 || outBmp.config != Bitmap.Config.HARDWARE)

        val csName = srcCs?.name ?: "unknown"
        Logger.i("IO", "decode.done", mapOf("w" to outBmp.width, "h" to outBmp.height, "cs" to csName, "mime" to mime, "rotated" to rotated))
        // Повышаем "уверенность" только если пространство не тривиальное sRGB/Linear sRGB.
        val iccConf = when (srcCs) {
            null -> false
            ColorSpace.get(ColorSpace.Named.SRGB),
            ColorSpace.get(ColorSpace.Named.LINEAR_SRGB) -> false
            else -> true
        }
        return DecodedImage(
            bitmap = outBmp,
            colorSpace = srcCs,
            iccConfidence = iccConf,
            rotated = rotated,
            width = outBmp.width,
            height = outBmp.height,
            mime = mime,
            // Реальная эксклюзивность буфера (без HARDWARE и с учётом поворота).
            exclusive = outExclusive
        )
    }

    private fun safeMime(res: ContentResolver, uri: Uri): String? = try {
        res.getType(uri)
    } catch (_: Exception) { null }

    /** Поворот по EXIF (уже известна ориентация). Возвращает (bitmap, rotated). */
    private fun applyExifRotate(orientation: Int, bmp: Bitmap): Pair<Bitmap, Boolean> {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL   -> m.preScale(1f, -1f)
            // Доп. случаи 5/7: flip H + rotate (см. AndroidX Camera TransformUtils)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postScale(-1f, 1f)
                m.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postScale(-1f, 1f)
                m.postRotate(90f)
            }
            else -> return Pair(bmp, false)
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return Pair(rotated, true)
    }

    /** Однократное чтение EXIF‑ориентации (до декодирования). */
    /** EXIF-ориентация из AFD с учётом startOffset: используем поток окна ресурса. */
    private fun readExifOrientation(afd: AssetFileDescriptor): Int = try {
        afd.createInputStream().use { ins ->
            ExifInterface(ins).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    } catch (_: Exception) {
            ExifInterface.ORIENTATION_UNDEFINED
        }
    /** Вариант через ContentResolver/Uri: также читаем через AFD.createInputStream(). */
     private fun readExifOrientation(res: ContentResolver, uri: Uri): Int = try {
        res.openAssetFileDescriptor(uri, "r").use { afd ->
            if (afd == null) {
                ExifInterface.ORIENTATION_UNDEFINED
            } else {
                    afd.createInputStream().use { ins ->
                    ExifInterface(ins).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL) }
                    }
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_UNDEFINED
    }
}