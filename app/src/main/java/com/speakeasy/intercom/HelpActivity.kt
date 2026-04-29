package com.speakeasy.intercom

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * FAQ-/Hilfe-Bildschirm. Listet typische Probleme aus dem Real-Test-Betrieb
 * mit Lösungs-Hinweisen. Erreichbar übers 3-Punkt-Menü.
 *
 * Inhalte sind in `strings.xml` (alle 5 Locales) und werden hier nur kompiliert
 * und in einer Card pro Topic dargestellt.
 */
class HelpActivity : AppCompatActivity() {

    private data class Topic(val titleRes: Int, val bodyRes: Int)

    private val topics = listOf(
        Topic(R.string.help_t1_title, R.string.help_t1_body),
        Topic(R.string.help_t2_title, R.string.help_t2_body),
        Topic(R.string.help_t3_title, R.string.help_t3_body),
        Topic(R.string.help_t4_title, R.string.help_t4_body),
        Topic(R.string.help_t5_title, R.string.help_t5_body),
        Topic(R.string.help_t6_title, R.string.help_t6_body),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val faqList = findViewById<LinearLayout>(R.id.faqList)
        val inflater = LayoutInflater.from(this)
        topics.forEach { t ->
            val card = inflater.inflate(R.layout.item_help_topic, faqList, false) as MaterialCardView
            card.findViewById<TextView>(R.id.topicTitle).setText(t.titleRes)
            card.findViewById<TextView>(R.id.topicBody).setText(t.bodyRes)
            faqList.addView(card)
        }

        findViewById<MaterialButton>(R.id.feedbackBtn).setOnClickListener {
            Feedback.show(this)
        }
    }
}
