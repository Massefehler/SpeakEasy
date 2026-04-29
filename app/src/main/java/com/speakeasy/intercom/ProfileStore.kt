package com.speakeasy.intercom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistente, geordnete Liste bekannter SpeakEasy-Peers.
 *
 * Speicherung als JSON in SharedPreferences (kein extra DB-Abhängigkeit nötig).
 * Reihenfolge = zuletzt verbunden zuerst.
 */
data class Profile(val mac: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_MAC, mac)
        put(KEY_LABEL, label)
    }

    companion object {
        const val KEY_MAC = "mac"
        const val KEY_LABEL = "label"

        fun fromJson(o: JSONObject): Profile? {
            val mac = o.optString(KEY_MAC).takeIf { it.isNotEmpty() } ?: return null
            val label = o.optString(KEY_LABEL).takeIf { it.isNotEmpty() } ?: mac
            return Profile(mac, label)
        }
    }
}

object ProfileStore {
    private const val PREFS = "speakeasy"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_LEGACY_MAC = "last_peer_mac"
    private const val KEY_LEGACY_NAME = "last_peer_name"
    private const val MAX_PROFILES = 8

    fun loadAll(context: Context): List<Profile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw != null) {
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { Profile.fromJson(arr.getJSONObject(it)) }
            }.getOrElse { emptyList() }
        }
        // Legacy-Migration: alter Single-Peer-Eintrag wird zur Profilliste mit einem Element.
        val legacyMac = prefs.getString(KEY_LEGACY_MAC, null)
        if (legacyMac != null) {
            val legacyName = prefs.getString(KEY_LEGACY_NAME, null) ?: legacyMac
            val migrated = listOf(Profile(legacyMac, legacyName))
            saveAll(context, migrated)
            return migrated
        }
        return emptyList()
    }

    /** Fügt einen Peer hinzu oder schiebt ihn an Position 0; ersetzt einen vorhandenen Eintrag. */
    fun promote(context: Context, mac: String, label: String): List<Profile> {
        val current = loadAll(context).toMutableList()
        val existing = current.indexOfFirst { it.mac.equals(mac, ignoreCase = true) }
        val updated = if (existing >= 0) {
            current.removeAt(existing).copy(label = label)
        } else Profile(mac, label)
        current.add(0, updated)
        val capped = current.take(MAX_PROFILES)
        saveAll(context, capped)
        return capped
    }

    fun rename(context: Context, mac: String, newLabel: String): List<Profile> {
        val updated = loadAll(context).map {
            if (it.mac.equals(mac, ignoreCase = true)) it.copy(label = newLabel) else it
        }
        saveAll(context, updated)
        return updated
    }

    fun delete(context: Context, mac: String): List<Profile> {
        val updated = loadAll(context).filterNot { it.mac.equals(mac, ignoreCase = true) }
        saveAll(context, updated)
        return updated
    }

    private fun saveAll(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILES, arr.toString())
            .apply()
    }
}
