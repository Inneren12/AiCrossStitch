package com.appforcross.editor.dev

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.MutablePreferences

private const val DS_NAME = "dev_prefs"
private val Context.dataStore by preferencesDataStore(name = DS_NAME)

object DevPrefs {

    private object Keys {
        val DEBUG = booleanPreferencesKey("dev.debug")
    }

    fun isDebug(ctx: Context): Flow<Boolean> =
        ctx.dataStore.data.map { prefs: Preferences ->
            prefs[Keys.DEBUG] ?: false
        }

    suspend fun setDebug(ctx: Context, value: Boolean) {
        ctx.dataStore.edit { prefs: MutablePreferences ->
            prefs[Keys.DEBUG] = value
        }
    }
}