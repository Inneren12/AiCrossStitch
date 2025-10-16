package com.appforcross.editor.preset

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.appforcross.editor.analysis.Stage3Analyze
import com.appforcross.editor.io.Decoder
import com.appforcross.editor.logging.Logger
import java.io.File
import java.io.FileOutputStream

/** Оркестратор Stage-4: анализ превью → PresetGate → JSON-дамп результата. */
object Stage4Runner {
    data class Output(
        val gate: PresetGateResult,
        val jsonPath: String
    )

    fun run(ctx: Context, uri: Uri, targetWst: Int? = 240): Output {
        // 1) Быстрый декод, чтобы знать ширину исходника
        val dec = Decoder.decodeUri(ctx, uri)
        val wpx = dec.width
        // 2) Stage-3 анализ
        val analyze = Stage3Analyze.run(ctx, uri)
        // 3) PresetGate
        val res = PresetGate.run(
            an = analyze,
            sourceWpx = wpx,
            options = PresetGateOptions(targetWst = targetWst)
        )
        // 4) JSON дамп в cache/diag
        val json = toJson(res)
        val out = File(ctx.cacheDir, "preset_spec.json")
        FileOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        Logger.i("PGATE", "preset.json.saved", mapOf("path" to out.absolutePath, "bytes" to out.length()))
        return Output(gate = res, jsonPath = out.absolutePath)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun toJson(r: PresetGateResult): String = buildString {
        append("{")
        append("\"preset\":\"").append(esc(r.spec.id)).append("\",")
        append("\"addons\":[")
        append(r.spec.addons.joinToString(","){ "\"${esc(it)}\"" })
        append("],")
        append("\"r\":").append("%.4f".format(r.r)).append(",")
        append("\"sigma\":{")
        append("\"base\":").append("%.4f".format(r.normalized.sigmaBase)).append(",")
        append("\"edge\":").append("%.4f".format(r.normalized.sigmaEdge)).append(",")
        append("\"flat\":").append("%.4f".format(r.normalized.sigmaFlat)).append("},")
        append("\"covers\":{")
        append("\"edge\":").append("%.3f".format(r.covers.edgePct)).append(",")
        append("\"flat\":").append("%.3f".format(r.covers.flatPct)).append(",")
        append("\"skin\":").append("%.3f".format(r.covers.skinPct)).append(",")
        append("\"sky\":").append("%.3f".format(r.covers.skyPct)).append(",")
        append("\"hitex_fine\":").append("%.3f".format(r.covers.hiTexFinePct)).append(",")
        append("\"hitex_coarse\":").append("%.3f".format(r.covers.hiTexCoarsePct)).append("},")
        append("\"reason\":\"").append(esc(r.reason)).append("\",")
        append("\"params\":{")
        // укороченный дамп параметров
        val s = r.spec
        fun kv(k:String,v:String) { append("\"").append(k).append("\":").append(v) }
        kv("wb_strength", s.color.wbStrength.toString()); append(",")
        kv("gamma_target", s.color.gammaTarget.toString()); append(",")
        kv("rolloff", s.color.rolloff.toString()); append(",")
        kv("nr_luma_r", s.nr.lumaRadius.toString()); append(",")
        kv("nr_luma_eps", s.nr.lumaEps.toString()); append(",")
        kv("nr_chroma_gain", s.nr.chromaGain.toString()); append(",")
        kv("tex_flat", s.texture.smoothFlat.toString()); append(",")
        kv("tex_nonflat", s.texture.smoothNonFlat.toString()); append(",")
        kv("skin_sat_delta", s.unify.skinSatDelta.toString()); append(",")
        kv("skin_tone_smooth", s.unify.skinToneSmooth.toString()); append(",")
        kv("sky_hue_mode", s.unify.skyHueShiftToMode.toString()); append(",")
        kv("sky_v_delta", s.unify.skyVdelta.toString()); append(",")
        kv("sky_grad_smooth", s.unify.skyGradSmooth.toString()); append(",")
        kv("edge_protect", s.edges.protectGain.toString()); append(",")
        kv("usm_amount", s.edges.preSharpenAmount.toString()); append(",")
        kv("usm_radius", s.edges.preSharpenRadius.toString()); append(",")
        kv("usm_thr", s.edges.preSharpenThreshold.toString()); append(",")
        kv("k_sigma", s.aaPref.kSigma.toString()); append(",")
        kv("edge_scale", s.aaPref.edgeScale.toString()); append(",")
        kv("flat_scale", s.aaPref.flatScale.toString()); append(",")
        kv("filter", "\"${s.scale.filter}\""); append(",")
        kv("micro_phase", s.scale.microPhaseTrials.toString()); append(",")
        kv("verify_ssim", s.verify.ssimMin.toString()); append(",")
        kv("verify_edge", s.verify.edgeKeepMin.toString()); append(",")
        kv("verify_banding", s.verify.bandingMax.toString()); append(",")
        kv("verify_de95", s.verify.de95Max.toString()); append(",")
        kv("post_dering", s.post.dering.toString()); append(",")
        kv("post_clahe_clip", s.post.claheClip.toString()); append(",")
        kv("dither_amp_L", s.quant.ditherAmpL.toString()); append(",")
        kv("dither_mask", "\"${s.quant.ditherMask}\""); append(",")
        kv("palette_bias", "\"${s.quant.paletteBias}\""); append(",")
        kv("quality", "\"${s.quality.tier}\"")
        append("}")
        append("}")
    }
}