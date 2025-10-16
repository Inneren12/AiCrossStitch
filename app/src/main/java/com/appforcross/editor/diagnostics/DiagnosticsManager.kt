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

object DiagnosticsManager {
    private const val DIAG_DIR = "diag"
    private const val SESS_PREFIX = "session-"
    private const val MAX_SESSIONS = 5

    data class Session(val id: String, val dir: File)

    fun startSession(ctx: Context): Session {
        val root = File(ctx.filesDir, DIAG_DIR).apply { if (!exists()) mkdirs() }
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(root, "$SESS_PREFIX$id").apply { mkdirs() }
        rotate(root)
        Logger.i("IO", "io.session.start", mapOf(
            "diag_root" to root.absolutePath,
            "dir" to dir.absolutePath,
            "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
            "sdk" to Build.VERSION.SDK_INT
        ))
        return Session(id, dir)
    }

    fun currentSessionDir(ctx: Context): File? {
        val root = File(ctx.filesDir, DIAG_DIR)
        val sessions = root.listFiles()?.filter { it.isDirectory && it.name.startsWith(SESS_PREFIX) }?.sorted()
        return sessions?.lastOrNull()
    }

    fun exportZip(ctx: Context): File {
        val sessionDir = currentSessionDir(ctx) ?: throw IllegalStateException("No session found")
        val out = File(ctx.cacheDir, "${sessionDir.name}.zip")
        zipDir(sessionDir, out)
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