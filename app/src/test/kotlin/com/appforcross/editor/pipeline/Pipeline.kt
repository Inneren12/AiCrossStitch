package com.appforcross.editor.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.appforcross.editor.analysis.AnalyzeResult
import com.appforcross.editor.analysis.Masks
import com.appforcross.editor.analysis.Metrics
import com.appforcross.editor.analysis.SceneDecision
import com.appforcross.editor.analysis.SceneKind
import com.appforcross.editor.preset.PresetGateResult
import com.appforcross.editor.preset.PresetSpec
import com.appforcross.editor.preset.QualityTier
import com.appforcross.editor.preset.RegionCovers
import com.appforcross.editor.preset.ScaleFilter
import java.io.File

object Pipeline {
    data class S7Out(
        val colorPng: String,
        val indexBin: String,
        val palette: IntArray,
        val w: Int,
        val h: Int
    )

    data class S9Out(
        val patternIndex: String,
        val previewPng: String,
        val legendJson: String
    )

    fun runS7(ctx: Context, srcPng: File, outDir: File): S7Out {
        val cls = Class.forName("com.appforcross.editor.quant.QuantizeRunner")
        val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
        val inst = ctor.newInstance()
        val method = (cls.methods + cls.declaredMethods)
            .first { it.name in listOf("run", "execute") }
            .apply { isAccessible = true }
        val preview = BitmapFactory.decodeFile(srcPng.absolutePath)
            ?: error("Cannot decode source image: ${srcPng.absolutePath}")
        val analyze = createAnalyzeStub(preview)
        val gate = createPresetGateStub()
        val quantOptions = createQuantOptions()
        val result = try {
            when (method.parameterCount) {
                5 -> method.invoke(
                    inst,
                    ctx,
                    srcPng.absolutePath,
                    analyze,
                    gate,
                    quantOptions ?: error("QuantizeRunner.Options unavailable")
                )
                4 -> method.invoke(inst, ctx, srcPng.absolutePath, analyze, gate)
                3 -> method.invoke(inst, ctx, srcPng.absolutePath, outDir.absolutePath)
                2 -> method.invoke(inst, ctx, srcPng.absolutePath)
                else -> error("Unexpected QuantizeRunner.run signature")
            }
        } finally {
            recycleAnalyzeStub(analyze)
        }
        fun <T> prop(name: String): T = readProperty(result, name)
        val colorPng: String = prop("colorPng")
        val indexBin: String = prop("indexBin")
        val palette: IntArray = prop("palette")
        val bitmap = BitmapFactory.decodeFile(colorPng)
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()
        return S7Out(colorPng, indexBin, palette, w, h)
    }

    fun runS9(ctx: Context, colorPng: String, indexBin: String, palette: IntArray): S9Out {
        val cls = Class.forName("com.appforcross.editor.pattern.PatternRunner")
        val ctor = cls.getDeclaredConstructor().apply { isAccessible = true }
        val inst = ctor.newInstance()
        val method = (cls.methods + cls.declaredMethods)
            .first { it.name in listOf("run", "execute", "build") }
            .apply { isAccessible = true }
        val params = method.parameterTypes
        val args = arrayOfNulls<Any>(method.parameterCount)
        args[0] = ctx
        if (params.size >= 4 && params[1] == IntArray::class.java) {
            args[1] = palette
            args[2] = indexBin
            args[3] = colorPng
            if (args.size >= 5) {
                args[4] = null
            }
            if (args.size >= 6) {
                args[5] = createPatternOptions()
            }
        } else {
            args[1] = colorPng
            args[2] = indexBin
            args[3] = palette
            if (args.size >= 5) {
                args[4] = null
            }
            if (args.size >= 6) {
                args[5] = null
            }
        }
        val result = when (args.size) {
            4 -> method.invoke(inst, args[0], args[1], args[2], args[3])
            5 -> method.invoke(inst, args[0], args[1], args[2], args[3], args[4])
            6 -> method.invoke(inst, args[0], args[1], args[2], args[3], args[4], args[5])
            else -> error("Unexpected PatternRunner.run signature")
        }
        fun <T> prop(name: String): T = readProperty(result, name)
        val patternIndex: String = prop("indexBin")
        val previewPng: String = prop("previewPng")
        val legendJson: String = prop("legendJson")
        return S9Out(patternIndex, previewPng, legendJson)
    }
}

private fun <T> readProperty(instance: Any, name: String): T {
    val field = instance.javaClass.getDeclaredField(name)
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(instance) as T
}

private fun createQuantOptions(): Any? = try {
    val cls = Class.forName("com.appforcross.editor.quant.QuantizeRunner\$Options")
    cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
} catch (_: Throwable) {
    null
}

private fun createAnalyzeStub(preview: Bitmap): AnalyzeResult {
    fun mask(): Bitmap = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ALPHA_8)
    val masks = Masks(mask(), mask(), mask(), mask(), mask(), mask())
    val metrics = Metrics(
        width = preview.width,
        height = preview.height,
        lMed = 0.0,
        drP99minusP1 = 0.0,
        satLoPct = 0.0,
        satHiPct = 0.0,
        castOK = 0.0,
        noiseY = 0.0,
        noiseC = 0.0,
        edgeRate = 0.0,
        varLap = 0.0,
        hazeScore = 0.0,
        flatPct = 0.0,
        gradP95Sky = 0.0,
        gradP95Skin = 0.0,
        colors5bit = 0,
        top8Coverage = 0.0,
        checker2x2 = 0.0
    )
    val decision = SceneDecision(SceneKind.PHOTO, subtype = null, confidence = 1.0)
    return AnalyzeResult(
        preview = preview,
        metrics = metrics,
        masks = masks,
        decision = decision,
        sourceWidth = preview.width,
        sourceHeight = preview.height
    )
}

private fun recycleAnalyzeStub(analyze: AnalyzeResult) {
    analyze.preview.recycle()
    analyze.masks.edge.recycle()
    analyze.masks.flat.recycle()
    analyze.masks.hiTexFine.recycle()
    analyze.masks.hiTexCoarse.recycle()
    analyze.masks.skin.recycle()
    analyze.masks.sky.recycle()
}

private fun createPresetGateStub(): PresetGateResult {
    val spec = PresetSpec(
        id = "Test/Stub",
        addons = emptyList(),
        color = PresetSpec.ColorParams(wbStrength = 0.5, gammaTarget = 0.48, rolloff = 0.1),
        nr = PresetSpec.NRParams(lumaRadius = 3, lumaEps = 0.003, chromaGain = 1.0),
        texture = PresetSpec.TextureParams(smoothFlat = 0.2, smoothNonFlat = 0.1),
        unify = PresetSpec.UnifyParams(skinSatDelta = 0.0, skinToneSmooth = 0.2, skyHueShiftToMode = 0.0, skyVdelta = 0.0),
        edges = PresetSpec.EdgeParams(protectGain = 0.8, preSharpenAmount = 0.3, preSharpenRadius = 1.0, preSharpenThreshold = 3),
        aaPref = PresetSpec.AAPref(kSigma = 0.5, edgeScale = 1.0, flatScale = 1.0),
        scale = PresetSpec.ScaleParams(filter = ScaleFilter.EWA_Mitchell, microPhaseTrials = 1),
        verify = PresetSpec.VerifyParams(ssimMin = 0.98, edgeKeepMin = 0.98, bandingMax = 0.01, de95Max = 3.0),
        post = PresetSpec.PostParams(dering = true, localContrast = true, claheClip = 1.5),
        quant = PresetSpec.QuantHints(ditherAmpL = 0.4, ditherMask = "Flat|Sky|Skin", paletteBias = "neutral"),
        quality = PresetSpec.QualityParams(tier = QualityTier.BALANCED, bitdepthInternal = "16F")
    )
    val normalized = PresetGateResult.Normalized(
        sigmaBase = spec.aaPref.sigmaBase(1.0),
        sigmaEdge = 1.0,
        sigmaFlat = 1.0
    )
    val covers = RegionCovers(
        edgePct = 0.0,
        flatPct = 0.0,
        skinPct = 0.0,
        skyPct = 0.0,
        hiTexFinePct = 0.0,
        hiTexCoarsePct = 0.0
    )
    return PresetGateResult(
        spec = spec,
        normalized = normalized,
        r = 1.0,
        covers = covers,
        reason = "test"
    )
}

private fun createPatternOptions(): Any? = try {
    val cls = Class.forName("com.appforcross.editor.pattern.PatternRunner\$Options")
    val ctor = cls.getDeclaredConstructor(
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Float::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType
    ).apply { isAccessible = true }
    ctor.newInstance(1, 1, 1, 0f, 1024, true)
} catch (_: Throwable) {
    null
}
