package com.appforcross.editor

import android.app.Application
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.LogLevel
import com.appforcross.editor.logging.Logger
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.util.Log
import com.appforcross.app.BuildConfig
import com.appforcross.editor.dev.DevPrefs
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
class App : Application() {
    // Фоновые задачи старта (жизненный цикл контролируем вручную)
    private var appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // Возобновляем scope после возврата на передний план
            if (!appScope.isActive) appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        override fun onStop(owner: LifecycleOwner) {
            // При уходе в фон гарантируем запись логов и ограничиваем жизнь фоновых задач
            try { Logger.flush() } catch (_: Throwable) {}
            appScope.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Единый флаг дебага
        val debugFlag = BuildConfig.DEBUG ||
                ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        // 1) Старт сессии — ротация уедет в фон внутри DiagnosticsManager
        val sess = DiagnosticsManager.startSession(this)
        val versionName = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                    @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            } ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            // 4) Не скрываем сбой; возвращаемся к BuildConfig.VERSION_NAME.
            Log.w("App", "Failed to read package version; fallback to BuildConfig.VERSION_NAME", e)
            BuildConfig.VERSION_NAME
        }
        // 2) Инициализируем Logger с УЧЁТОМ сохранённого переключателя до публикации первого лога.
        // Чтение DataStore с коротким тайм-аутом; при сбое — откат к уровню сборки.
        val initialLevel: LogLevel = runBlocking {
            withTimeoutOrNull(500) {
                val debugFromPrefs = DevPrefs.isDebug(applicationContext).first()
                if (debugFromPrefs) LogLevel.DEBUG else LogLevel.INFO
            }
        } ?: if (debugFlag) LogLevel.DEBUG else LogLevel.INFO

        Logger.init(
            sessionDir = sess.dir,
            sessionId = sess.id,
            // 2) Инициализируемся быстро по флагу сборки...
            minLevel = if (debugFlag) LogLevel.DEBUG else LogLevel.INFO,
            logcatDebugEnabled = debugFlag
        )
        Logger.i("UI", "app.start", mapOf("version" to versionName, "debug" to debugFlag))
        // Далее — асинхронное выравнивание на случай изменения настройки на лету
        appScope.launch {
            val debugFromPrefs = DevPrefs.isDebug(applicationContext).first()
            val target = if (debugFromPrefs) LogLevel.DEBUG else LogLevel.INFO
            Logger.setMinLevel(target)
        }
        // Подписываемся на жизненный цикл процесса (фон/передний план)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
    }
    // Детеминированная очистка при давлении памяти (когда UI скрыт)
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            try { Logger.flush() } catch (_: Throwable) {}
        }
    }

    // Best-effort для отладочных сборок; дополнительно отменяем глобальный scope
    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
        if (BuildConfig.DEBUG) Logger.shutdown()
    }
}