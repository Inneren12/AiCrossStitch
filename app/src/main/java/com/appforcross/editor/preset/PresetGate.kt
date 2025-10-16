package com.appforcross.editor.preset

import android.graphics.Bitmap
import android.graphics.Color
import com.appforcross.editor.analysis.AnalyzeResult
import com.appforcross.editor.analysis.Metrics
import com.appforcross.editor.analysis.Masks
import com.appforcross.editor.logging.Logger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Тип фильтра ресемплинга для прескейла. */
enum class ScaleFilter { EWA_L3, EWA_Mitchell }
enum class QualityTier { FAST, BALANCED, MAX }

data class PresetSpec(
    val id: String,
    val addons: List<String>,
    val color: ColorParams,
    val nr: NRParams,
    val texture: TextureParams,
    val unify: UnifyParams,
    val edges: EdgeParams,
    val aaPref: AAPref,
    val scale: ScaleParams,
    val verify: VerifyParams,
    val post: PostParams,
    val quant: QuantHints,
    val quality: QualityParams
) {
    data class ColorParams(
        val wbStrength: Double,
        val gammaTarget: Double,
        val rolloff: Double
    )
    data class NRParams(
        val lumaRadius: Int,
        val lumaEps: Double,
        val chromaGain: Double,
        val maskFlatOnly: Boolean = true
    )
    data class TextureParams(
        val smoothFlat: Double,
        val smoothNonFlat: Double
    )
    data class UnifyParams(
        val skinSatDelta: Double,
        val skinToneSmooth: Double,
        val skyHueShiftToMode: Double,
        val skyVdelta: Double,
        val skyGradSmooth: Double = 0.0
    )
    data class EdgeParams(
        val protectGain: Double,
        val preSharpenAmount: Double,
        val preSharpenRadius: Double,
        val preSharpenThreshold: Int
    )
    data class AAPref(
        val kSigma: Double,
        val edgeScale: Double,
        val flatScale: Double
    ) {
        /** σ = kSigma * max(0, r-1). */
        fun sigmaBase(r: Double): Double = kSigma * max(0.0, r - 1.0)
    }
    data class ScaleParams(
        val filter: ScaleFilter,
        val microPhaseTrials: Int
    )
    data class VerifyParams(
        val ssimMin: Double,
        val edgeKeepMin: Double,
        val bandingMax: Double,
        val de95Max: Double
    )
    data class PostParams(
        val dering: Boolean,
        val localContrast: Boolean,
        val claheClip: Double
    )
    data class QuantHints(
        val ditherAmpL: Double,
        val ditherMask: String,
        val paletteBias: String
    )
    data class QualityParams(
        val tier: QualityTier,
        val bitdepthInternal: String
    )
}

/** Результат гейтинга. */
data class PresetGateResult(
    val spec: PresetSpec,
    val normalized: Normalized,      // σ и множители для заданного r
    val r: Double,                   // фактический фактор уменьшения по ширине (Wpx/Wst)
    val covers: RegionCovers,        // покрытия масок
    val reason: String               // короткое объяснение выбора
) {
    data class Normalized(val sigmaBase: Double, val sigmaEdge: Double, val sigmaFlat: Double)
}

/** Покрытие регионов (0..1). */
data class RegionCovers(
    val edgePct: Double,
    val flatPct: Double,
    val skinPct: Double,
    val skyPct: Double,
    val hiTexFinePct: Double,
    val hiTexCoarsePct: Double
)

/** Опции входа: целевой размер в стежках и режим. */
data class PresetGateOptions(
    val targetWst: Int? = null,     // если null — считаем r=1.25 как безопасный минимум антиалиаса
    val quality: QualityTier = QualityTier.BALANCED
)

object PresetGate {

    // ---------- БАЗОВЫЙ ПРОФИЛЬ ----------
    private fun baseSpec(tier: QualityTier) = PresetSpec(
        id = "FOTO/AutoBalanced",
        addons = emptyList(),
        color = PresetSpec.ColorParams(wbStrength = 0.5, gammaTarget = 0.48, rolloff = 0.08),
        nr = PresetSpec.NRParams(lumaRadius = 3, lumaEps = 0.003, chromaGain = 1.0),
        texture = PresetSpec.TextureParams(smoothFlat = 0.25, smoothNonFlat = 0.10),
        unify = PresetSpec.UnifyParams(skinSatDelta = -0.07, skinToneSmooth = 0.20, skyHueShiftToMode = 0.07, skyVdelta = -0.04),
        edges = PresetSpec.EdgeParams(protectGain = 0.8, preSharpenAmount = 0.28, preSharpenRadius = 1.0, preSharpenThreshold = 3),
        aaPref = PresetSpec.AAPref(kSigma = 0.50, edgeScale = 0.5, flatScale = 1.0),
        scale = PresetSpec.ScaleParams(filter = ScaleFilter.EWA_Mitchell, microPhaseTrials = when(tier){QualityTier.FAST->1;QualityTier.BALANCED->2;QualityTier.MAX->4}),
        verify = PresetSpec.VerifyParams(ssimMin = 0.985, edgeKeepMin = 0.98, bandingMax = 0.003, de95Max = 3.0),
        post = PresetSpec.PostParams(dering = true, localContrast = true, claheClip = 1.8),
        quant = PresetSpec.QuantHints(ditherAmpL = 0.40, ditherMask = "Flat|Sky|Skin", paletteBias = "neutral"),
        quality = PresetSpec.QualityParams(tier = tier, bitdepthInternal = "16F")
    )

    // ---------- ПРЕСЕТЫ (дельты к базе) ----------
    private fun presetPortrait(base: PresetSpec) = base.copy(
        id = "FOTO/PortraitSoftSkin",
        nr = base.nr.copy(chromaGain = 1.4),
        texture = base.texture.copy(smoothFlat = base.texture.smoothFlat + 0.10, smoothNonFlat = base.texture.smoothNonFlat + 0.05),
        unify = base.unify.copy(skinSatDelta = -0.10, skinToneSmooth = 0.30, skyHueShiftToMode = 0.0, skyVdelta = 0.0),
        edges = base.edges.copy(preSharpenAmount = 0.22, preSharpenRadius = 0.9, preSharpenThreshold = 4),
        scale = base.scale.copy(filter = ScaleFilter.EWA_Mitchell),
        quant = base.quant.copy(ditherAmpL = 0.35, paletteBias = "skin-neutral")
    )
    private fun presetLandscapeGrad(base: PresetSpec) = base.copy(
        id = "FOTO/LandscapeGradients",
        color = base.color.copy(rolloff = base.color.rolloff + 0.02),
        texture = base.texture.copy(smoothFlat = base.texture.smoothFlat + 0.05),
        unify = base.unify.copy(skyHueShiftToMode = 0.10, skyVdelta = -0.06, skyGradSmooth = 0.10),
        aaPref = base.aaPref.copy(kSigma = 0.55),
        scale = base.scale.copy(filter = ScaleFilter.EWA_Mitchell, microPhaseTrials = base.scale.microPhaseTrials + 1),
        quant = base.quant.copy(ditherAmpL = 0.55, ditherMask = "Flat|Sky", paletteBias = "neutral")
    )
    private fun presetArchitecture(base: PresetSpec) = base.copy(
        id = "FOTO/ArchitectureEdges",
        nr = base.nr.copy(lumaEps = base.nr.lumaEps * 0.8, chromaGain = 0.9),
        texture = base.texture.copy(smoothFlat = base.texture.smoothFlat - 0.05),
        edges = base.edges.copy(protectGain = 0.9, preSharpenAmount = 0.33, preSharpenRadius = 1.1, preSharpenThreshold = 2),
        aaPref = base.aaPref.copy(kSigma = 0.45, edgeScale = 0.65),
        scale = base.scale.copy(filter = ScaleFilter.EWA_L3, microPhaseTrials = base.scale.microPhaseTrials + 2),
        quant = base.quant.copy(ditherAmpL = 0.30, paletteBias = "cool-neutral")
    )
    private fun presetAnimals(base: PresetSpec) = base.copy(
        id = "FOTO/AnimalsFurFeather",
        nr = base.nr.copy(lumaRadius = base.nr.lumaRadius + 1, chromaGain = 1.1),
        texture = base.texture.copy(smoothNonFlat = max(0.0, base.texture.smoothNonFlat - 0.05)),
        edges = base.edges.copy(preSharpenAmount = 0.30, preSharpenRadius = 1.0, preSharpenThreshold = 3),
        scale = base.scale.copy(filter = ScaleFilter.EWA_L3),
        quant = base.quant.copy(ditherAmpL = 0.45, paletteBias = "warm-natural")
    )
    private fun presetLowLight(base: PresetSpec) = base.copy(
        id = "FOTO/LowLightISO",
        color = base.color.copy(gammaTarget = 0.50, rolloff = base.color.rolloff + 0.02),
        nr = base.nr.copy(lumaRadius = 5, lumaEps = 0.008, chromaGain = 1.8),
        texture = base.texture.copy(smoothFlat = base.texture.smoothFlat + 0.10),
        edges = base.edges.copy(preSharpenAmount = 0.24, preSharpenRadius = 1.1, preSharpenThreshold = 4),
        aaPref = base.aaPref.copy(kSigma = 0.55),
        quant = base.quant.copy(ditherAmpL = 0.50)
    )
    private fun presetOldScan(base: PresetSpec) = base.copy(
        id = "FOTO/OldScanRestore",
        color = base.color.copy(wbStrength = 0.7, rolloff = 0.10),
        nr = base.nr.copy(chromaGain = 1.6, lumaRadius = 4, lumaEps = 0.006),
        texture = base.texture.copy(smoothFlat = base.texture.smoothFlat + 0.05),
        unify = base.unify.copy(skinSatDelta = -0.08, skyHueShiftToMode = 0.08),
        edges = base.edges.copy(preSharpenAmount = 0.26, preSharpenRadius = 1.0, preSharpenThreshold = 4),
        quant = base.quant.copy(ditherAmpL = 0.45)
    )
    private fun presetSmallCanvas(base: PresetSpec) = base.copy(
        id = "FOTO/SmallCanvasMinColors",
        color = base.color.copy(rolloff = base.color.rolloff + 0.03),
        nr = base.nr.copy(chromaGain = 1.2),
        texture = base.texture.copy(smoothFlat = base.texture.smoothFlat + 0.10, smoothNonFlat = base.texture.smoothNonFlat + 0.05),
        unify = base.unify.copy(skinSatDelta = -0.12, skinToneSmooth = 0.35, skyHueShiftToMode = 0.12, skyVdelta = -0.07),
        edges = base.edges.copy(preSharpenAmount = 0.20, preSharpenRadius = 0.9, preSharpenThreshold = 3),
        aaPref = base.aaPref.copy(kSigma = 0.60),
        quant = base.quant.copy(ditherAmpL = 0.60, paletteBias = "low-count")
    )
    private fun presetLargeCanvas(base: PresetSpec) = base.copy(
        id = "FOTO/LargeCanvasHiDetail",
        nr = base.nr.copy(lumaEps = base.nr.lumaEps * 0.75, chromaGain = 0.9),
        texture = base.texture.copy(smoothFlat = base.texture.smoothFlat, smoothNonFlat = base.texture.smoothNonFlat),
        edges = base.edges.copy(preSharpenAmount = 0.34, preSharpenRadius = 1.2, preSharpenThreshold = 2),
        aaPref = base.aaPref.copy(kSigma = 0.45),
        scale = base.scale.copy(filter = ScaleFilter.EWA_L3, microPhaseTrials = base.scale.microPhaseTrials + 2),
        quant = base.quant.copy(ditherAmpL = 0.35, paletteBias = "hi-detail")
    )

    // ---------- АДДОНЫ (минимум: SkinProtect, SkyBandingShield, EdgeGuard+) ----------
    private fun applyAddons(spec: PresetSpec, addons: MutableList<String>, m: Metrics, cov: RegionCovers): PresetSpec {
        var s = spec
        // ADD_SKIN_PROTECT: если skin покрытие заметное → дополнительно мягче
        if (cov.skinPct >= 0.12) {
            s = s.copy(
                unify = s.unify.copy(skinToneSmooth = s.unify.skinToneSmooth + 0.10),
                quant = s.quant.copy(ditherMask = "${s.quant.ditherMask}|Skin")
            )
            addons += "ADD_SKIN_PROTECT"
        }
        // ADD_SKY_BANDING_SHIELD: сильные градиенты неба → поднять дизер амплитуду
        if (cov.skyPct >= 0.15 && m.gradP95Sky >= 0.7) {
            s = s.copy(
                unify = s.unify.copy(skyGradSmooth = max(0.10, s.unify.skyGradSmooth)),
                quant = s.quant.copy(ditherAmpL = max(s.quant.ditherAmpL, 0.55))
            )
            addons += "ADD_SKY_BANDING_SHIELD"
        }
        // ADD_EDGE_GUARD+: когда edgeRate высок и резкость средняя
        if (m.edgeRate >= 0.28 && m.varLap in 0.06..0.12) {
            s = s.copy(edges = s.edges.copy(preSharpenThreshold = max(1, s.edges.preSharpenThreshold - 1)))
            addons += "ADD_EDGE_GUARD+"
        }
        return s
    }

    // ---------- Основа гейтинга ----------
    fun run(an: AnalyzeResult, sourceWpx: Int, options: PresetGateOptions): PresetGateResult {
        val tier = options.quality
        val base = baseSpec(tier)
        val m = an.metrics
        val cov = coverage(an.masks)
        val skinPct = cov.skinPct
        val skyPct = cov.skyPct
        val hiTexPct = max(cov.hiTexFinePct, cov.hiTexCoarsePct)

        // Выбор пресета (приоритетами)
        var spec = base
        var reason = "AUTO_BALANCED"
        when {
            // Low light / high ISO
            m.noiseY >= 2.0 || m.noiseC >= 2.0 -> {
                spec = presetLowLight(base); reason = "LOWLIGHT_STRONGNR"
            }
            // Portrait / soft skin
            skinPct >= 0.12 && m.edgeRate in 0.12..0.30 -> {
                spec = presetPortrait(base); reason = "PORTRAIT_SOFT"
            }
            // Sky gradients / landscapes
            skyPct >= 0.15 || m.gradP95Sky >= 0.7 -> {
                spec = presetLandscapeGrad(base); reason = "SKY_GRADIENT_SAFE"
            }
            // Architecture / hard edges
            m.edgeRate >= 0.28 && m.varLap >= 0.06 && skyPct < 0.25 -> {
                spec = presetArchitecture(base); reason = "ARCHITECTURE_EDGES"
            }
            // Animals / foliage (texture keeper)
            hiTexPct >= 0.25 && m.noiseY < 2.0 -> {
                spec = presetAnimals(base); reason = "TEXTURE_KEEPER"
            }
        }

        // Модификаторы под размер полотна (грубые коридоры — A3, 14ct)
        val targetWst = options.targetWst
        if (targetWst != null) {
            when {
                targetWst < 180 -> { spec = presetSmallCanvas(spec); reason += "+SMALL_CANVAS" }
                targetWst >= 300 -> { spec = presetLargeCanvas(spec); reason += "+LARGE_CANVAS" }
            }
        }

        // Аддоны
        val addons = mutableListOf<String>()
        spec = applyAddons(spec, addons, m, cov)

        // Нормализация под реальный фактор r
        val r = computeR(sourceWpx, targetWst)
        val sigmaBase = spec.aaPref.sigmaBase(r)
        val normalized = PresetGateResult.Normalized(
            sigmaBase = sigmaBase,
            sigmaEdge = sigmaBase * spec.aaPref.edgeScale,
            sigmaFlat = sigmaBase * spec.aaPref.flatScale
        )

        // Лог
        Logger.i("PGATE", "preset.selected", mapOf(
            "preset" to spec.id,
            "addons" to addons.joinToString(","),
            "r" to r,
            "sigma_base" to "%.3f".format(normalized.sigmaBase),
            "reason" to reason
        ))

        return PresetGateResult(
            spec = spec.copy(addons = addons.toList()),
            normalized = normalized,
            r = r,
            covers = cov,
            reason = reason
        )
    }

    private fun computeR(sourceWpx: Int, targetWst: Int?): Double {
        if (targetWst == null) return 1.25 // безопасный нижний порог AA, если не знаем сетку
        return sourceWpx.toDouble() / max(1, targetWst)
    }

    private fun coverage(m: Masks): RegionCovers =
        RegionCovers(
            edgePct = maskCoverage(m.edge),
            flatPct = maskCoverage(m.flat),
            skinPct = maskCoverage(m.skin),
            skyPct = maskCoverage(m.sky),
            hiTexFinePct = maskCoverage(m.hiTexFine),
            hiTexCoarsePct = maskCoverage(m.hiTexCoarse)
        )

    private fun maskCoverage(mask: Bitmap): Double {
        val w = mask.width; val h = mask.height
        var cnt = 0
        val row = IntArray(w)
        for (y in 0 until h) {
            mask.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                // ALPHA_8: непрозрачные пиксели → белая маска
                if ((row[x] ushr 24) != 0) cnt++
            }
        }
        return cnt.toDouble() / (w * h).toDouble()
    }
}