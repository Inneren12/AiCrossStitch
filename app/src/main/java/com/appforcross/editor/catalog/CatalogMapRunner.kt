package com.appforcross.editor.catalog

import android.content.Context
import android.graphics.Color
import com.appforcross.editor.diagnostics.DiagnosticsManager
import com.appforcross.editor.logging.Logger
import java.io.File
import java.io.FileOutputStream

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
            sb.append("{\"brand\":\"").append(brand).append("\",")
            sb.append("\"metrics\":{")
                .append("\"avgDE\":").append("%.3f".format(res.metrics.avgDE)).append(',')
                .append("\"maxDE\":").append("%.3f".format(res.metrics.maxDE)).append(',')
                .append("\"blends\":").append(res.metrics.blendsCount)
                .append("},\"entries\":[")
            res.entries.forEachIndexed { i, e ->
                if (i>0) sb.append(',')
                sb.append("{\"idx\":").append(e.index)
                    .append(",\"rgb\":\"").append(rgbHex(e.paletteRGB)).append("\"")
                when (val m = e.match) {
                    is CatalogMatch.Single -> {
                        sb.append(",\"type\":\"single\",\"code\":\"").append(m.color.code).append("\",")
                            .append("\"name\":").append(jsonString(m.color.name)).append(',')
                            .append("\"rgb\":\"").append(rgbHex(m.color.rgb)).append("\",")
                            .append("\"dE\":").append("%.3f".format(m.dE))
                    }
                    is CatalogMatch.Blend -> {
                        sb.append(",\"type\":\"blend\",\"codeA\":\"").append(m.a.code).append("\",")
                            .append("\"codeB\":\"").append(m.b.code).append("\",")
                            .append("\"nameA\":").append(jsonString(m.a.name)).append(',')
                            .append("\"nameB\":").append(jsonString(m.b.name)).append(',')
                            .append("\"rgbA\":\"").append(rgbHex(m.a.rgb)).append("\",")
                            .append("\"rgbB\":\"").append(rgbHex(m.b.rgb)).append("\",")
                            .append("\"dE\":").append("%.3f".format(m.dE))
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
            "avgDE" to "%.3f".format(res.metrics.avgDE),
            "maxDE" to "%.3f".format(res.metrics.maxDE),
            "blends" to res.metrics.blendsCount,
            "json" to out.absolutePath
        ))
        return Output(brand, res.metrics.avgDE, res.metrics.maxDE, res.metrics.blendsCount, out.absolutePath)
    }

    private fun rgbHex(rgb: Int): String =
        "#%02X%02X%02X".format(Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    private fun jsonString(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\"","\\\"") + "\""
    }