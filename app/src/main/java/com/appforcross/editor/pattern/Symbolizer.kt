// app/src/main/java/com/appforcross/editor/pattern/Symbolizer.kt
package com.appforcross.editor.pattern

data class LegendEntry(
    val idx: Int,
    val code: String?,   // DMC код, если есть
    val name: String?,
    val rgb: Int,
    val symbol: String   // базовый символ авто-символизации
)

data class PatternLegend(
    val entries: List<LegendEntry>,
    val overrides: Map<Int, String> = emptyMap()
) {
    /** Символ с учётом override */
    fun symbolFor(idx: Int): String =
        overrides[idx] ?: entries.firstOrNull { it.idx == idx }?.symbol ?: "?"

    /** Эффективные пары idx->symbol (уникальные) */
    fun effectiveSymbols(): Map<Int, String> =
        entries.associate { it.idx to symbolFor(it.idx) }
}

// здесь уже есть ваши data-классы LegendEntry / PatternLegend (не трогаем)
object PatternSymbols {

    /** Безопасный пул символов для Roboto/Compose */
    val DEFAULT_SYMBOLS: List<String> = listOf(
        "■","●","▲","◆","○","□","△","◇","✚","✖","+","x","★","☆","◼","◻",
        "◉","◊","▣","▢","•","↑","↓","←","→","≡","≈","Ø","Δ","Ω",
        "1","2","3","4","5","6","7","8","9","0",
        "A","B","C","D","E","F","G","H","I","J","K","L","M",
        "N","O","P","Q","R","S","T","U","V","W","X","Y","Z"
    )

    /**
     * Гарантирует уникальность символов. При конфликте берёт первый свободный из [pool].
     */
    fun normalizeUnique(legend: PatternLegend, pool: List<String> = DEFAULT_SYMBOLS): PatternLegend {
        val used = mutableSetOf<String>()
        val baseByIdx = legend.entries.associate { it.idx to it.symbol }
        val newOverrides = mutableMapOf<Int, String>()

        fun nextFree(): String = pool.firstOrNull { it !in used } ?: "?"

        legend.entries.forEach { e ->
            val want = legend.overrides[e.idx] ?: e.symbol
            val chosen = if (want in used) nextFree() else want
            used += chosen
            if (chosen != baseByIdx[e.idx]) newOverrides[e.idx] = chosen
        }
        return legend.copy(overrides = newOverrides)
    }

    /** Эффективная карта idx→symbol с учётом overrides. */
    fun effectiveSymbols(legend: PatternLegend): Map<Int, String> =
        legend.entries.associate { it.idx to (legend.overrides[it.idx] ?: it.symbol) }
}
