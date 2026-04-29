package com.speakeasy.intercom

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UncaughtExceptionHandler, der jeden Crash als Text-Datei in
 * `<external-files-dir>/crashes/` ablegt und danach den Default-Handler aufruft
 * (= System bringt Standard-Dialog + Prozess-Kill).
 *
 * Vorteil gegenüber Firebase Crashlytics: keine Internet-Abhängigkeit, kein
 * Tracking-Risiko, User schickt die Datei manuell beim Bug-Report. Reicht für
 * eine Friends-Beta.
 */
object CrashHandler {

    private const val MAX_LOGS = 30

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeCrash(app, thread, throwable) } catch (_: Throwable) { /* nicht eskalieren */ }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashesDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "crashes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashesDir(context)
        rotate(dir)
        val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(dir, "crash_$ts.txt")
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("=== SpeakEasy Crash ===")
        pw.println("Time: $ts")
        pw.println("Thread: ${thread.name}")
        pw.println("Build: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT})")
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            pw.println("App: ${info.versionName} (${info.longVersionCode})")
        } catch (_: Throwable) {}
        pw.println()
        throwable.printStackTrace(pw)
        pw.flush()
        file.writeText(sw.toString())
    }

    private fun rotate(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") } ?: return
        if (files.size <= MAX_LOGS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_LOGS)
            .forEach { runCatching { it.delete() } }
    }
}
