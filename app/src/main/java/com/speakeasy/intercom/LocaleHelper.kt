package com.speakeasy.intercom

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Per-App-Locale via [AppCompatDelegate.setApplicationLocales].
 *
 * `tag = "system"` → leere Liste, App folgt der System-Sprache.
 * Sonst BCP-47-Tag (z. B. "de", "en", "es", "it", "fr").
 */
object LocaleHelper {

    fun apply(tag: String?) {
        val locales = if (tag.isNullOrBlank() || tag == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
