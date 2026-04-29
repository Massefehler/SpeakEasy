package com.speakeasy.intercom

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * Eigenständige Settings-Activity mit dem gleichen Card-Look wie der Hauptscreen.
 * Persistiert in den Default-SharedPreferences (`<package>_preferences`), damit
 * IntercomService dieselben Werte über `PreferenceManager.getDefaultSharedPreferences()`
 * lesen kann.
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        wireAudioSection()
        wireConnectionSection()
        wireUiSection()
        wireAboutSection()
    }

    override fun onResume() {
        super.onResume()
        // Wallpaper-Wert aktualisieren, falls der User in der WallpaperActivity
        // gerade eine neue Auswahl getroffen hat.
        val wallpaperValue = findViewById<TextView?>(R.id.wallpaperValue)
        wallpaperValue?.text = getString(WallpaperRepository.current(this).displayNameRes)
    }

    // -- Audio ---------------------------------------------------------------

    private fun wireAudioSection() {
        val codecValue = findViewById<TextView>(R.id.codecValue)
        codecValue.text = codecLabel(prefs.getString(IntercomService.KEY_CODEC, "opus") ?: "opus")
        findViewById<View>(R.id.rowCodec).setOnClickListener {
            val current = prefs.getString(IntercomService.KEY_CODEC, "opus") ?: "opus"
            singleChoiceDialog(
                titleRes = R.string.prefs_codec_title,
                entriesArrayRes = R.array.codec_entries,
                valuesArrayRes = R.array.codec_values,
                selectedValue = current,
            ) { picked ->
                prefs.edit().putString(IntercomService.KEY_CODEC, picked).apply()
                codecValue.text = codecLabel(picked)
            }
        }

        val bitrateSlider = findViewById<Slider>(R.id.bitrateSlider)
        val bitrateValue = findViewById<TextView>(R.id.bitrateValue)
        val initBitrate = prefs.getInt(IntercomService.KEY_OPUS_BITRATE_KBPS, 24)
        bitrateSlider.value = initBitrate.toFloat().coerceIn(12f, 48f)
        bitrateValue.text = getString(R.string.prefs_bitrate_value, initBitrate)
        bitrateSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            bitrateValue.text = getString(R.string.prefs_bitrate_value, v)
            prefs.edit().putInt(IntercomService.KEY_OPUS_BITRATE_KBPS, v).apply()
        }

        val fecSlider = findViewById<Slider>(R.id.fecSlider)
        val fecValue = findViewById<TextView>(R.id.fecValue)
        val initFec = prefs.getInt(IntercomService.KEY_OPUS_FEC_LOSS, 10)
        fecSlider.value = initFec.toFloat().coerceIn(0f, 30f)
        fecValue.text = getString(R.string.prefs_fec_value, initFec)
        fecSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            fecValue.text = getString(R.string.prefs_fec_value, v)
            prefs.edit().putInt(IntercomService.KEY_OPUS_FEC_LOSS, v).apply()
        }

        val agcSwitch = findViewById<MaterialSwitch>(R.id.agcSwitch)
        agcSwitch.isChecked = prefs.getBoolean(IntercomService.KEY_AGC_ENABLED, true)
        agcSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(IntercomService.KEY_AGC_ENABLED, checked).apply()
        }

        val adaptiveSwitch = findViewById<MaterialSwitch>(R.id.adaptiveBitrateSwitch)
        adaptiveSwitch.isChecked = prefs.getBoolean(IntercomService.KEY_ADAPTIVE_BITRATE, true)
        adaptiveSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(IntercomService.KEY_ADAPTIVE_BITRATE, checked).apply()
        }

        findViewById<View>(R.id.rowMicTest).setOnClickListener {
            startActivity(Intent(this, MicTestActivity::class.java))
        }
    }

    private fun codecLabel(value: String): String = when (value) {
        "mulaw" -> getString(R.string.codec_mulaw)
        "pcm" -> getString(R.string.codec_pcm)
        else -> getString(R.string.codec_opus)
    }

    // -- Verbindung ----------------------------------------------------------

    private fun wireConnectionSection() {
        val autoConnect = findViewById<MaterialSwitch>(R.id.autoConnectSwitch)
        autoConnect.isChecked = prefs.getBoolean(IntercomService.KEY_AUTO_CONNECT, false)
        autoConnect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(IntercomService.KEY_AUTO_CONNECT, checked).apply()
        }

        val hbSlider = findViewById<Slider>(R.id.heartbeatSlider)
        val hbValue = findViewById<TextView>(R.id.heartbeatValue)
        val initHb = prefs.getInt(IntercomService.KEY_HEARTBEAT_SECONDS, 4)
        hbSlider.value = initHb.toFloat().coerceIn(2f, 10f)
        hbValue.text = getString(R.string.prefs_heartbeat_value, initHb)
        hbSlider.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            hbValue.text = getString(R.string.prefs_heartbeat_value, v)
            prefs.edit().putInt(IntercomService.KEY_HEARTBEAT_SECONDS, v).apply()
        }

        findViewById<View>(R.id.rowBtPair).setOnClickListener {
            // Direkter Sprung in die Bluetooth-Einstellungen – spart dem User mehrere
            // Tippgriffe gegenüber dem manuellen Weg über die System-Settings.
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    // -- Bedienung -----------------------------------------------------------

    private fun wireUiSection() {
        val languageValue = findViewById<TextView>(R.id.languageValue)
        languageValue.text = languageLabel(prefs.getString(IntercomService.KEY_LANGUAGE, "system") ?: "system")
        findViewById<View>(R.id.rowLanguage).setOnClickListener {
            val current = prefs.getString(IntercomService.KEY_LANGUAGE, "system") ?: "system"
            singleChoiceDialog(
                titleRes = R.string.prefs_language_title,
                entriesArrayRes = R.array.language_entries,
                valuesArrayRes = R.array.language_values,
                selectedValue = current,
            ) { picked ->
                prefs.edit().putString(IntercomService.KEY_LANGUAGE, picked).apply()
                languageValue.text = languageLabel(picked)
                LocaleHelper.apply(picked)
            }
        }

        val wallpaperValue = findViewById<TextView>(R.id.wallpaperValue)
        wallpaperValue.text = getString(WallpaperRepository.current(this).displayNameRes)
        findViewById<View>(R.id.rowWallpaper).setOnClickListener {
            startActivity(Intent(this, WallpaperActivity::class.java))
        }

        val themeValue = findViewById<TextView>(R.id.themeValue)
        themeValue.text = themeLabel(prefs.getString(IntercomService.KEY_THEME, "system") ?: "system")
        findViewById<View>(R.id.rowTheme).setOnClickListener {
            val current = prefs.getString(IntercomService.KEY_THEME, "system") ?: "system"
            singleChoiceDialog(
                titleRes = R.string.prefs_theme_title,
                entriesArrayRes = R.array.theme_entries,
                valuesArrayRes = R.array.theme_values,
                selectedValue = current,
            ) { picked ->
                prefs.edit().putString(IntercomService.KEY_THEME, picked).apply()
                themeValue.text = themeLabel(picked)
                AppCompatDelegate.setDefaultNightMode(
                    when (picked) {
                        "light" -> AppCompatDelegate.MODE_NIGHT_NO
                        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }
        }

        wireSwitchRow(
            row = findViewById(R.id.rowTts),
            title = R.string.prefs_tts_title, summary = R.string.prefs_tts_summary,
            key = IntercomService.KEY_TTS_ENABLED, default = true,
        )
        wireSwitchRow(
            row = findViewById(R.id.rowVibration),
            title = R.string.prefs_vibration_title, summary = R.string.prefs_vibration_summary,
            key = IntercomService.KEY_VIBRATION_ENABLED, default = true,
        )
        wireSwitchRow(
            row = findViewById(R.id.rowTones),
            title = R.string.prefs_tones_title, summary = R.string.prefs_tones_summary,
            key = IntercomService.KEY_TONES_ENABLED, default = true,
        )
        wireSwitchRow(
            row = findViewById(R.id.rowAutoDim),
            title = R.string.prefs_autodim_title, summary = R.string.prefs_autodim_summary,
            key = IntercomService.KEY_AUTO_DIM_ENABLED, default = true,
        )
    }

    private fun wireSwitchRow(
        row: LinearLayout,
        title: Int, summary: Int,
        key: String, default: Boolean,
    ) {
        row.findViewById<TextView>(R.id.itemTitle).setText(title)
        row.findViewById<TextView>(R.id.itemSummary).setText(summary)
        val sw = row.findViewById<MaterialSwitch>(R.id.itemSwitch)
        sw.isChecked = prefs.getBoolean(key, default)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
        // Klick irgendwo im Row toggelt den Switch – größere Touch-Fläche.
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
    }

    private fun languageLabel(tag: String): String = when (tag) {
        "en" -> getString(R.string.language_en)
        "de" -> getString(R.string.language_de)
        "es" -> getString(R.string.language_es)
        "it" -> getString(R.string.language_it)
        "fr" -> getString(R.string.language_fr)
        else -> getString(R.string.language_system)
    }

    private fun themeLabel(value: String): String = when (value) {
        "light" -> getString(R.string.theme_light)
        "dark" -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_system)
    }

    // -- Über ----------------------------------------------------------------

    private fun wireAboutSection() {
        val versionTv = findViewById<TextView>(R.id.versionValue)
        versionTv.text = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: Throwable) { "?" }

        findViewById<View>(R.id.rowShowIntro).setOnClickListener {
            getSharedPreferences("speakeasy", MODE_PRIVATE).edit()
                .putBoolean("intro_seen", false).apply()
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.prefs_show_intro_summary)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        findViewById<View>(R.id.rowLicenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }

        findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            val urlRes = if (resources.configuration.locales.get(0).language == "de") {
                R.string.privacy_url_de
            } else {
                R.string.privacy_url_en
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(getString(urlRes))))
            } catch (_: android.content.ActivityNotFoundException) {
                // Kein Browser installiert — sehr selten, ignorieren.
            }
        }

        findViewById<View>(R.id.rowReset).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.prefs_reset_title)
                .setMessage(R.string.prefs_reset_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.edit().clear().apply()
                    LocaleHelper.apply("system")
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // -- Helpers -------------------------------------------------------------

    private fun singleChoiceDialog(
        titleRes: Int,
        entriesArrayRes: Int,
        valuesArrayRes: Int,
        selectedValue: String,
        onPicked: (String) -> Unit,
    ) {
        val entries = resources.getStringArray(entriesArrayRes)
        val values = resources.getStringArray(valuesArrayRes)
        val selectedIdx = values.indexOf(selectedValue).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(entries, selectedIdx) { dialog, which ->
                onPicked(values[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
