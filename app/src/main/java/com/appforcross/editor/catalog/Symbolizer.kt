package com.appforcross.editor.pattern

/** Простой назначатель символов. При нехватке — падение на латиницу/цифры. */
object Symbolizer {
    private val base: CharArray = charArrayOf(
        '●','○','■','□','▲','△','◆','◇','✚','✖','★','☆','▣','▢','▧','▨',
        '♠','♣','♦','♤','♧','♢','⬤','◯','◼','◻','◉','◎','◍','◈','◩','◪'
    )
    private val letters: CharArray = (
            ('A'..'Z').joinToString("") +
                    ('a'..'z').joinToString("") +
                    ('0'..'9').joinToString("")
            ).toCharArray()

    fun assign(k: Int): CharArray {
        val out = CharArray(k)
        var p = 0
        // сначала символы из набора
        while (p < k && p < base.size) {
            out[p] = base[p]; p++
        }
        // затем латиница+цифры
        var q = 0
        while (p < k && q < letters.size) {
            out[p] = letters[q]; p++; q++
        }
        // если всё ещё не хватило — повторяем с апострофом
        var r = 0
        while (p < k) {
            val c = if (r < base.size) base[r] else letters[(r - base.size) % letters.size]
            out[p] = c
            p++; r++
        }
        return out
    }
}