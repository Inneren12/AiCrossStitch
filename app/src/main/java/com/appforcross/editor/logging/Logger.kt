package com.appforcross.editor.logging

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Структурный логгер: JSONL в файл + (dev) в Logcat. Однопоточная запись. */
object Logger {
    private val minLevelRef = AtomicReference(LogLevel.INFO)
    private val sessionIdRef = AtomicReference<String?>(null)
    private val fileRef = AtomicReference<File?>(null)
    private val running = AtomicBoolean(false)
    private val writerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "logger-writer").apply { isDaemon = true }
    }
    private var logcatDebug = false

    @Synchronized
    fun init(sessionDir: File, sessionId: String, minLevel: LogLevel, logcatDebugEnabled: Boolean) {
        if (!sessionDir.exists()) sessionDir.mkdirs()
        val f = File(sessionDir, "log.jsonl")
        fileRef.set(f)
        minLevelRef.set(minLevel)
        sessionIdRef.set(sessionId)
        logcatDebug = logcatDebugEnabled
        running.set(true)
        i("IO", "logger.init", mapOf("path" to f.absolutePath, "minLevel" to minLevel.name, "sessionId" to sessionId))
    }

    fun setMinLevel(level: LogLevel) {
        minLevelRef.set(level)
        i("IO", "logger.level.update", mapOf("minLevel" to level.name))
    }

    fun shutdown() { running.set(false) }

    fun d(cat: String, msg: String, data: Map<String, Any?> = emptyMap(), req: String? = null, tile: String? = null) =
        log(LogLevel.DEBUG, cat, msg, data, req, tile)
    fun i(cat: String, msg: String, data: Map<String, Any?> = emptyMap(), req: String? = null, tile: String? = null) =
        log(LogLevel.INFO, cat, msg, data, req, tile)
    fun w(cat: String, msg: String, data: Map<String, Any?> = emptyMap(), req: String? = null, tile: String? = null) =
        log(LogLevel.WARN, cat, msg, data, req, tile)
    fun e(cat: String, msg: String, data: Map<String, Any?> = emptyMap(), req: String? = null, tile: String? = null, err: Throwable? = null) {
        val m = if (err != null) data + ("error" to (err.message ?: err.toString())) else data
        log(LogLevel.ERROR, cat, msg, m, req, tile)
    }

    fun log(level: LogLevel, cat: String, msg: String, data: Map<String, Any?>, req: String?, tile: String?) {
        if (!minLevelRef.get().allows(level)) return
        val ev = LogEvent(level = level, category = cat, message = msg, data = data,
            sessionId = sessionIdRef.get(), requestId = req, tileId = tile)
        val line = ev.toJsonLine() + "\n"
        if (logcatDebug || level >= LogLevel.WARN) {
            val tag = "AiX/$cat"
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, msg)
                LogLevel.INFO -> Log.i(tag, msg)
                LogLevel.WARN -> Log.w(tag, msg)
                LogLevel.ERROR -> Log.e(tag, msg)
            }
        }
        val file = fileRef.get() ?: return
        if (!running.get()) return
        writerExecutor.execute {
            try {
                BufferedWriter(FileWriter(file, true)).use {
                    it.write(line)
                    it.flush()
                }
            } catch (io: IOException) {
                Log.e("AiX/Logger", "write fail: ${io.message}")
            }
        }
    }
}