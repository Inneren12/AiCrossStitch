package com.appforcross.editor.ui.import

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.appforcross.editor.ui.tabs.ImportTab

/** Отдельная активити для интеграции Import‑экрана (на случай, если таблоид ещё не подключён). */
class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    ImportTab()
                }
            }
        }
    }
}