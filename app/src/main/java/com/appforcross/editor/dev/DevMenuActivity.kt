package com.appforcross.editor.dev

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.withContext
import com.appforcross.editor.io.ImagePrep
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.core.content.FileProvider
import kotlinx.coroutines.cancel
import com.appforcross.editor.analysis.Stage3Analyze
import com.appforcross.editor.preset.Stage4Runner

class DevMenuActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val REQ_PICK_IMAGE = 1001
    private val REQ_PICK_ANALYZE = 1002
    private val REQ_PICK_PRESET = 1003
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
        val btnStage2 = Button(this).apply { text = "Pick image & Run Stage-2" }
        val btnStage3 = Button(this).apply { text = "Pick image & Analyze (Stage-3)" }
        val btnStage4 = Button(this).apply { text = "Pick image & PresetGate (Stage-4)" }

        root.addView(tvTitle)
        root.addView(row)
        root.addView(btnExport)
        root.addView(btnStage2)
        root.addView(btnStage3)
        root.addView(btnStage4)

        setContentView(root)
        scope.launch {
            // (п.1) Чтение DataStore от имени applicationContext
            swDebug.isChecked = DevPrefs.isDebug(applicationContext).first()
        }
        swDebug.setOnCheckedChangeListener { _, checked ->
            scope.launch {
                // (п.1) Запись в DataStore от имени applicationContext
                DevPrefs.setDebug(applicationContext, checked)
                Logger.setMinLevel(if (checked) LogLevel.DEBUG else LogLevel.INFO)
                Toast.makeText(this@DevMenuActivity, "DEBUG logs: $checked", Toast.LENGTH_SHORT).show()
            }
        }

        btnExport.setOnClickListener {
            scope.launch {
                try {
                    val zip = DiagnosticsManager.exportZip(this@DevMenuActivity)
                    Logger.i("IO", "diag.export.ui", mapOf("zip" to zip.absolutePath))
                    // (п.2) FileProvider вместо устаревшего file://
                    val uri = FileProvider.getUriForFile(
                        this@DevMenuActivity,
                        "${applicationContext.packageName}.fileprovider",
                        zip
                    )
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

        // Stage-2: выбор изображения и запуск входного пайплайна
        btnStage2.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                // ВАЖНО: просим разовый READ и возможность персистить
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            // (WRITE нам не нужен для чтения изображения)
            }
            startActivityForResult(intent, REQ_PICK_IMAGE)
        }
        // Stage-3: анализ превью (маски+метрики+классификация)
        btnStage3.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_PICK_ANALYZE)
        }
        // Stage-4: PresetGate
        btnStage4.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_PICK_PRESET)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            // Персистим право чтения для SAF (если доступно)
            try {
                var takeFlags = (data?.flags ?: 0) and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                // Фолбэк: на некоторых OEM data.flags может быть 0 — берём READ вручную
                if (takeFlags == 0) {
                    takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) { /* not fatal */ }
            runStage2(uri)
        }
        if (requestCode == REQ_PICK_ANALYZE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                var takeFlags = (data?.flags ?: 0) and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) { /* ignore */ }
            runStage3(uri)
        }

        if (requestCode == REQ_PICK_PRESET && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                var takeFlags = (data?.flags ?: 0) and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) { /* ignore */ }
            runStage4(uri)
        }

    }

    private fun runStage2(uri: Uri) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    ImagePrep.prepare(this@DevMenuActivity, uri)
                }
                // Сохраняем результат в cache для быстрой проверки
                val out = File(cacheDir, "stage2_result.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(out).use { fos ->
                        res.linearF16.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
                Logger.i("IO", "prep.result", mapOf(
                    "uri" to uri.toString(),
                    "hdr" to res.wasHdrTonemapped,
                    "cs" to (res.srcColorSpace?.name ?: "unknown"),
                    "blk_mean" to res.blockiness.mean,
                    "halo" to res.haloScore,
                    "out" to out.absolutePath,
                    "bytes" to out.length()
                ))
                Toast.makeText(this@DevMenuActivity, "Stage-2 OK → ${out.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("IO", "prep.fail", err = e, data = mapOf("uri" to uri.toString()))
                Toast.makeText(this@DevMenuActivity, "Stage-2 error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        // (п.1) Исключаем утечки: отменяем пользовательскую scope
        scope.cancel()
    }
    private fun runStage3(uri: Uri) {
        scope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    Stage3Analyze.run(this@DevMenuActivity, uri)
                }
                val dir = DiagnosticsManager.currentSessionDir(this@DevMenuActivity)
                val out = File(cacheDir, "stage3_preview.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(out).use { fos ->
                        res.preview.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
                Logger.i("ANALYZE", "stage3.result", mapOf(
                    "kind" to res.decision.kind.name,
                    "subtype" to (res.decision.subtype ?: "-"),
                    "confidence" to "%.2f".format(res.decision.confidence),
                    "preview" to out.absolutePath
                ))
                Toast.makeText(this@DevMenuActivity, "Stage-3: ${res.decision.kind} (${res.decision.subtype ?: "-"})", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("ANALYZE", "stage3.fail", err = e, data = mapOf("uri" to uri.toString()))
                Toast.makeText(this@DevMenuActivity, "Stage-3 error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runStage4(uri: Uri) {
        scope.launch {
            try {
                val out = withContext(Dispatchers.Default) {
                    Stage4Runner.run(this@DevMenuActivity, uri, targetWst = 240 /* по умолчанию A3/14ct коридор */)
                }
                Logger.i("PGATE", "stage4.result", mapOf(
                    "preset" to out.gate.spec.id,
                    "addons" to out.gate.spec.addons.joinToString(","),
                    "r" to out.gate.r,
                    "json" to out.jsonPath
                ))
                Toast.makeText(this@DevMenuActivity, "Stage‑4: ${out.gate.spec.id}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Logger.e("PGATE", "stage4.fail", err = e, data = mapOf("uri" to uri.toString()))
            }
        }
    }
}