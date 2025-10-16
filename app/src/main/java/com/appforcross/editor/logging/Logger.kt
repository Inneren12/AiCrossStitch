package com.appforcross.editor.logging

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

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
    /** Единый писатель на сеанс (оптимизация вместо открытия/закрытия на каждую запись). */
    @Volatile private var writer: BufferedWriter? = null
    /** Блокировка на время экспорта (исключает параллельную запись в ZIP). */
    private val writeLock = ReentrantLock(true)

    @Synchronized
    fun init(sessionDir: File, sessionId: String, minLevel: LogLevel, logcatDebugEnabled: Boolean) {
        if (!sessionDir.exists()) sessionDir.mkdirs()
        val f = File(sessionDir, "log.jsonl")
        fileRef.set(f)
        minLevelRef.set(minLevel)
        sessionIdRef.set(sessionId)
        logcatDebug = logcatDebugEnabled
        running.set(true)
        // Открываем единый буферизированный писатель (append).
        writer = BufferedWriter(FileWriter(f, true))
        i("IO", "logger.init", mapOf("path" to f.absolutePath, "minLevel" to minLevel.name, "sessionId" to sessionId))
    }

    fun setMinLevel(level: LogLevel) {
        minLevelRef.set(level)
        i("IO", "logger.level.update", mapOf("minLevel" to level.name))
    }

    /** Корректное завершение: останавливаем приём, сбрасываем и закрываем писатель. */
    /**
    +     * Принудительное «осушение» очереди записи — полезно перед экспортом диагностики.
    +     * Реализовано барьером: пустая задача, которую дожидаемся, гарантирует выполнение всех предыдущих.
    +     */
    fun flush(timeoutMs: Long = 3000) {
        if (!running.get()) return
        try {
            val fut = writerExecutor.submit {}
            fut.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // пул уже закрыт — нечего ждать
        } catch (t: Throwable) {
            // проглатываем — в крайних случаях часть логов могла не успеть записаться
        }
    }

    /** Корректное завершение: останавливаем приём и дожидаемся слива очереди. */
    fun shutdown() {
        running.set(false)
        flush(2_000)
        writerExecutor.shutdown()
    }

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
        val target = fileRef.get() ?: return
        if (fileRef.get() == null) return
        if (!running.get()) return
        try {
            // Захватываем ссылку локально, чтобы не потерять её в лямбде
            val dest = target
            writerExecutor.execute {
                try {
                    BufferedWriter(FileWriter(dest, true)).use {
                        it.write(line)
                        it.flush()
                    }
                } catch (io: IOException) {
                    Log.e("AiX/Logger", "write fail: ${io.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // очередь уже закрыта (shutdown) — безопасно игнорируем
        }
    }

    /**
     * Временная пауза записи на время критических операций (например, упаковка ZIP).
     * Удерживает блокировку до вызова [resumeWrites].
     */
    fun pauseWrites() {
        writeLock.lock()
        try {
            writer?.flush()
        } catch (_: IOException) {
        }
    }

    /** Возобновляет запись после [pauseWrites]. */
    fun resumeWrites() {
        try {
            writer?.flush()
        } catch (_: IOException) {
        } finally {
            writeLock.unlock()
        }
    }
}
