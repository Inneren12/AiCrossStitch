package com.appforcross.editor.ui.tabs

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appforcross.editor.analysis.AnalyzeResult
import com.appforcross.editor.analysis.Stage3Analyze
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.preset.PresetGateResult
import com.appforcross.editor.preset.PresetGateOptions
import com.appforcross.editor.preset.PresetGate
import com.appforcross.editor.io.Decoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ImportTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    var analyze by remember { mutableStateOf<AnalyzeResult?>(null) }
    var gate by remember { mutableStateOf<PresetGateResult?>(null) }
    var targetWst by rememberSaveable { mutableStateOf(240f) } // по умолчанию «A3/14ct коридор»

    // Picker
    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            // Пытаемся персистить READ, если разрешено системой (фолбэк — игнор ошибок)
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            Logger.i("UI", "IMPORT.pick", mapOf("uri" to uri.toString()))
            // Автозапуск анализа и пресет-гейта
            runAnalyzeAndGate(
                ctx = ctx,
                scope = scope,
                uri = uri,
                targetWst = targetWst.roundToInt(),
                onBusy = { busy = it },
                onError = { err = it },
                onAnalyze = { analyze = it },
                onGate = { gate = it }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Import", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = { openDoc.launch(arrayOf("image/*")) }
            ) { Text("Choose image") }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Processing…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (pickedUri != null) {
            Text("Selected: ${pickedUri}", style = MaterialTheme.typography.bodySmall)
        }

        // Target Wst slider (виден когда есть выбор)
        if (pickedUri != null) {
            HorizontalDivider()
            Text("Target width (stitches): ${targetWst.roundToInt()}", fontWeight = FontWeight.SemiBold)
            Slider(
                value = targetWst,
                onValueChange = { targetWst = it },
                valueRange = 160f..340f,
                steps = 340 - 160 - 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy && pickedUri != null && analyze != null,
                    onClick = {
                        // Пересчитать только PresetGate под новый Wst (без повторного Stage‑3)
                        val a = analyze ?: return@OutlinedButton
                        val uri = pickedUri ?: return@OutlinedButton
                        scope.launch {
                            busy = true; err = null
                            try {
                                val wpx = withContext(Dispatchers.Default) { Decoder.decodeUri(ctx, uri).width }
                                val res = withContext(Dispatchers.Default) {
                                    PresetGate.run(
                                        an = a,
                                        sourceWpx = wpx,
                                        options = PresetGateOptions(targetWst = targetWst.roundToInt())
                                    )
                                }
                                gate = res
                                Logger.i("UI", "IMPORT.preset.recalc", mapOf("Wst" to targetWst.roundToInt(), "preset" to res.spec.id))
                            } catch (e: Exception) {
                                err = "Recalc failed: ${e.message}"
                                Logger.e("UI", "IMPORT.preset.recalc.fail", err = e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Recalculate Preset") }

                OutlinedButton(
                    enabled = !busy && gate != null,
                    onClick = {
                        Logger.i("UI", "IMPORT.preset.apply", mapOf(
                            "preset" to gate?.spec?.id,
                            "r" to gate?.r
                        ))
                        // Здесь на 6 спринте будем формировать PreScaleSpec и запускать PreScale
                    }
                ) { Text("Apply Preset") }
            }
        }

        // ANALYZE: summary
        analyze?.let { a ->
            HorizontalDivider()
            Text("Detected: ${a.decision.kind} ${a.decision.subtype?.let { "(${it})" } ?: ""} • conf=${fmt(a.decision.confidence)}",
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))

            val m = a.metrics
            MetricsRow("L_med", fmt(m.lMed), "DR P99–P1", fmt(m.drP99minusP1))
            MetricsRow("SatLo", pct(m.satLoPct), "SatHi", pct(m.satHiPct))
            MetricsRow("Cast(OK)", fmt(m.castOK), "NoiseY/C", "${fmt(m.noiseY)}/${fmt(m.noiseC)}")
            MetricsRow("EdgeRate", pct(m.edgeRate), "VarLap", fmt(m.varLap))
            MetricsRow("HazeScore", fmt(m.hazeScore), "FlatPct", pct(m.flatPct))
            MetricsRow("GradP95_sky", fmt(m.gradP95Sky), "GradP95_skin", fmt(m.gradP95Skin))
            MetricsRow("Colors(5‑bit)", "${m.colors5bit}", "Top8 cover", pct(m.top8Coverage))
            MetricsRow("Checker2×2", pct(m.checker2x2), "", "")
        }

        // PresetGate: summary
        gate?.let { g ->
            HorizontalDivider()
            Text("PresetGate", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Preset: ${g.spec.id}")
            Text("Addons: ${if (g.spec.addons.isEmpty()) "—" else g.spec.addons.joinToString()}")
            Text("Filter: ${g.spec.scale.filter} • micro‑phase: ${g.spec.scale.microPhaseTrials}")
            Text("r = ${fmt(g.r)}  •  σ_base=${fmt(g.normalized.sigmaBase)}  σ_edge=${fmt(g.normalized.sigmaEdge)}  σ_flat=${fmt(g.normalized.sigmaFlat)}")
            Spacer(Modifier.height(6.dp))
            Text("Covers (preview masks): edge=${pct(g.covers.edgePct)} flat=${pct(g.covers.flatPct)} skin=${pct(g.covers.skinPct)} sky=${pct(g.covers.skyPct)}")
        }

        err?.let {
            Spacer(Modifier.height(8.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MetricsRow(k1: String, v1: String, k2: String, v2: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$k1:", fontWeight = FontWeight.SemiBold); Text(v1)
        }
        if (k2.isNotEmpty()) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$k2:", fontWeight = FontWeight.SemiBold); Text(v2)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun pct(x: Double): String = "${(x * 100.0).coerceIn(0.0, 100.0).let { String.format("%.1f%%", it) }}"
private fun fmt(x: Double): String = String.format("%.3f", x)

private fun runAnalyzeAndGate(
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    uri: Uri,
    targetWst: Int,
    onBusy: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onAnalyze: (AnalyzeResult) -> Unit,
    onGate: (PresetGateResult) -> Unit
) {
    scope.launch {
        onBusy(true); onError(null)
        try {
            val analyze = withContext(Dispatchers.Default) { Stage3Analyze.run(ctx, uri) }
            onAnalyze(analyze)
            val wpx = withContext(Dispatchers.Default) { Decoder.decodeUri(ctx, uri).width }
            val gate = withContext(Dispatchers.Default) {
                PresetGate.run(analyze, sourceWpx = wpx, options = PresetGateOptions(targetWst = targetWst))
            }
            onGate(gate)
            Logger.i("UI", "IMPORT.done", mapOf("Wst" to targetWst, "preset" to gate.spec.id, "r" to gate.r))
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
            Logger.e("UI", "IMPORT.fail", err = e)
        } finally {
            onBusy(false)
        }
    }
}