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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class App : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            flushLogs()
            appScope.coroutineContext.cancelChildren()
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
        appScope.cancel()
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
        return runBlocking {
            withTimeoutOrNull(500) {
                DevPrefs.isDebug(applicationContext).first()
            }
        }?.let { enabled ->
            if (enabled) LogLevel.DEBUG else LogLevel.INFO
        } ?: if (debugFlag) LogLevel.DEBUG else LogLevel.INFO
    }

    private fun observeDeveloperLogPreference(initialLevel: LogLevel) {
        appScope.launch {
            DevPrefs.isDebug(applicationContext)
                .map { enabled -> if (enabled) LogLevel.DEBUG else LogLevel.INFO }
                .drop(1)
                .collectLatest { level ->
                    if (level != initialLevel) {
                        Logger.setMinLevel(level)
                    }
                }
        }
    }

    private fun flushLogs() {
        try {
            Logger.flush()
        } catch (t: Throwable) {
            Log.w("App", "Failed to flush logs", t)
        }
    }
}