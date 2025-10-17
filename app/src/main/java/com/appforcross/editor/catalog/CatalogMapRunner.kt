package com.appforcross.editor.catalog

import android.content.Context
import android.graphics.Color
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object CatalogMapRunner {
    data class Output(
        val brand: String,
        val avgDE: Double,
        val maxDE: Double,
        val blends: Int,
        val jsonPath: String
    )

    fun run(
        ctx: Context,
        palette: IntArray,
        brand: String = "DMC",
        options: CatalogMapOptions = CatalogMapOptions()
    ): Output {
        Logger.i("CATMAP", "start", mapOf("brand" to brand, "k" to palette.size, "allowBlends" to options.allowBlends))
        val catalog = ThreadCatalogs.load(ctx, brand)
        val res = CatalogMapper.mapPaletteToCatalog(palette, catalog, options)

        // JSON: {brand, metrics, entries:[{idx, rgb, match:{type:"single"/"blend", code(s), name(s), rgb(s), dE}}]}
        val out = File(ctx.cacheDir, "palette_catalog.json")
        FileOutputStream(out).use { fos ->
            val sb = StringBuilder(4096)
            sb.append("{\"brand\":").append(jsonString(brand)).append(',')
            sb.append("\"metrics\":{")
                .append("\"avgDE\":").append(formatDecimal(res.metrics.avgDE)).append(',')
                .append("\"maxDE\":").append(formatDecimal(res.metrics.maxDE)).append(',')
                .append("\"blends\":").append(res.metrics.blendsCount)
                .append("},\"entries\":[")
            res.entries.forEachIndexed { i, e ->
                if (i>0) sb.append(',')
                sb.append("{\"idx\":").append(e.index)
                    .append(",\"rgb\":").append(jsonString(rgbHex(e.paletteRGB)))
                when (val m = e.match) {
                    is CatalogMatch.Single -> {
                        sb.append(",\"type\":\"single\",\"code\":").append(jsonString(m.color.code)).append(',')
                            .append("\"name\":").append(jsonString(m.color.name)).append(',')
                            .append("\"rgb\":").append(jsonString(rgbHex(m.color.rgb))).append(',')
                            .append("\"dE\":").append(formatDecimal(m.dE))
                    }
                    is CatalogMatch.Blend -> {
                        sb.append(",\"type\":\"blend\",\"codeA\":").append(jsonString(m.a.code)).append(',')
                            .append("\"codeB\":").append(jsonString(m.b.code)).append(',')
                            .append("\"nameA\":").append(jsonString(m.a.name)).append(',')
                            .append("\"nameB\":").append(jsonString(m.b.name)).append(',')
                            .append("\"rgbA\":").append(jsonString(rgbHex(m.a.rgb))).append(',')
                            .append("\"rgbB\":").append(jsonString(rgbHex(m.b.rgb))).append(',')
                            .append("\"dE\":").append(formatDecimal(m.dE))
                    }
                }
                sb.append('}')
            }
            sb.append("]}")
            fos.write(sb.toString().toByteArray())
        }
        // копия в diag
        try {
            DiagnosticsManager.currentSessionDir(ctx)?.let { dir ->
                out.copyTo(File(dir, out.name), overwrite = true)
            }
        } catch (_: Exception) {}
        Logger.i("CATMAP", "done", mapOf(
            "avgDE" to formatDecimal(res.metrics.avgDE),
            "maxDE" to formatDecimal(res.metrics.maxDE),
            "blends" to res.metrics.blendsCount,
            "json" to out.absolutePath
        ))
        return Output(brand, res.metrics.avgDE, res.metrics.maxDE, res.metrics.blendsCount, out.absolutePath)
    }

    private fun rgbHex(rgb: Int): String =
        "#%02X%02X%02X".format(Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    private fun jsonString(s: String?): String =
        if (s == null) "null" else "\"" + escapeJsonString(s) + "\""

    private fun formatDecimal(value: Double): String =
        String.format(Locale.US, "%.3f", value)

    private fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
