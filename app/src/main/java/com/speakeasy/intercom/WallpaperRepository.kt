package com.speakeasy.intercom

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Liefert das aktive Header-Wallpaper aus den Settings. Nur Bundled-
 * Wallpapers — eine Custom-Foto-Funktion ist aktuell nicht implementiert.
 *
 * Pref: `wallpaper_id` (String, Default „default")
 */
object WallpaperRepository {

    const val KEY_WALLPAPER_ID = "wallpaper_id"

    fun current(context: Context): Wallpaper {
        val id = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_WALLPAPER_ID, WallpaperRegistry.ID_DEFAULT)
        return WallpaperRegistry.byId(id)
    }

    fun setCurrent(context: Context, wallpaper: Wallpaper) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(KEY_WALLPAPER_ID, wallpaper.id)
            .apply()
    }

    fun loadDrawable(context: Context, wallpaper: Wallpaper): Drawable? =
        ContextCompat.getDrawable(context, wallpaper.drawableRes)
}
