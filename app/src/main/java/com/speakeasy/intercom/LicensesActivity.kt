package com.speakeasy.intercom

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Listet die Open-Source-Lizenzen aller eingebundenen Bibliotheken.
 * libopus ist BSD-3-Clause, AndroidX/Material sind Apache-2.0 — Anzeige ist
 * rechtlich Pflicht.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        val opusBody = findViewById<TextView>(R.id.licenseOpusBody)
        opusBody.text = readRawText(R.raw.license_opus)
    }

    private fun readRawText(resId: Int): String =
        resources.openRawResource(resId).bufferedReader().use { it.readText() }
}
