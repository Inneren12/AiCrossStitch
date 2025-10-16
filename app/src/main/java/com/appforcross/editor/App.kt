package com.appforcross.editor

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.appforcross.app.BuildConfig
import com.appforcross.editor.dev.DevPrefs
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.LogLevel
import com.appforcross.editor.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class App : Application() {
    /** Долгоживущий scope уровня процесса — НЕ отменяем при background. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Scope «переднего плана»: пересоздаётся между onStart/onStop, отменяется в onStop. */
    private val foregroundScopeRef = AtomicReference<CoroutineScope?>(null)
    /** Барьер на одновременные сбросы логов из разных коллбеков. */
    private val flushMutex = Mutex()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // Создаём новый foreground-scope; предыдущий безопасно гасим.
            foregroundScopeRef.getAndSet(CoroutineScope(SupervisorJob() + Dispatchers.Default))?.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val debugFlag = BuildConfig.DEBUG ||
                (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val session = DiagnosticsManager.startSession(this)
        val versionName = resolveVersionName()
        val initialLevel = resolveInitialLogLevel(debugFlag)

        Logger.init(
            sessionDir = session.dir,
            sessionId = session.id,
            minLevel = initialLevel,
            logcatDebugEnabled = debugFlag
        )
        Logger.i("UI", "app.start", mapOf("version" to versionName, "debug" to debugFlag))

        observeDeveloperLogPreference(initialLevel)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            flushLogs()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        flushLogs()
        foregroundScopeRef.getAndSet(null)?.cancel()
        appScope.cancel() // мягкое завершение долгоживущих задач
        if (BuildConfig.DEBUG) {
            Logger.shutdown()
        }
    }

    private fun resolveVersionName(): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            } ?: BuildConfig.VERSION_NAME
        } catch (t: Exception) {
            Log.w("App", "Falling back to BuildConfig.VERSION_NAME", t)
            BuildConfig.VERSION_NAME
        }
    }

    private fun resolveInitialLogLevel(debugFlag: Boolean): LogLevel {
        // Синхронно читаем сохранённую настройку, без таймаута.
        return try {
            val enabled = runBlocking { DevPrefs.isDebug(applicationContext).first() }
            if (enabled) LogLevel.DEBUG else LogLevel.INFO
        } catch (t: Exception) {
            if (debugFlag) LogLevel.DEBUG else LogLevel.INFO
        }
    }

    private fun observeDeveloperLogPreference(initialLevel: LogLevel) {
        appScope.launch {
            DevPrefs.isDebug(applicationContext)
                .map { enabled -> if (enabled) LogLevel.DEBUG else LogLevel.INFO }
                .distinctUntilChanged()
                .collectLatest { level -> Logger.setMinLevel(level) }
        }
    }

    private fun flushLogs() {
        // Координируем конкурентные вызовы и избегаем блокировки главного потока.
        try {
            runBlocking {
                withTimeoutOrNull(500) {
                    flushMutex.withLock {
                        withContext(Dispatchers.IO) { Logger.flush() }
                    }
                } ?: Logger.flush() // fallback: «попросить» сброс без ожидания
            }
        } catch (t: Throwable) {
            Log.w("App", "Failed to flush logs", t)
        }
    }

    /** Доступ к актуальным областям для вызывающих: исключает удержание отменённых scope. */
    fun applicationScope(): CoroutineScope = appScope
    fun foregroundScope(): CoroutineScope = foregroundScopeRef.get() ?: appScope
}