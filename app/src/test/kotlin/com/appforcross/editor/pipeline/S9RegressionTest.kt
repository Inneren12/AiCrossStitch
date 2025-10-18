package com.appforcross.editor.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S9RegressionTest {
    @Test
    fun checkerboard_S7_to_S9_hasNonTrivialPatternIndex() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(ctx.cacheDir, "test_s9_cb").apply { mkdirs() }
        val src = File(dir, "src.png")
        val bm = TestImages.genChecker(240, 136, 6)
        TestImages.savePng(bm, src)
        bm.recycle()
        val s7 = Pipeline.runS7(ctx, src, dir)
        assertThat(File(s7.colorPng).exists()).isTrue()
        assertThat(File(s7.indexBin).exists()).isTrue()
        val idx7 = IndexIo.readIndexBin(s7.indexBin, s7.w, s7.h)
        val st7 = IndexIo.stats(idx7)
        assertThat(st7.unique).isAtLeast(2)
        assertThat(st7.min).isAtLeast(0)
        assertThat(st7.max).isLessThan(s7.palette.size)
        val s9 = Pipeline.runS9(ctx, s7.colorPng, s7.indexBin, s7.palette)
        assertThat(File(s9.patternIndex).exists()).isTrue()
        assertThat(File(s9.previewPng).exists()).isTrue()
        assertThat(File(s9.legendJson).exists()).isTrue()
        val idx9 = IndexIo.readIndexBin(s9.patternIndex, s7.w, s7.h)
        val st9 = IndexIo.stats(idx9)
        assertThat(st9.unique).isAtLeast(2)
        assertThat(st9.min).isAtLeast(0)
    }
}
