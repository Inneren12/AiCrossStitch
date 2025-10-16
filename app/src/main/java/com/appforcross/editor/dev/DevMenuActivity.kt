package com.appforcross.editor.dev

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.LogLevel
import com.appforcross.editor.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DevMenuActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // --- Программная разметка, без R ---
        fun dp(x: Int) = (x * resources.displayMetrics.density).roundToInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val tvTitle = TextView(this).apply {
            text = "Developer Options"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 18f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val tvLabel = TextView(this).apply {
            text = "Enable DEBUG logs"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val swDebug = Switch(this)
        row.addView(tvLabel)
        row.addView(swDebug)
        val btnExport = Button(this).apply { text = "Export diagnostics (.zip)" }
        root.addView(tvTitle)
        root.addView(row)
        root.addView(btnExport)
        setContentView(root)
        scope.launch {
            swDebug.isChecked = DevPrefs.isDebug(this@DevMenuActivity).first()
        }
        swDebug.setOnCheckedChangeListener { _, checked ->
            scope.launch {
                DevPrefs.setDebug(this@DevMenuActivity, checked)
                Logger.setMinLevel(if (checked) LogLevel.DEBUG else LogLevel.INFO)
                Toast.makeText(this@DevMenuActivity, "DEBUG logs: $checked", Toast.LENGTH_SHORT).show()
            }
        }

        btnExport.setOnClickListener {
            scope.launch {
                try {
                    val zip = DiagnosticsManager.exportZip(this@DevMenuActivity)
                    Logger.i("IO", "diag.export.ui", mapOf("zip" to zip.absolutePath))
                    val uri = Uri.fromFile(zip)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share diagnostics"))
                } catch (e: Exception) {
                    Logger.e("IO", "diag.export.fail", err = e)
                    Toast.makeText(this@DevMenuActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}