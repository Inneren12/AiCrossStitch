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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.appforcross.editor.prescale.PreScaleRunner
import com.appforcross.quant.QuantizeRunner
import kotlin.math.min
import com.appforcross.editor.catalog.CatalogMapRunner
import androidx.compose.material3.ExperimentalMaterial3Api
import com.appforcross.editor.catalog.ThreadCatalogs
import com.appforcross.editor.pattern.PatternRunner
import com.appforcross.editor.export.PdfExportRunner
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    var analyze by remember { mutableStateOf<AnalyzeResult?>(null) }
    var sourceWidth by remember { mutableStateOf<Int?>(null) }
    var gate by remember { mutableStateOf<PresetGateResult?>(null) }
    var targetWst by rememberSaveable { mutableStateOf(240f) } // по умолчанию «A3/14ct коридор»

    // Результат PreScale
    var preOut by remember { mutableStateOf<PreScaleRunner.Output?>(null) }

    // Параметры и результат Quantize
    var kMax by rememberSaveable { mutableStateOf(28f) }
    var qOut by remember { mutableStateOf<QuantizeRunner.Output?>(null) }
    var enableOrdered by rememberSaveable { mutableStateOf(true) }
    var enableFS by rememberSaveable { mutableStateOf(true) }
    // Catalog map
    var brand by rememberSaveable { mutableStateOf("DMC") }
    var allowBlends by rememberSaveable { mutableStateOf(true) }
    var catOut by remember { mutableStateOf<CatalogMapRunner.Output?>(null) }

    // Pattern
    var patOut by remember { mutableStateOf<PatternRunner.Output?>(null) }
    var minRunFlat by rememberSaveable { mutableStateOf(4) }
    var minRunEdge by rememberSaveable { mutableStateOf(3) }

    // Export PDF
    var cellSizeMm by rememberSaveable { mutableStateOf("3.0") }
    var marginMm by rememberSaveable { mutableStateOf("10") }
    var overlap by rememberSaveable { mutableStateOf("8") }
    var renderSymbols by rememberSaveable { mutableStateOf(true) }
    var pageA3 by rememberSaveable { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf<String?>(null) }

    // PDF Preview
    var previewPage by rememberSaveable { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(0) }
    var previewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var previewBusy by remember { mutableStateOf(false) }

    // Save As launcher (SAF)
    val saveAs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        val q = qOut; val p = patOut
        if (uri != null && q != null && p != null) {
            scope.launch {
                busy = true; err = null
                try {
                    val bytes = withContext(Dispatchers.Default) {
                        PdfExportRunner.runToBytes(
                            ctx = ctx,
                            indexBinPath = p.indexBin,
                            colorPngPath = q.colorPng,
                            palette = q.palette,
                            legendJsonPath = p.legendJson,
                            opt = PdfExportRunner.Options(
                                page = if (pageA3) PdfExportRunner.PageSize.A3 else PdfExportRunner.PageSize.A4,
                                orientation = PdfExportRunner.Orientation.PORTRAIT,
                                cellSizeMm = cellSizeMm.toFloatOrNull() ?: 3.0f,
                                marginMm = marginMm.toFloatOrNull() ?: 10f,
                                overlapCells = overlap.toIntOrNull() ?: 8,
                                boldEvery = 10,
                                render = if (renderSymbols) PdfExportRunner.RenderMode.SYMBOLS else PdfExportRunner.RenderMode.COLOR,
                                includeLegend = true
                            )
                        )
                    }
                    ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes.bytes) }
                    exportPath = uri.toString()
                } catch (e: Exception) {
                    err = "Save As failed: $e"
                    com.appforcross.editor.logging.Logger.e("UI","EXPORT.saveas.fail", err = e)
                } finally { busy = false }
            }
        }
    }

    var brands by remember { mutableStateOf(listOf("DMC")) }
    var brandMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val avail: List<String> = ThreadCatalogs.listAvailable(ctx)
        brands = if (avail.isNotEmpty()) avail else listOf("DMC")
        if (!brands.contains(brand)) {
            // подстрахуемся на случай пустого списка
            brand = brands.firstOrNull() ?: "DMC"
        }
    }
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
                onAnalyze = {
                    analyze = it
                    sourceWidth = it.sourceWidth
                },
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
                        scope.launch {
                            busy = true; err = null
                            try {
                                val wpx = sourceWidth ?: a.sourceWidth
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
                    enabled = !busy && gate != null && analyze != null && pickedUri != null,
                    onClick = {
                        val g = gate ?: return@OutlinedButton
                        val a = analyze ?: return@OutlinedButton
                        val uri = pickedUri ?: return@OutlinedButton
                        scope.launch {
                            busy = true; err = null
                            try {
                                val out = withContext(Dispatchers.Default) {
                                    PreScaleRunner.run(
                                        ctx = ctx,
                                        uri = uri,
                                        analyze = a,
                                        gate = g,
                                        targetWst = targetWst.roundToInt()
                                    )
                                }
                                preOut = out
                                Logger.i("UI", "IMPORT.preset.apply", mapOf(
                                    "preset" to g.spec.id, "Wst" to out.wst, "Hst" to out.hst,
                                    "ssim" to out.fr.ssim, "edgeKeep" to out.fr.edgeKeep,
                                    "banding" to out.fr.banding, "de95" to out.fr.de95,
                                    "png" to out.pngPath, "pass" to out.passed
                                ))
                            } catch (e: Exception) {
                                err = "PreScale failed: ${e.message}"
                                Logger.e("UI", "IMPORT.preset.apply.fail", err = e)
                            } finally {
                                busy = false
                            }
                        }
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

        // PreScale result
        preOut?.let { o ->
            HorizontalDivider()
            Text("PreScale", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Grid: ${o.wst} × ${o.hst}")
            Text("SSIM: ${fmt(o.fr.ssim)} • EdgeKeep: ${fmt(o.fr.edgeKeep)}")
            Text("Banding: ${fmt(o.fr.banding)} • ΔE95: ${fmt(o.fr.de95)}")
            Text("Pass: ${o.passed}")
            Text("PNG: ${o.pngPath}", style = MaterialTheme.typography.bodySmall)
        }

        // ---------- Quantize ----------
        if (preOut != null && analyze != null && gate != null) {
            HorizontalDivider()
            Text("Quantize", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("Max colors (K): ${kMax.roundToInt()}")
            Slider(
                value = kMax,
                onValueChange = { kMax = it },
                valueRange = 12f..44f,
                steps = 44 - 12 - 1
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableOrdered, onCheckedChange = { enableOrdered = it })
                    Text("Ordered (Sky/Flat/Skin)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableFS, onCheckedChange = { enableFS = it })
                    Text("FS (HiTex, edge‑aware)")
                }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val pre = preOut ?: return@OutlinedButton
                    val a = analyze ?: return@OutlinedButton
                    val g = gate ?: return@OutlinedButton
                    scope.launch {
                        busy = true; err = null
                        try {
                            val out = withContext(Dispatchers.Default) {
                                QuantizeRunner.run(
                                    ctx = ctx,
                                    preScalePng = pre.pngPath,
                                    analyze = a,
                                    gate = g,
                                    opt = QuantizeRunner.Options(
                                        kStart = min(16, kMax.roundToInt()),
                                        kMax = kMax.roundToInt(),
                                        deltaEMin = 3.0,
                                        useOrdered = enableOrdered,
                                        useFS = enableFS
                                    )
                                )
                            }
                            qOut = out
                            Logger.i("UI", "IMPORT.quant.done", mapOf(
                                "k" to out.k, "deMin" to out.deMin, "deMed" to out.deMed,
                                "avgErr" to out.avgErr, "png" to out.colorPng
                            ))
                        } catch (e: Exception) {
                            err = "Quantize failed: ${e}"
                            com.appforcross.editor.logging.Logger.e("UI","IMPORT.quant.fail", err = e)
                        } finally { busy = false }
                    }
                }
            ) { Text("Run Quantize") }
        }
        qOut?.let { q ->
            Spacer(Modifier.height(8.dp))
            Text("Palette K: ${q.k}")
            Text("ΔE min / med: ${fmt(q.deMin)} / ${fmt(q.deMed)}")
            Text("Avg ΔE: ${fmt(q.avgErr)}")
            Text("Out: ${q.colorPng}")
            Text("Index: ${q.indexBin}")
            Text("Palette: ${q.paletteJson}")
        }

        // ---------- Catalog Map ----------
        if (qOut != null) {
            HorizontalDivider()
            Text("Catalog", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            // простейший селектор бренда (одна опция пока)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Brand:")
                ExposedDropdownMenuBox(
                    expanded = brandMenu,
                    onExpandedChange = { brandMenu = !brandMenu }
                ) {
                    OutlinedTextField(
                        value = brand,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(),
                        label = { Text("Palette") }
                    )
                    ExposedDropdownMenu(
                        expanded = brandMenu,
                        onDismissRequest = { brandMenu = false }
                    ) {
                        brands.forEach { b ->
                            DropdownMenuItem(
                                text = { Text(b) },
                                onClick = { brand = b; brandMenu = false }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowBlends, onCheckedChange = { allowBlends = it })
                    Text("Allow blends (1:1)")
                }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val q = qOut ?: return@OutlinedButton
                    scope.launch {
                        busy = true; err = null
                        try {
                            val out = withContext(Dispatchers.Default) {
                                CatalogMapRunner.run(
                                    ctx = ctx,
                                    palette = q.palette,
                                    brand = brand,
                                    options = com.appforcross.editor.catalog.CatalogMapOptions(
                                        allowBlends = allowBlends,
                                        maxBlends = 4,
                                        blendPenalty = 0.7
                                    )
                                )
                            }
                            catOut = out
                        } catch (e: Exception) {
                            err = "Catalog map failed: ${e}"
                            com.appforcross.editor.logging.Logger.e("UI","IMPORT.catalog.fail", err = e)
                        } finally { busy = false }
                    }
                }
            ) { Text("Map to $brand") }
        }
        catOut?.let { c ->
            Spacer(Modifier.height(8.dp))
            Text("Brand: ${c.brand}")
            Text("AvgΔE / MaxΔE: ${fmt(c.avgDE)} / ${fmt(c.maxDE)}")
            Text("Blends used: ${c.blends}")
            Text("JSON: ${c.jsonPath}")
        }

        // ---------- Pattern ----------
        if (qOut != null) {
            HorizontalDivider()
            Text("Pattern", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = minRunFlat.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { minRunFlat = it.coerceAtLeast(1) } },
                    label = { Text("Min‑run flat") },
                    modifier = Modifier.width(140.dp)
                )
                OutlinedTextField(
                    value = minRunEdge.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { minRunEdge = it.coerceAtLeast(1) } },
                    label = { Text("Min‑run edge") },
                    modifier = Modifier.width(140.dp)
                )
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val q = qOut ?: return@OutlinedButton
                    val cat = catOut // может быть null
                    scope.launch {
                        busy = true; err = null
                        try {
                            val out = withContext(Dispatchers.Default) {
                                PatternRunner.run(
                                    ctx = ctx,
                                    palette = q.palette,
                                    indexBinPath = q.indexBin,
                                    colorPngPath = q.colorPng,
                                    catalogJsonPath = cat?.jsonPath,
                                    opt = PatternRunner.Options(
                                        minRunFlat = minRunFlat,
                                        minRunEdge = minRunEdge
                                    )
                                )
                            }
                            patOut = out
                        } catch (e: Exception) {
                            err = "Pattern build failed: ${e}"
                            com.appforcross.editor.logging.Logger.e("UI","IMPORT.pattern.fail", err = e)
                        } finally { busy = false }
                    }
                }
            ) { Text("Build Pattern") }
        }
        patOut?.let { p ->
            Spacer(Modifier.height(8.dp))
            Text("Thread changes /100: ${fmt(p.changesPer100)}")
            Text("Small islands /1000: ${fmt(p.smallIslandsPer1000)}")
            Text("Run median: ${fmt(p.runMedian)}")
            Text("Index: ${p.indexBin}")
            Text("Legend: ${p.legendJson}")
            Text("Preview: ${p.previewPng}")
        }

        // ---------- PDF Preview (before export) ----------
        if (qOut != null && patOut != null) {
            HorizontalDivider()
            Text("PDF Preview", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            // ререндер превью при изменении параметров
            LaunchedEffect(qOut?.colorPng, patOut?.indexBin, cellSizeMm, marginMm, overlap, renderSymbols, pageA3, previewPage) {
                val q = qOut!!; val p = patOut!!
                previewBusy = true
                try {
                    val prev = withContext(Dispatchers.Default) {
                        PdfExportRunner.renderPreviewBitmap(
                            ctx = ctx,
                            indexBinPath = p.indexBin,
                            colorPngPath = q.colorPng,
                            palette = q.palette,
                            legendJsonPath = p.legendJson,
                            opt = PdfExportRunner.Options(
                                page = if (pageA3) PdfExportRunner.PageSize.A3 else PdfExportRunner.PageSize.A4,
                                orientation = PdfExportRunner.Orientation.PORTRAIT,
                                cellSizeMm = cellSizeMm.toFloatOrNull() ?: 3.0f,
                                marginMm = marginMm.toFloatOrNull() ?: 10f,
                                overlapCells = overlap.toIntOrNull() ?: 8,
                                boldEvery = 10,
                                render = if (renderSymbols) PdfExportRunner.RenderMode.SYMBOLS else PdfExportRunner.RenderMode.COLOR,
                                includeLegend = true
                            ),
                            pageIndex = previewPage.coerceAtLeast(1),
                            maxSidePx = 1400
                        )
                    }
                    totalPages = prev.totalPages
                    if (previewPage > totalPages) previewPage = totalPages
                    previewBmp = prev.bitmap
                } catch (e: Exception) {
                    err = "Preview failed: $e"
                    com.appforcross.editor.logging.Logger.e("UI","EXPORT.preview.fail", err = e)
                } finally {
                    previewBusy = false
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !previewBusy && previewPage > 1, onClick = { previewPage = (previewPage - 1).coerceAtLeast(1) }) {
                    Text("◀ Prev")
                }
                Text("Page $previewPage / ${maxOf(1, totalPages)}")
                OutlinedButton(enabled = !previewBusy && (previewPage < maxOf(1, totalPages)), onClick = { previewPage = (previewPage + 1).coerceAtMost(maxOf(1,totalPages)) }) {
                    Text("Next ▶")
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 520.dp)) {
                val bm = previewBmp
                if (bm != null) {
                    Image(bm.asImageBitmap(), contentDescription = "PDF preview", modifier = Modifier.fillMaxSize())
                } else {
                    Text(if (previewBusy) "Rendering preview…" else "Preview not available")
                }
            }
        }

        // ---------- Export (PDF) ----------
        if (qOut != null && patOut != null) {
            HorizontalDivider()
            Text("Export (PDF)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cellSizeMm,
                    onValueChange = { cellSizeMm = it.filter { ch -> ch.isDigit() || ch=='.' } },
                    label = { Text("Cell size, mm") },
                    modifier = Modifier.width(140.dp)
                )
                OutlinedTextField(
                    value = marginMm,
                    onValueChange = { marginMm = it.filter { ch -> ch.isDigit() || ch=='.' } },
                    label = { Text("Margins, mm") },
                    modifier = Modifier.width(140.dp)
                )
                OutlinedTextField(
                    value = overlap,
                    onValueChange = { overlap = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Overlap, cells") },
                    modifier = Modifier.width(140.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = renderSymbols, onCheckedChange = { renderSymbols = it })
                    Text("Symbols (off = Colors)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pageA3, onCheckedChange = { pageA3 = it })
                    Text("A3 (off = A4)")
                }
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val q = qOut ?: return@OutlinedButton
                    val p = patOut ?: return@OutlinedButton
                    // "Сохранить как…" через SAF
                    val defaultName = "AiCrossStitch_pattern.pdf"
                    saveAs.launch(defaultName)
                }
            ) { Text("Save as… PDF") }
        }
        exportPath?.let { path ->
            Spacer(Modifier.height(6.dp))
            Text("PDF: $path")
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
            val wpx = analyze.sourceWidth
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