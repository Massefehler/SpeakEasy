package com.speakeasy.intercom

/**
 * Registry der verfügbaren Header-Wallpapers — alle Bundled, alle im
 * 14:10-Format (1484×1060) als WebP unter `res/drawable-nodpi/wp_*.webp`,
 * Hauptmotiv (Motorrad) in der rechten Bildhälfte. MainActivity hält den
 * Header-Container ebenfalls auf 14:10, sodass das Bild via `centerCrop`
 * vollständig und unverzerrt sichtbar bleibt.
 *
 * Custom-Fotos vom Handy sind aktuell nicht implementiert (Aspect-Mismatch
 * ohne Crop-Tool unbefriedigend). Wiedereinführung wäre ein separater
 * Bauauftrag.
 */
data class Wallpaper(
    val id: String,
    val displayNameRes: Int,
    val drawableRes: Int,
)

object WallpaperRegistry {
    const val ID_DEFAULT = "default"

    val BUILT_IN: List<Wallpaper> = listOf(
        Wallpaper(ID_DEFAULT, R.string.wallpaper_default,  R.drawable.header_bg),
        Wallpaper("biker_1",  R.string.wallpaper_biker_1,  R.drawable.wp_biker_1),
        Wallpaper("biker_2",  R.string.wallpaper_biker_2,  R.drawable.wp_biker_2),
        Wallpaper("cross_1",  R.string.wallpaper_cross_1,  R.drawable.wp_cross_1),
        Wallpaper("naked",    R.string.wallpaper_naked,    R.drawable.wp_naked),
        Wallpaper("riders_1", R.string.wallpaper_riders_1, R.drawable.wp_riders_1),
        Wallpaper("riders_2", R.string.wallpaper_riders_2, R.drawable.wp_riders_2),
        Wallpaper("speed_1",  R.string.wallpaper_speed_1,  R.drawable.wp_speed_1),
        Wallpaper("speed_2",  R.string.wallpaper_speed_2,  R.drawable.wp_speed_2),
        Wallpaper("travel_1", R.string.wallpaper_travel_1, R.drawable.wp_travel_1),
        Wallpaper("travel_2", R.string.wallpaper_travel_2, R.drawable.wp_travel_2),
        Wallpaper("tunnel_1", R.string.wallpaper_tunnel_1, R.drawable.wp_tunnel_1),
    )

    fun byId(id: String?): Wallpaper =
        BUILT_IN.firstOrNull { it.id == id } ?: BUILT_IN.first()
}
