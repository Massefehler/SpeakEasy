package com.speakeasy.intercom

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Verwaltung der gespeicherten Peer-Profile (max. 8). Liste mit Tap=Umbenennen,
 * Trash=Löschen, plus „Alle löschen"-Button am Ende. Long-Press auf der
 * Hauptscreen-Schnellverbindungs-Karte bleibt parallel funktional.
 */
class ProfilesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.deleteAllBtn).setOnClickListener { onDeleteAllClicked() }
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        val list = findViewById<LinearLayout>(R.id.profileList)
        val card = findViewById<View>(R.id.profilesCard)
        val empty = findViewById<TextView>(R.id.profilesEmpty)
        val deleteAll = findViewById<MaterialButton>(R.id.deleteAllBtn)

        list.removeAllViews()
        val profiles = ProfileStore.loadAll(this)
        if (profiles.isEmpty()) {
            card.visibility = View.GONE
            empty.visibility = View.VISIBLE
            deleteAll.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        empty.visibility = View.GONE
        deleteAll.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(this)
        profiles.forEachIndexed { idx, profile ->
            val row = inflater.inflate(R.layout.item_profile_row, list, false)
            row.findViewById<TextView>(R.id.profileLabel).text = profile.label
            row.findViewById<TextView>(R.id.profileMac).text = profile.mac
            row.findViewById<View>(R.id.profileEdit).setOnClickListener { rename(profile) }
            row.findViewById<View>(R.id.profileDelete).setOnClickListener { confirmDelete(profile) }
            list.addView(row)
            // Trenner zwischen den Reihen, optisch leichter — letzte Reihe ohne.
            if (idx < profiles.lastIndex) {
                val divider = View(this).apply {
                    val px1dp = (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        px1dp,
                    ).apply {
                        marginStart = (16 * resources.displayMetrics.density).toInt()
                        marginEnd = marginStart
                    }
                    setBackgroundColor(0x22000000)
                }
                list.addView(divider)
            }
        }
    }

    private fun rename(profile: Profile) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(profile.label)
            setSelection(profile.label.length)
            hint = getString(R.string.profile_rename_hint)
        }
        val dp = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            setPadding(dp, dp / 2, dp, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profile_rename_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newLabel = input.text.toString().trim().ifEmpty { profile.label }
                ProfileStore.rename(this, profile.mac, newLabel)
                renderList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.profile_actions_title, profile.label))
            .setMessage(R.string.profiles_delete_confirm)
            .setPositiveButton(R.string.profile_action_delete) { _, _ ->
                ProfileStore.delete(this, profile.mac)
                renderList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onDeleteAllClicked() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.profiles_delete_all)
            .setMessage(R.string.profiles_delete_all_confirm)
            .setPositiveButton(R.string.profile_action_delete) { _, _ ->
                ProfileStore.loadAll(this).forEach { ProfileStore.delete(this, it.mac) }
                renderList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
