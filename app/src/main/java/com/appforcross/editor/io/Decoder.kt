package com.appforcross.editor.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.appforcross.editor.logging.Logger
import java.io.InputStream

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

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun decodeUri(ctx: Context, uri: Uri): DecodedImage {
        Logger.i("IO", "decode.start", mapOf("uri" to uri.toString()))
        val mime = safeMime(ctx.contentResolver, uri)
        val srcBmp: Bitmap
        val srcCs: ColorSpace?

        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(ctx.contentResolver, uri)
            var outCs: ColorSpace? = null
            srcBmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // Если HDR/широкий гамут, просим F16, иначе достаточно 8888
                val cs = info.colorSpace
                outCs = cs
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetColorSpace(cs ?: ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                if (cs?.isWideGamut == true || cs == ColorSpace.get(ColorSpace.Named.BT2020_HLG) || cs == ColorSpace.get(ColorSpace.Named.BT2020_PQ)) {
                    decoder.setTargetSize(info.size.width, info.size.height)
                    decoder.setTargetColorSpace(cs)
                }
            }
            srcCs = outCs
        } else {
            // Fallback: BitmapFactory
            val opts = BitmapFactory.Options().apply { inMutable = true }
            ctx.contentResolver.openInputStream(uri).use { ins ->
                srcBmp = BitmapFactory.decodeStream(ins, null, opts) ?: throw IllegalArgumentException("decodeStream failed")
            }
            srcCs = if (Build.VERSION.SDK_INT >= 26) srcBmp.colorSpace else ColorSpace.get(ColorSpace.Named.SRGB)
        }

        // Возвращаем НОВЫЙ bitmap при необходимости поворота, не мутируем исходный in-place.
        val (outBmp, rotated) = applyExifRotate(ctx, uri, srcBmp)
        if (rotated && outBmp !== srcBmp) {
            // освобождаем исходный буфер, если был создан новый
            try { srcBmp.recycle() } catch (_: Throwable) {}
        }
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
            exclusive = true
        )
    }

    private fun safeMime(res: ContentResolver, uri: Uri): String? = try {
        res.getType(uri)
    } catch (_: Exception) { null }

    /** Поворот по EXIF. Возвращает (bitmap, rotated). Без попытки мутировать исходный bitmap. */
    private fun applyExifRotate(ctx: Context, uri: Uri, bmp: Bitmap): Pair<Bitmap, Boolean> {
        return try {
            ctx.contentResolver.openInputStream(uri).use { ins ->
                if (ins == null) return Pair(bmp, false)
                val exif = ExifInterface(ins)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val m = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
                    else -> return Pair(bmp, false)
                }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                Pair(rotated, true)
            }
        } catch (_: Exception) { Pair(bmp, false) }
    }
}