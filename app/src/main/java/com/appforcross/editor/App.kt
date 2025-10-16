package com.appforcross.editor

import android.app.Application
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.LogLevel
import com.appforcross.editor.logging.Logger
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val sess = DiagnosticsManager.startSession(this)
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val versionName = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                    @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
                } ?: "1.0"
        } catch (_: Exception) { "1.0" }
        Logger.init(
            sessionDir = sess.dir,
            sessionId = sess.id,
            minLevel = if (isDebug) LogLevel.DEBUG else LogLevel.INFO,
            logcatDebugEnabled = isDebug
        )
        Logger.i("UI", "app.start", mapOf("version" to versionName, "debug" to isDebug))
    }
}