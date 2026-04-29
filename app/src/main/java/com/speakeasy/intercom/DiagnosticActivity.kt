package com.speakeasy.intercom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

/**
 * Live-Diagnose: zeigt RTT, Frame-Counter, AEC/NS-Status und Codec.
 * Bindet sich an [IntercomService] und pollt einmal pro Sekunde [IntercomService.snapshot].
 */
class DiagnosticActivity : AppCompatActivity() {

    private var service: IntercomService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: TextView

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as IntercomService.LocalBinder).service
            bound = true
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null; bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)
        body = findViewById(R.id.diagBody)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.diagnostic_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_logs -> {
                exportCrashLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportCrashLogs() {
        val dir = CrashHandler.crashesDir(this)
        val files = (dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.diag_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val authority = "$packageName.fileprovider"
        val uris = ArrayList<android.net.Uri>(files.size)
        files.forEach { f ->
            runCatching { uris += FileProvider.getUriForFile(this, authority, f) }
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "SpeakEasy Crash-Logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.diag_export_logs)))
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, IntercomService::class.java), conn, Context.BIND_AUTO_CREATE)
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        if (bound) { unbindService(conn); bound = false }
    }

    private fun render() {
        val s = service
        val snap = s?.snapshot()
        val sb = StringBuilder()
        sb.appendLine("${getString(R.string.diag_state)}: ${s?.state ?: "–"}")
        sb.appendLine("${getString(R.string.diag_peer)}: ${s?.peerName ?: "–"}")
        if (snap != null) {
            sb.appendLine("${getString(R.string.diag_codec)}: ${snap.codecName}")
            sb.appendLine("${getString(R.string.diag_mic_mode)}: ${snap.micMode}")
            val aec = if (snap.aecActive) getString(R.string.diag_active) else getString(R.string.diag_inactive)
            val ns = if (snap.nsActive) getString(R.string.diag_active) else getString(R.string.diag_inactive)
            sb.appendLine("${getString(R.string.diag_aec)}: $aec")
            sb.appendLine("${getString(R.string.diag_ns)}: $ns")
            sb.appendLine("${getString(R.string.diag_frames)}: ${snap.framesTx} / ${snap.framesRx}")
            sb.appendLine("${getString(R.string.diag_rtt)}: ${snap.rttMs} ms / ${snap.avgRttMs} ms")
            sb.appendLine("${getString(R.string.diag_noise_floor)}: ${"%.1f".format(snap.noiseFloorDb)} dBFS")
            sb.appendLine("${getString(R.string.diag_gate_threshold)}: ${"%.1f".format(snap.gateThresholdDb)} dBFS")
        } else {
            sb.appendLine("(keine aktive Audio-Session)")
        }
        body.text = sb.toString()
    }
}
