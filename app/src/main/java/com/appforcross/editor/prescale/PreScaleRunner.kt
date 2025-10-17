package com.appforcross.editor.prescale

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.appforcross.editor.analysis.AnalyzeResult
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.io.ImagePrep
import com.appforcross.editor.logging.Logger
import com.appforcross.editor.preset.PresetGateResult
import com.appforcross.editor.preset.PresetSpec
import java.io.File
import java.io.FileOutputStream
import android.graphics.ColorSpace

object PreScaleRunner {
    data class Output(
        val pngPath: String,
        val wst: Int,
        val hst: Int,
        val fr: PreScale.FR,
        val passed: Boolean
    )

    /**
     * Полный запуск PreScale:
     *  - Stage-2 (ImagePrep) → linear RGBA_F16
     *  - PreScale.run(...) по выбранному пресету/σ(r)
     *  - сохранение PNG в cache и diag.
     */
    fun run(
        ctx: Context,
        uri: Uri,
        analyze: AnalyzeResult,
        gate: PresetGateResult,
        targetWst: Int
    ): Output {
        val prep = ImagePrep.prepare(ctx, uri)
        Logger.i("PRESCALE", "start", mapOf(
            "wpx" to prep.linearF16.width, "hpx" to prep.linearF16.height,
            "config" to prep.linearF16.config.toString(),
            "preset" to gate.spec.id, "Wst" to targetWst
        ))
        // На всякий случай: если не F16, приведём
        val lin = if (prep.linearF16.config == Bitmap.Config.RGBA_F16 && prep.linearF16.isMutable) {
            prep.linearF16
        } else {
            prep.linearF16.copy(Bitmap.Config.RGBA_F16, /*mutable*/ true)
        }
        val res = PreScale.run(
            linearF16 = lin,
            preset = gate.spec,
            norm = gate.normalized,
            masksPrev = analyze.masks,
            targetWst = targetWst
        )
        // Сохраняем
        val out = File(ctx.cacheDir, "prescale_${res.wst}x${res.hst}.png")
        FileOutputStream(out).use { fos ->
            res.out.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        // Диагностика (скопируем в сессию)
        try {
            DiagnosticsManager.currentSessionDir(ctx)?.let { dir ->
                val diag = File(dir, out.name)
                out.copyTo(diag, overwrite = true)
            }
        } catch (_: Exception) { }

        Logger.i("PRESCALE", "done", mapOf(
            "png" to out.absolutePath,
            "wst" to res.wst, "hst" to res.hst,
            "ssim" to "%.4f".format(res.fr.ssim),
            "edgeKeep" to "%.4f".format(res.fr.edgeKeep),
            "banding" to "%.4f".format(res.fr.banding),
            "de95" to "%.3f".format(res.fr.de95),
            "pass" to res.passed
        ))
        return Output(
            pngPath = out.absolutePath,
            wst = res.wst, hst = res.hst,
            fr = res.fr,
            passed = res.passed
        )
    }
}