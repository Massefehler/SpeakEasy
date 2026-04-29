package com.speakeasy.intercom

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Friends-Beta-Feedback-Kanal: öffnet einen Mail-Intent vorbefüllt mit App-/
 * Geräte-Info. Wenn lokale Crash-Logs vorliegen, wird zusätzlich ein
 * "Mit Logs senden"-Knopf angeboten (ACTION_SEND_MULTIPLE + FileProvider-URIs).
 *
 * Bewusst ohne Server: keine Telemetrie, keine Drittanbieter-Bibliothek, der
 * User entscheidet pro Mail, was er teilt.
 */
object Feedback {

    private const val EMAIL = "speakeasy@skymail.eu"

    fun show(context: Context) {
        val logs = collectLogFiles(context)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.feedback_dialog_title)
            .setMessage(R.string.feedback_dialog_msg)
            .setPositiveButton(R.string.feedback_dialog_without_logs) { _, _ ->
                send(context, logs = emptyList())
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (logs.isNotEmpty()) {
            dialog.setNeutralButton(R.string.feedback_dialog_with_logs) { _, _ ->
                send(context, logs = logs)
            }
        }
        dialog.show()
    }

    private fun collectLogFiles(context: Context): List<File> {
        val dir = CrashHandler.crashesDir(context)
        return (dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    private fun send(context: Context, logs: List<File>) {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Throwable) { null }
        val versionName = info?.versionName ?: "?"
        val versionCode = if (info == null) 0L
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()

        val subject = context.getString(R.string.feedback_subject, versionName)
        val body = context.getString(
            R.string.feedback_body_template,
            versionName,
            versionCode,
            Build.MANUFACTURER ?: "?",
            Build.MODEL ?: "?",
            Build.VERSION.RELEASE ?: "?",
            Build.VERSION.SDK_INT,
        )

        val intent = if (logs.isNotEmpty()) {
            val authority = "${context.packageName}.fileprovider"
            val uris = ArrayList<Uri>(logs.size)
            logs.forEach { f ->
                runCatching { uris += FileProvider.getUriForFile(context, authority, f) }
            }
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            // mailto: filtert die Auswahl auf reine E-Mail-Apps und vermeidet,
            // dass Messenger/Browser im Chooser auftauchen.
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$EMAIL")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        }

        val chooser = Intent.createChooser(intent, context.getString(R.string.feedback_dialog_title))
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.feedback_no_email_app, Toast.LENGTH_LONG).show()
        }
    }
}
