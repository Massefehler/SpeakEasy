package com.speakeasy.intercom

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

/**
 * Header-Wallpaper-Auswahl: 2-spaltiges Grid mit allen Bundled-Wallpapers.
 * Tap = sofort übernehmen, Live-Preview im Header beim Zurückkehren in
 * die Hauptansicht (kein App-Restart nötig).
 */
class WallpaperActivity : AppCompatActivity() {

    private lateinit var adapter: WallpaperAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        adapter = WallpaperAdapter(
            initialSelected = WallpaperRepository.current(this).id,
            onPick = ::onPick,
        )
        val grid = findViewById<RecyclerView>(R.id.wpGrid)
        grid.layoutManager = GridLayoutManager(this, 2)
        grid.adapter = adapter
    }

    private fun onPick(wallpaper: Wallpaper) {
        WallpaperRepository.setCurrent(this, wallpaper)
        adapter.setSelected(wallpaper.id)
    }
}

private class WallpaperAdapter(
    initialSelected: String,
    private val onPick: (Wallpaper) -> Unit,
) : RecyclerView.Adapter<WallpaperAdapter.WpVH>() {

    private val items: List<Wallpaper> = WallpaperRegistry.BUILT_IN
    private var selectedId: String = initialSelected

    fun setSelected(id: String) {
        val before = items.indexOfFirst { it.id == selectedId }
        val after = items.indexOfFirst { it.id == id }
        selectedId = id
        if (before >= 0) notifyItemChanged(before)
        if (after >= 0 && after != before) notifyItemChanged(after)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WpVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wallpaper, parent, false)
        return WpVH(view)
    }

    override fun onBindViewHolder(holder: WpVH, position: Int) {
        val wp = items[position]
        val ctx = holder.itemView.context
        holder.name.setText(wp.displayNameRes)
        holder.thumb.setImageResource(wp.drawableRes)
        holder.thumb.background = null
        holder.itemView.setOnClickListener { onPick(wp) }

        val isSelected = wp.id == selectedId
        holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        (holder.itemView as MaterialCardView).strokeWidth =
            if (isSelected) (3 * ctx.resources.displayMetrics.density).toInt() else 0
    }

    class WpVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumb: ImageView = itemView.findViewById(R.id.wpThumb)
        val check: ImageView = itemView.findViewById(R.id.wpCheck)
        val name: TextView = itemView.findViewById(R.id.wpName)
    }
}
