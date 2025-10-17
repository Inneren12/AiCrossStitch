
package com.appforcross.editor.export

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.appforcross.editor.diagnostics.DiagnosticsManager
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.logging.LogcatKV

class ImageExportRunner(
    private val context: Context,
    private val diag: DiagnosticsManager,
    // Директория текущей сессии (diag/session-*/…)
    private val sessionDir: File
) {

    // Где лежит кэш превью от Stage9
    private fun previewFile(): File = File(sessionDir, "pattern_preview.png")

    fun hasPreview(): Boolean = previewFile().exists()

    fun loadPreviewBitmap(): Bitmap? =
        previewFile().takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /**
     * Экспорт PNG: если есть готовое превью — пишем его.
     * Если по какой-то причине нет — пытаемся бэкапно загрузить raw-изображение pattern_index.bin нельзя напрямую,
     * но здесь можно вставить вызов к рендеру превью (если у тебя он доступен).
     */
    fun exportTo(uri: Uri): Boolean {
        val bmp = loadPreviewBitmap() ?: return false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            // PNG игнорирует quality, но API требует параметр
            val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (ok) {
                val meta = mapOf(
                        "w" to bmp.width,
                        "h" to bmp.height,
                        "session" to sessionDir.name,
                        "ts" to Instant.now().toString()
                    )
                Logger.i("EXPORT","PNG.done", meta)
                LogcatKV.i("EXPORT","PNG.done", meta)
            }
            return ok
        }
        return false
    }

    /** Прямой экспорт в файл (внутреннее сохранение, если нужно). */
    fun exportTo(file: File): Boolean {
        val bmp = loadPreviewBitmap() ?: return false
        FileOutputStream(file).use { out ->
            return bmp.compress(Bitmap.CompressFormat.PNG, 100, out).also { ok ->
                if (ok) {
                    val size = file.length()
                    Logger.i(
                        "EXPORT",
                        "PNG.file",
                        mapOf("path" to file.absolutePath,
                            "w" to bmp.width,
                            "h" to bmp.height,
                            "bytes" to size)
                    )
                    val meta = mapOf("path" to file.absolutePath, "w" to bmp.width, "h" to bmp.height, "bytes" to size)
                    Logger.i("EXPORT","PNG.file", meta)
                    LogcatKV.i("EXPORT","PNG.file", meta)
                }
            }
        }
    }
}
