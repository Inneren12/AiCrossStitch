package com.appforcross.editor.pattern

/** Простой назначатель символов. При нехватке — падение на латиницу/цифры. */
object Symbolizer {
    private val base: List<String> = listOf(
        "●","○","■","□","▲","△","◆","◇","✚","✖","★","☆","▣","▢","▧","▨",
        "♠","♣","♦","♤","♧","♢","⬤","◯","◼","◻","◉","◎","◍","◈","◩","◪"
    )
    private val letters: List<String> = buildList {
        ('A'..'Z').forEach { add(it.toString()) }
        ('a'..'z').forEach { add(it.toString()) }
        ('0'..'9').forEach { add(it.toString()) }
    }

    fun assign(k: Int): List<String> {
        if (k <= 0) return emptyList()

        val out = ArrayList<String>(k)
        var p = 0
        // сначала символы из набора
        while (p < k && p < base.size) {
            out += base[p]
            p++
        }
        // затем латиница+цифры
        var q = 0
        while (p < k && q < letters.size) {
            out += letters[q]
            p++
            q++
        }
        if (p >= k) return out

        // если всё ещё не хватило — повторяем с апострофами (", "''", ...)
        val pool = base + letters
        var r = 0
        while (p < k) {
            val baseIndex = r % pool.size
            val primeCount = r / pool.size + 1
            val suffix = "'".repeat(primeCount)
            out += pool[baseIndex] + suffix
            p++
            r++
        }
        return out
    }
}