package com.speakeasy.intercom

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Frühe Anwendungs-Initialisierung: setzt das Theme aus den Preferences,
 * bevor die erste Activity erzeugt wird. Die Sprache verwaltet AppCompat
 * automatisch via setApplicationLocales (im Speicher persistiert) – die
 * SettingsActivity ruft [LocaleHelper.apply] direkt bei Auswahl auf.
 */
class SpeakEasyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Crash-Handler so früh wie möglich, damit auch Init-Fehler in
        // Application/Activity-Construction protokolliert werden.
        CrashHandler.install(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.getString(IntercomService.KEY_THEME, "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        // Aufräumen alter Custom-Foto-Prefs aus beta6–10 (Custom-Fotos im Header
        // funktionierten wegen 21:9-Mismatch nicht). Falls die alte Datei oder
        // Pref-Werte noch da sind, einmalig wegräumen — Wiedereinführung
        // (z. B. als Pro-Feature mit Crop-Tool) wäre ein eigener Bauauftrag.
        cleanupLegacyCustomPhoto(prefs)
    }

    private fun cleanupLegacyCustomPhoto(prefs: android.content.SharedPreferences) {
        val edit = prefs.edit()
        if (prefs.getString(WallpaperRepository.KEY_WALLPAPER_ID, null) == "custom") {
            edit.putString(WallpaperRepository.KEY_WALLPAPER_ID, WallpaperRegistry.ID_DEFAULT)
        }
        edit.remove("wallpaper_fullscreen")
        edit.remove("background_photo_active")
        edit.apply()
        java.io.File(filesDir, "wallpaper_custom.webp").delete()
    }
}
