package com.appforcross.editor.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
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
        val mime: String?
    )

object Decoder {

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
            ctx.contentResolver.openInputStream(uri).use { ins ->
                srcBmp = BitmapFactory.decodeStream(ins) ?: throw IllegalArgumentException("decodeStream failed")
            }
            srcCs = if (Build.VERSION.SDK_INT >= 26) srcBmp.colorSpace else ColorSpace.get(ColorSpace.Named.SRGB)
        }

        val rotated = applyExifRotateInPlace(ctx, uri, srcBmp)
        val csName = srcCs?.name ?: "unknown"
        Logger.i("IO", "decode.done", mapOf("w" to srcBmp.width, "h" to srcBmp.height, "cs" to csName, "mime" to mime, "rotated" to rotated))
        return DecodedImage(
            bitmap = srcBmp,
            colorSpace = srcCs,
            iccConfidence = srcCs != null,
            rotated = rotated,
            width = srcBmp.width,
            height = srcBmp.height,
            mime = mime
        )
    }

    private fun safeMime(res: ContentResolver, uri: Uri): String? = try {
        res.getType(uri)
    } catch (_: Exception) { null }

    /** Поворот по EXIF in-place, возвращает применён ли. */
    private fun applyExifRotateInPlace(ctx: Context, uri: Uri, bmp: Bitmap): Boolean {
        return try {
            val ins: InputStream = ctx.contentResolver.openInputStream(uri) ?: return false
            val exif = ExifInterface(ins)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
                else -> { ins.close(); return false }
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            // заменить содержимое (in-place семантика для вызывающего)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(rotated, 0f, 0f, null)
            ins.close()
            true
        } catch (_: Exception) { false }
    }
}