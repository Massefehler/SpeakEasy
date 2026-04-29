package com.speakeasy.intercom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlin.math.log10

/**
 * Self-Test des Mikrofons – High-Level über MediaRecorder + MediaPlayer.
 *
 * Auf einigen OEM-Geräten (insb. Samsung OneUI) hat AAC/MP4 zu Problemen geführt:
 * Container-Finalisierung beim stop() konnte fehlschlagen → Datei korrupt →
 * MediaPlayer-Wiedergabe stumm. Deshalb nutzen wir hier **3GPP-Container mit
 * AMR_NB-Audio** als Default. Das Format ist eine Stream-Codierung (kein
 * komplexer MOOV-Atom-Block am Ende), funktioniert auf jedem Android-Gerät
 * seit API 1 und übersteht auch ungeplante Stops sauber.
 *
 * Wer explizit das BT-Headset-Mic prüfen will, baut eine echte Intercom-
 * Verbindung auf – nur dort ist der SCO-Pfad aktiv.
 */
class MicTestActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var levelLabel: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var recordBtn: MaterialButton
    private lateinit var playbackBtn: MaterialButton
    private val handler = Handler(Looper.getMainLooper())

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null

    @Volatile private var recording = false
    @Volatile private var playing = false

    private val outputFile by lazy { File(cacheDir, "mic_test.3gp") }

    private val recordTick = object : Runnable {
        var endAtMs = 0L
        override fun run() {
            val rec = recorder ?: return
            try {
                renderLevelFromAmplitude(rec.maxAmplitude)
            } catch (_: Throwable) { /* IllegalStateException kurz nach stop ignorieren */ }
            if (System.currentTimeMillis() >= endAtMs) {
                stopRecording()
            } else if (recording) {
                handler.postDelayed(this, 50)
            }
        }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else {
            Toast.makeText(this, R.string.error_permissions, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mic_test)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        statusTv = findViewById(R.id.micTestStatus)
        levelLabel = findViewById(R.id.micTestLevelLabel)
        levelBar = findViewById(R.id.micTestLevel)
        recordBtn = findViewById(R.id.micTestRecordBtn)
        playbackBtn = findViewById(R.id.micTestPlaybackBtn)

        recordBtn.setOnClickListener {
            if (recording) stopRecording() else ensureMicPermission()
        }
        playbackBtn.setOnClickListener {
            if (playing) stopPlayback() else startPlayback()
        }
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
        stopPlayback()
    }

    private fun ensureMicPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // -- Recording -----------------------------------------------------------

    private fun startRecording() {
        if (recording) return
        try { if (outputFile.exists()) outputFile.delete() } catch (_: Throwable) {}

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }

        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            // 3GPP-Container + AMR_NB-Audio: Stream-Format ohne komplexe
            // Container-Finalisierung beim stop(). Funktioniert garantiert
            // auf jedem Android-Gerät, übersteht auch abruptes Stoppen.
            rec.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            // AMR_NB: 8 kHz Mono, 12,2 kbps Bitrate – telefoniequalität,
            // aber für einen Mic-Test mehr als genug.
            rec.setAudioSamplingRate(8_000)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(12_200)
            rec.setOutputFile(outputFile.absolutePath)
            rec.prepare()
            rec.start()
        } catch (e: Throwable) {
            Log.e(TAG, "MediaRecorder start failed", e)
            try { rec.release() } catch (_: Throwable) {}
            toast("Aufnahme konnte nicht gestartet werden: ${e.message}")
            return
        }

        recorder = rec
        recording = true
        recordBtn.setText(R.string.mic_test_stop_btn)
        playbackBtn.isEnabled = false
        statusTv.setText(R.string.mic_test_status_recording)
        levelBar.progress = 0
        levelLabel.text = ""

        recordTick.endAtMs = System.currentTimeMillis() + RECORD_DURATION_MS
        handler.postDelayed(recordTick, 50)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(recordTick)
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (_: Throwable) { /* zu kurz aufgenommen */ }
        try { rec?.release() } catch (_: Throwable) {}

        recordBtn.setText(R.string.mic_test_record_btn)
        levelBar.progress = 0
        levelLabel.text = ""
        val haveData = outputFile.exists() && outputFile.length() > 100
        playbackBtn.isEnabled = haveData
        statusTv.setText(if (haveData) R.string.mic_test_status_done else R.string.mic_test_status_idle)
        if (!haveData) {
            val sz = if (outputFile.exists()) outputFile.length() else -1L
            toast("Aufnahme leer (Datei-Größe: $sz Byte). Mikrofon evtl. blockiert.")
        }
    }

    // -- Playback ------------------------------------------------------------

    private fun startPlayback() {
        if (playing) return
        if (!outputFile.exists() || outputFile.length() < 100) {
            toast("Keine Aufnahme vorhanden.")
            return
        }
        if (!requestAudioFocus()) {
            // Kein harter Abbruch – manche Devices verweigern Focus, spielen
            // aber trotzdem ab. Wir loggen es nur.
            Log.w(TAG, "AudioFocus not granted, trying to play anyway")
        }
        val mp = MediaPlayer()
        try {
            // Explizites Routing: Media-Output (A2DP wenn verbunden, sonst
            // Phone-Speaker). Default auf manchen Geräten (insb. Samsung)
            // routet sonst ins Leere.
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(outputFile.absolutePath)
            mp.setOnPreparedListener {
                try {
                    it.start()
                    Log.i(TAG, "Playback started, file size=${outputFile.length()} bytes")
                } catch (e: Throwable) {
                    Log.e(TAG, "MediaPlayer start failed", e)
                    handler.post {
                        toast("Wiedergabe-Start fehlgeschlagen: ${e.message}")
                        onPlaybackEnded()
                    }
                }
            }
            mp.setOnCompletionListener { onPlaybackEnded() }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                handler.post {
                    toast("Wiedergabe-Fehler ($what / $extra)")
                    onPlaybackEnded()
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Throwable) {
            Log.e(TAG, "MediaPlayer setup failed", e)
            try { mp.release() } catch (_: Throwable) {}
            abandonAudioFocus()
            toast("Wiedergabe konnte nicht gestartet werden: ${e.message}")
            return
        }
        player = mp
        playing = true
        playbackBtn.setText(R.string.mic_test_stop_btn)
        recordBtn.isEnabled = false
        statusTv.setText(R.string.mic_test_status_playing)
        levelBar.progress = 0
        levelLabel.text = ""
    }

    private fun stopPlayback() {
        if (!playing) return
        val mp = player
        player = null
        try { mp?.stop() } catch (_: Throwable) {}
        try { mp?.release() } catch (_: Throwable) {}
        onPlaybackEnded()
    }

    private fun onPlaybackEnded() {
        playing = false
        try { player?.release() } catch (_: Throwable) {}
        player = null
        abandonAudioFocus()
        playbackBtn.setText(R.string.mic_test_playback_btn)
        recordBtn.isEnabled = true
        statusTv.setText(R.string.mic_test_status_done)
        levelBar.progress = 0
        levelLabel.text = ""
    }

    // -- Audio-Focus ---------------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { /* nichts – kurze Wiedergabe */ }
                .build()
            focusRequest = req
            val res = audioManager.requestAudioFocus(req)
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    // -- Pegel-Anzeige -------------------------------------------------------

    private fun renderLevelFromAmplitude(amp: Int) {
        // AMR_NB-Recorder liefert maxAmplitude in 0..32767, wie sonst auch.
        val linear = amp / 32_768.0
        val db = if (linear < 1e-6) -120.0 else 20.0 * log10(linear)
        val pct = ((db - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0.0, 1.0)
        levelBar.progress = (pct * 1000).toInt()
        levelLabel.text = if (db > -80.0) String.format("%.0f dBFS", db) else "—"
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "MicTest"
        private const val RECORD_DURATION_MS = 5_000L

        private const val DB_MIN = -50.0
        private const val DB_MAX = -5.0
    }
}
