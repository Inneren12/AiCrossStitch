// app/src/main/java/com/appforcross/editor/ui/pattern/PatternViewModel.kt
package com.appforcross.editor.ui.pattern

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.export.ImageExportRunner
import com.appforcross.editor.pattern.LegendIo
import com.appforcross.editor.pattern.PatternLegend
import com.appforcross.editor.pattern.Symbolizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.appforcross.editor.pattern.PatternSymbols

class PatternViewModel(
    private val appContext: Context,
    private val diag: DiagnosticsManager,
    private val sessionDir: File
) : ViewModel() {

    private val legendFile = File(sessionDir, "pattern_legend.json")
    private val previewFile = File(sessionDir, "pattern_preview.png")
    private val imageExporter by lazy { ImageExportRunner(appContext, diag, sessionDir) }

    private val _legend = MutableStateFlow<PatternLegend?>(null)
    val legend: StateFlow<PatternLegend?> = _legend

    private val _hasPreview = MutableStateFlow<Boolean>(false)
    val hasPreview: StateFlow<Boolean> = _hasPreview

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _legend.value = LegendIo.load(legendFile)?.let { PatternSymbols.normalizeUnique(it) }
            _hasPreview.value = previewFile.exists()
        }
    }

    fun setSymbol(idx: Int, symbol: String) {
        val cur = _legend.value ?: return
        val new = cur.copy(
            overrides = cur.overrides.toMutableMap().apply { put(idx, symbol) }
        )
        val normalized = PatternSymbols.normalizeUnique(new)
        _legend.value = normalized
        viewModelScope.launch(Dispatchers.IO) {
            LegendIo.save(legendFile, normalized)
            // (опционально) здесь можно перестроить pattern_preview.png, если у тебя есть быстрый рендер
        }
    }

    fun exportPng(uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = imageExporter.exportTo(uri)
            withContext(Dispatchers.Main) { onDone(ok) }
        }
    }
}
