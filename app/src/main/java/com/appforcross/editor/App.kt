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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.*
class App : Application() {
    // Неблокирующие фоновые задачи старта (живут столько же, сколько процесс приложения)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Единый флаг дебага
        val debugFlag = BuildConfig.DEBUG ||
                ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        // 1) Старт сессии без runBlocking — ротация уедет в фон внутри DiagnosticsManager
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

        Logger.init(
            sessionDir = sess.dir,
            sessionId = sess.id,
            // 2) Инициализируемся быстро по флагу сборки...
            minLevel = if (debugFlag) LogLevel.DEBUG else LogLevel.INFO,
            logcatDebugEnabled = debugFlag
        )
        Logger.i("UI", "app.start", mapOf("version" to versionName, "debug" to debugFlag))
        // ...а затем асинхронно подтягиваем сохранённые настройки DevPrefs и выравниваем уровень
        appScope.launch {
            val debugFromPrefs = DevPrefs.isDebug(applicationContext).first()
            val target = if (debugFromPrefs) LogLevel.DEBUG else LogLevel.INFO
            Logger.setMinLevel(target)
        }
    }
    // 3) При завершении процесса в debug-сборках корректно закрываем логгер.
    override fun onTerminate() {
        super.onTerminate()
        if (BuildConfig.DEBUG) {
            Logger.shutdown()
        }
    }
}