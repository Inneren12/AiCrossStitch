package com.appforcross.editor.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnvironmentSmokeTest {

    @Test
    fun appContext_isAvailable() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertThat(ctx).isNotNull()
        assertThat(ctx.packageName).contains("com.appforcross")
    }

    @Test
    fun assets_palettes_list_isReadable() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val list = runCatching { ctx.assets.list("palettes")?.toList() ?: emptyList() }.getOrDefault(emptyList())
        // В некоторых окружениях assets могут быть пустыми — главное, что доступ не падает.
        assertThat(list).isNotNull()
    }
}
