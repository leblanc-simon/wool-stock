package io.leblanc.woolstock.platform

import android.content.Context
import androidx.core.content.edit

/** Réglages dans les SharedPreferences « settings », les mêmes que les versions précédentes de l'app. */
internal class SharedPreferencesStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }

    override fun remove(key: String) = prefs.edit { remove(key) }
}
