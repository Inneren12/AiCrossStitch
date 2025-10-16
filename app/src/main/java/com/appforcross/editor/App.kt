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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class App : Application() {
    private var isDebuggable: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // 1) Избавляемся от "холодного" дискового IO в main: работа стартовой сессии выполняется на Dispatchers.IO.
        val sess = runBlocking(Dispatchers.IO) {
            DiagnosticsManager.startSession(this@App, rotateInBackground = true)
        }
        isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
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

        // 2) Учитываем сохранённый выбор разработчика (DevPrefs) при старте процесса.
        val debugFromPrefs = runBlocking(Dispatchers.IO) {
            DevPrefs.isDebug(applicationContext).first()
        }
        val minLevel = if (debugFromPrefs) LogLevel.DEBUG else LogLevel.INFO

        Logger.init(
            sessionDir = sess.dir,
            sessionId = sess.id,
            minLevel = minLevel,
            logcatDebugEnabled = isDebuggable
        )
        Logger.i("UI", "app.start", mapOf("version" to versionName, "debug" to isDebug))
    }
    // 3) При завершении процесса в debug-сборках корректно закрываем логгер.
    override fun onTerminate() {
        super.onTerminate()
        if (BuildConfig.DEBUG) {
            Logger.shutdown()
        }
    }
}