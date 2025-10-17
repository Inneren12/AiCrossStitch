package com.appforcross.editor.util

import java.nio.ShortBuffer

/**
 * Пул полуплавающих (half, 16-bit) буферов на поток.
 *
 * Цели:
 *  - избежать частых огромных выделений ShortArray(width*height*4)
 *  - иметь возможность "подрезать" буфер после вызова, чтобы долгоживущие потоки
 *    не удерживали пик 50+ Мп бесконечно.
 */
object HalfBufferPool {
    /** Порог авто-тримминга: всё, что больше этого объёма, сбрасывается после вызова. */
    const val TRIM_THRESHOLD_BYTES: Long = 64L * 1024L * 1024L

    private val local = object : ThreadLocal<ShortArray>() {
        override fun initialValue(): ShortArray = ShortArray(0)
    }

    /** Гарантирует буфер не меньше [required] шортов. Возвращает ссылку на пул. */
    @JvmOverloads
    fun obtain(required: Int, clear: Boolean = false): ShortArray {
        var buf = local.get()
        if (buf.size < required) {
            // Рост только вверх (до подрезки). Копию не делаем — новый массив.
            buf = ShortArray(required)
            local.set(buf)
        }
        if (clear) {
            java.util.Arrays.fill(buf, 0.toShort())
        }
        return buf
    }

    /** Оборачивает текущий буфер в ShortBuffer с лимитом [length]. */
    fun wrap(length: Int): ShortBuffer {
        val arr = obtain(length)
        return ShortBuffer.wrap(arr, 0, length)
    }

    /**
     * Сбросить буфер, если он стал слишком большим (например, после одного 50+ Мп).
     * Вызывать после завершения тяжёлого шага.
     */
    fun trimIfOversized() {
        val arr = local.get()
        val bytes = arr.size.toLong() * 2L
        if (bytes > TRIM_THRESHOLD_BYTES) {
            local.set(ShortArray(0))
        }
    }
    /** Явно «освободить» буфер потока (например, в onDestroy/cleanup). */
    fun release() {
        local.set(ShortArray(0))
    }
}