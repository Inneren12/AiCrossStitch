package com.appforcross.editor.diagnostics

import android.content.Context
import android.os.Build
import com.appforcross.editor.logging.Logger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.TimeZone
import kotlin.concurrent.thread

object DiagnosticsManager {
    private const val DIAG_DIR = "diag"
    private const val SESS_PREFIX = "session-"
    private const val MAX_SESSIONS = 5

    data class Session(val id: String, val dir: File)
    @Volatile private var activeSession: Session? = null

    /** Доступ к активной сессии без повторного обращения к ФС. */
    fun activeSession(): Session? = activeSession
    fun activeSessionId(): String? = activeSession?.id
    /**
     * Создаёт каталог сессии и (по умолчанию) переносит ротацию в фон.
     * Это снижает задержку старта приложения (см. п.1 рекомендаций).
     */
    fun startSession(ctx: Context, rotateInBackground: Boolean = true): Session {
        val root = File(ctx.filesDir, DIAG_DIR).apply { if (!exists()) mkdirs() }
        val startedAt = Date()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(startedAt)
        val dir = File(root, "$SESS_PREFIX$id").apply { mkdirs() }
        if (rotateInBackground) {
            thread(name = "diag-rotate", isDaemon = true) { rotate(root) }
        } else {
                rotate(root)
            }
        Logger.i("IO", "io.session.start", mapOf(
            "diag_root" to root.absolutePath,
            "dir" to dir.absolutePath,
            "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
            "sdk" to Build.VERSION.SDK_INT
        ))
        // (п.5) Минимальные метаданные сессии для удобства диагностики.
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        File(dir, "session.json").writeText(
            """{"id":"$id","started_at_utc":"${iso.format(startedAt)}","device":"${Build.MANUFACTURER}/${Build.MODEL}","sdk":${Build.VERSION.SDK_INT}}"""
        )
        return Session(id, dir).also { activeSession = it }
    }

    fun currentSessionDir(ctx: Context): File? {
        val root = File(ctx.filesDir, DIAG_DIR)
        val sessions = root.listFiles()?.filter { it.isDirectory && it.name.startsWith(SESS_PREFIX) }?.sorted()
        return sessions?.lastOrNull()
    }

    fun exportZip(ctx: Context): File {
        val sessionDir = currentSessionDir(ctx) ?: throw IllegalStateException("No session found")
        val out = File(ctx.cacheDir, "${sessionDir.name}.zip")
        // (п.3) На время упаковки «замораживаем» запись логов, чтобы архив был полным.
        Logger.pauseWrites()
        try {
            // 3) Перед упаковкой убеждаемся, что очередь записи логгера пуста.
            Logger.flush()
            zipDir(sessionDir, out)
        } finally {
                Logger.resumeWrites()
            }
        Logger.i("IO", "diag.export", mapOf("zip_path" to out.absolutePath, "bytes" to out.length()))
        return out
    }

    private fun rotate(root: File) {
        val sessions = root.listFiles()?.filter { it.isDirectory && it.name.startsWith(SESS_PREFIX) }?.sorted() ?: return
        val excess = sessions.size - MAX_SESSIONS
        if (excess > 0) {
            sessions.take(excess).forEach {
                it.deleteRecursively()
                Logger.i("IO", "diag.rotate", mapOf("removed" to it.name))
            }
        }
    }

    private fun zipDir(srcDir: File, outZip: File) {
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            fun add(file: File, base: String) {
                val name = base + file.name
                if (file.isDirectory) {
                    zos.putNextEntry(ZipEntry("$name/")); zos.closeEntry()
                    file.listFiles()?.forEach { add(it, "$name/") }
                } else {
                    FileInputStream(file).use { fis ->
                        zos.putNextEntry(ZipEntry(name))
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }
            add(srcDir, "")
        }
    }
}