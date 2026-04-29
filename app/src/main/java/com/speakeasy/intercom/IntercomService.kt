package com.speakeasy.intercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.io.IOException
import java.util.Locale

/**
 * Foreground-Service, der die RFCOMM-Verbindung und das Audio während der Fahrt am Leben hält.
 *
 * Wird per Intents gesteuert und meldet seinen Zustand über [stateListener] sowie den
 * Audio-Pegel über [levelListener].
 */
class IntercomService : android.app.Service() {

    enum class State { IDLE, SEARCHING, LISTENING, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    inner class LocalBinder : Binder() {
        val service: IntercomService get() = this@IntercomService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var peerName: String? = null
        private set
    @Volatile var reconnectAttempt: Int = 0
        private set

    var stateListener: ((State) -> Unit)? = null
    var levelListener: ((sentRms: Float, recvRms: Float) -> Unit)? = null
    var rttListener: ((rttMs: Long) -> Unit)? = null
    var peerBatteryListener: ((percent: Int) -> Unit)? = null

    @Volatile var peerBatteryPercent: Int = -1
        private set

    private lateinit var adapter: BluetoothAdapter
    private var link: BluetoothLink? = null
    private var connection: BluetoothLink.Connection? = null
    private var audio: AudioEngine? = null
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var toneGen: ToneGenerator? = null
    private var audioManagerSvc: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handleAudioFocusChange(change)
    }

    /** Letzter erfolgreicher Peer – wird für Auto-Reconnect gespeichert. */
    private var lastPeerDevice: BluetoothDevice? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingHandover: Runnable? = null
    private var pendingReconnect: Runnable? = null
    private var pendingDirectAccept: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val mgr = getSystemService(BluetoothManager::class.java)
        adapter = mgr.adapter
        audioManagerSvc = getSystemService(AUDIO_SERVICE) as? AudioManager
        ensureNotificationChannel()
        // TTS lazy initialisieren – erste Ansage erfolgt mit kleiner Verzögerung,
        // alle weiteren sind sofort einsatzbereit.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.GERMAN
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(currentNotificationText()))
        when (intent?.action) {
            ACTION_HOST -> startHosting()
            ACTION_SEARCH -> startSearch()
            ACTION_JOIN -> deviceFromIntent(intent)?.let(::connectTo)
            ACTION_ACCEPT -> deviceFromIntent(intent)?.let(::acceptInvitation)
            ACTION_DIRECT -> deviceFromIntent(intent)?.let(::directConnect)
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    private fun deviceFromIntent(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DEVICE)

    private fun currentNotificationText(): String = when (state) {
        State.IDLE -> getString(R.string.status_idle)
        State.SEARCHING -> getString(R.string.status_searching)
        State.LISTENING -> getString(R.string.status_listening)
        State.CONNECTING -> getString(R.string.status_connecting)
        State.CONNECTED -> getString(R.string.status_connected, peerName ?: "")
        State.RECONNECTING -> getString(R.string.status_reconnecting, reconnectAttempt)
        State.ERROR -> getString(R.string.status_error, lastError ?: "")
    }

    /** Hostet eine Verbindung – wartet auf den Peer. */
    fun startHosting() {
        if (state != State.IDLE && state != State.ERROR) return
        transition(State.LISTENING)
        worker = Thread({
            val l = BluetoothLink(adapter).also { link = it }
            try {
                val conn = l.listen()
                onConnected(conn)
            } catch (e: IOException) {
                if (state == State.LISTENING) fail("Hosten fehlgeschlagen: ${e.message}")
            }
        }, "intercom-host").also { it.start() }
    }

    /**
     * Sucht-Modus: öffnet einen RFCOMM-SDP-Server, damit andere SpeakEasy-Apps uns
     * per UUID-Filter erkennen können.
     */
    fun startSearch() {
        if (state != State.IDLE && state != State.ERROR) return
        transition(State.SEARCHING)
        worker = Thread({
            val l = BluetoothLink(adapter).also { link = it }
            try {
                val conn = l.listen()
                onConnected(conn)
            } catch (e: IOException) {
                if (state == State.SEARCHING) fail("Suche fehlgeschlagen: ${e.message}")
            }
        }, "intercom-search").also { it.start() }
    }

    /**
     * Aus dem Such-Modus heraus zum gewählten Peer verbinden.
     *
     * Tiebreaker: Beide Telefone können gleichzeitig in [acceptInvitation] landen.
     * Damit nicht beide den eigenen Server schließen und ins Leere connecten,
     * vergleichen wir die Bluetooth-Adapter-Namen: der lex-kleinere initiiert,
     * der lex-größere bleibt im accept() und wartet auf den eingehenden Connect.
     * Sollte der Initiator nichts tun, übernimmt der Wartende nach
     * [HANDOVER_FALLBACK_MS] selbst.
     */
    fun acceptInvitation(device: BluetoothDevice) {
        if (state != State.SEARCHING) {
            connectTo(device); return
        }
        val localName = ownAdapterName()
        val peerLabel = device.deviceLabel()
        val iInitiate = shouldInitiate(localName, peerLabel, device.address)
        Log.i(TAG, "Tiebreaker: lokal=\"$localName\" peer=\"$peerLabel\" → ${if (iInitiate) "initiiere" else "warte"}")

        peerName = peerLabel
        transition(State.CONNECTING)

        if (iInitiate) {
            // Eigenen SDP-Server schließen, damit wir aktiv connecten können.
            link?.cancel(); link = null
            worker?.interrupt(); worker = null
            startConnectWorker(device, "intercom-accept")
        } else {
            // Wir bleiben im accept() und vertrauen darauf, dass die Gegenseite
            // initiiert. Falls das nicht passiert, übernehmen wir nach kurzer Frist.
            scheduleHandover(device)
        }
    }

    /** Verbindet sich zu einem Peer (manuell, ohne Tiebreaker). */
    fun connectTo(device: BluetoothDevice) {
        if (state != State.IDLE && state != State.ERROR) return
        peerName = device.deviceLabel()
        transition(State.CONNECTING)
        startConnectWorker(device, "intercom-join")
    }

    /**
     * Direkt-Verbindung zum letzten Peer ohne Discovery/Discoverable-Prompt.
     * Wir öffnen kurzzeitig den SDP-Server (damit die Gegenseite uns connecten
     * kann, falls sie schneller war) und gehen 400 ms später in den normalen
     * Tiebreaker – das ist robust gegen "wer drückt zuerst".
     */
    fun directConnect(device: BluetoothDevice) {
        if (state != State.IDLE && state != State.ERROR) return
        peerName = device.deviceLabel()
        transition(State.SEARCHING)
        worker = Thread({
            val l = BluetoothLink(adapter).also { link = it }
            try {
                val conn = l.listen()
                onConnected(conn)
            } catch (e: IOException) {
                if (state == State.SEARCHING) fail("Direkt-Verbindung fehlgeschlagen: ${e.message}", peer = device)
            }
        }, "intercom-direct-listen").also { it.start() }

        cancelPendingDirectAccept()
        val task = Runnable {
            // Falls der Peer in der kurzen Zeit selbst connectet hat, ist state schon CONNECTED.
            if (state == State.SEARCHING) acceptInvitation(device)
        }
        pendingDirectAccept = task
        mainHandler.postDelayed(task, 400L)
    }

    private fun cancelPendingDirectAccept() {
        pendingDirectAccept?.let { mainHandler.removeCallbacks(it) }
        pendingDirectAccept = null
    }

    fun stop() {
        cancelPendingHandover()
        cancelPendingReconnect()
        cancelPendingDirectAccept()
        lastPeerDevice = null
        reconnectAttempt = 0
        abandonAudioFocus()
        audio?.stop(); audio = null
        connection?.close(); connection = null
        link?.cancel(); link = null
        worker?.interrupt(); worker = null
        peerName = null
        peerBatteryPercent = -1
        peerBatteryListener?.invoke(-1)
        releaseWakeLock()
        transition(State.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setMuted(muted: Boolean) { audio?.setMuted(muted) }
    fun isMuted(): Boolean = audio?.isMuted() == true

    fun setPeerVolume(volume: Float) {
        audio?.setPeerVolume(volume)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PEER_VOLUME, volume).apply()
    }

    fun getPeerVolume(): Float = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat(KEY_PEER_VOLUME, 1.0f)

    fun setGateBiasDb(db: Float) {
        audio?.setGateBiasDb(db.toDouble())
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_GATE_BIAS, db).apply()
    }

    fun getGateBiasDb(): Float = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat(KEY_GATE_BIAS, 0f)

    fun snapshot(): AudioEngine.Diagnostics? = audio?.snapshot()

    // -- internals -------------------------------------------------------------

    private fun startConnectWorker(device: BluetoothDevice, threadName: String) {
        worker = Thread({
            val l = BluetoothLink(adapter).also { link = it }
            try {
                val conn = l.connect(device)
                onConnected(conn)
            } catch (e: IOException) {
                fail("Verbindung fehlgeschlagen: ${e.message}", peer = device)
            } catch (e: SecurityException) {
                fail("Bluetooth-Berechtigung fehlt", peer = device)
            }
        }, threadName).also { it.start() }
    }

    private fun scheduleHandover(device: BluetoothDevice) {
        cancelPendingHandover()
        val task = Runnable {
            if (state != State.CONNECTING) return@Runnable
            Log.i(TAG, "Tiebreaker-Fallback: warte zu lange, initiiere selbst")
            link?.cancel(); link = null
            worker?.interrupt(); worker = null
            startConnectWorker(device, "intercom-handover")
        }
        pendingHandover = task
        mainHandler.postDelayed(task, HANDOVER_FALLBACK_MS)
    }

    private fun cancelPendingHandover() {
        pendingHandover?.let { mainHandler.removeCallbacks(it) }
        pendingHandover = null
    }

    private fun cancelPendingReconnect() {
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingReconnect = null
    }

    private fun onConnected(conn: BluetoothLink.Connection) {
        cancelPendingHandover()
        cancelPendingReconnect()
        cancelPendingDirectAccept()
        connection = conn
        peerName = conn.remoteName
        lastPeerDevice = conn.remoteDevice
        reconnectAttempt = 0
        lastError = null
        persistLastPeer(conn.remoteDevice, conn.remoteName)
        acquireWakeLock()
        val engine = AudioEngine(this).also { audio = it }
        engine.levelListener = { s, r -> levelListener?.invoke(s, r) }
        engine.rttListener = { ms -> rttListener?.invoke(ms) }
        engine.peerBatteryListener = { pct ->
            peerBatteryPercent = pct
            peerBatteryListener?.invoke(pct)
        }
        engine.setMicMode(loadMicMode())
        engine.setPeerVolume(getPeerVolume())
        engine.setGateBiasDb(getGateBiasDb().toDouble())

        // Codec + Heartbeat + AGC + Adaptive-Bitrate aus den Settings ziehen,
        // damit beide Seiten dieselbe Konfiguration fahren. Defaults:
        // Opus 24 kbps, 4 s Heartbeat, AGC + Adaptive-Bitrate beide an.
        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        engine.setCodec(buildCodecFromPrefs(settings))
        engine.setHeartbeatIntervalMs(
            settings.getInt(KEY_HEARTBEAT_SECONDS, 4) * 1_000L
        )
        engine.setAgcEnabled(settings.getBoolean(KEY_AGC_ENABLED, true))
        engine.setAdaptiveBitrate(
            enabled = settings.getBoolean(KEY_ADAPTIVE_BITRATE, true),
            highBitrateBps = settings.getInt(KEY_OPUS_BITRATE_KBPS, 24) * 1_000,
        )

        engine.start(conn.input, conn.output) { err ->
            Log.w(TAG, "Audio/Stream-Fehler", err)
            fail(err.message ?: "Verbindung verloren", peer = conn.remoteDevice)
        }
        // Audio-Focus anfordern: bei Anruf, Alarm o. ä. wird der Listener
        // benachrichtigt und wir pausieren temporär (RFCOMM bleibt am Leben).
        requestAudioFocus()
        transition(State.CONNECTED)
    }

    private fun buildCodecFromPrefs(prefs: android.content.SharedPreferences): Codec {
        return when (prefs.getString(KEY_CODEC, "opus")) {
            "mulaw" -> MuLawCodec()
            "pcm" -> PcmCodec()
            else -> OpusCodec(
                bitrate = prefs.getInt(KEY_OPUS_BITRATE_KBPS, 24) * 1_000,
                packetLossPercent = prefs.getInt(KEY_OPUS_FEC_LOSS, 10),
            )
        }
    }

    fun setMicMode(mode: AudioEngine.MicMode) {
        audio?.setMicMode(mode)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MIC_MODE, mode.name).apply()
    }

    fun getMicMode(): AudioEngine.MicMode = loadMicMode()

    private fun loadMicMode(): AudioEngine.MicMode {
        val name = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MIC_MODE, AudioEngine.MicMode.WIND_FILTER.name)
        return runCatching { AudioEngine.MicMode.valueOf(name!!) }
            .getOrDefault(AudioEngine.MicMode.WIND_FILTER)
    }

    private fun persistLastPeer(device: BluetoothDevice, label: String) {
        ProfileStore.promote(this, device.address, label)
    }

    /**
     * Bei Verbindungsverlust: drei Versuche mit Backoff (1 s / 3 s / 8 s) zum letzten
     * bekannten Peer, danach echter ERROR-Zustand. Manuelles [stop] oder explizit
     * übergebenes `peer = null` deaktiviert den Reconnect.
     */
    private fun fail(message: String, peer: BluetoothDevice? = lastPeerDevice) {
        lastError = message
        abandonAudioFocus()
        audio?.stop(); audio = null
        connection?.close(); connection = null
        link?.cancel(); link = null
        worker?.interrupt(); worker = null
        cancelPendingHandover()
        // Peer-Akku-Anzeige bei Verbindungsverlust zurücksetzen — Wert wäre
        // jetzt veraltet, der nächste STATUS-Frame kommt erst nach Reconnect.
        peerBatteryPercent = -1
        peerBatteryListener?.invoke(-1)

        if (peer != null && reconnectAttempt < RECONNECT_DELAYS.size) {
            scheduleReconnect(peer)
        } else {
            cancelPendingReconnect()
            lastPeerDevice = null
            reconnectAttempt = 0
            releaseWakeLock()
            transition(State.ERROR)
        }
    }

    private fun scheduleReconnect(device: BluetoothDevice) {
        cancelPendingReconnect()
        val delayMs = RECONNECT_DELAYS[reconnectAttempt]
        reconnectAttempt++
        lastPeerDevice = device
        peerName = device.deviceLabel()
        transition(State.RECONNECTING)
        Log.i(TAG, "Reconnect-Versuch $reconnectAttempt in ${delayMs}ms")
        val task = Runnable {
            if (state != State.RECONNECTING) return@Runnable
            startConnectWorker(device, "intercom-reconnect-$reconnectAttempt")
        }
        pendingReconnect = task
        mainHandler.postDelayed(task, delayMs)
    }

    private fun transition(newState: State) {
        val previous = state
        state = newState
        stateListener?.invoke(newState)
        updateNotification(currentNotificationText())
        announceTransition(previous, newState)
    }

    /**
     * Akustische und haptische Bestätigung bei Statuswechsel – auf dem Motorrad
     * sieht man das Display nicht, also gibt's einen kurzen Vibrations-Tick und
     * eine TTS-Ansage über das Headset. Welche Cues laufen, entscheidet die
     * Settings-Activity (TTS / Vibration / Töne einzeln abschaltbar).
     */
    private fun announceTransition(from: State, to: State) {
        when (to) {
            State.CONNECTED -> {
                if (from != State.CONNECTED) {
                    vibrate(longArrayOf(0, 60, 80, 60))
                    playTone(ToneGenerator.TONE_PROP_BEEP, 180)
                    speak(getString(R.string.tts_connected, peerName ?: ""))
                }
            }
            State.RECONNECTING -> {
                vibrate(longArrayOf(0, 200))
                playTone(ToneGenerator.TONE_PROP_NACK, 220)
                if (reconnectAttempt == 1) speak(getString(R.string.tts_reconnecting))
            }
            State.ERROR -> {
                vibrate(longArrayOf(0, 250, 120, 250))
                playTone(ToneGenerator.TONE_CDMA_REORDER, 350)
                speak(getString(R.string.tts_error))
            }
            State.IDLE -> {
                if (from == State.CONNECTED || from == State.RECONNECTING) {
                    vibrate(longArrayOf(0, 80, 100, 80))
                    speak(getString(R.string.tts_disconnected))
                }
            }
            else -> { /* keine Cues für Searching/Listening/Connecting */ }
        }
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    /**
     * Fordert Audio-Focus für die laufende Intercom-Session an. Damit benachrichtigt
     * uns das System, wenn ein Anruf eingeht oder eine andere App temporär die
     * Audio-Hoheit übernimmt – wir pausieren dann unsere Wiedergabe und Mic-
     * Capture, RFCOMM-Heartbeat läuft weiter, sodass die Strecke nach Anruf-Ende
     * nahtlos zurückkommt.
     */
    private fun requestAudioFocus() {
        val am = audioManagerSvc ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManagerSvc ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION") am.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio-Focus verloren ($focusChange) – pausiere Audio")
                audio?.pause()
                speak(getString(R.string.tts_paused_for_call))
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio-Focus zurück – setze Audio fort")
                audio?.resume()
                speak(getString(R.string.tts_resumed))
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (!prefs().getBoolean(KEY_VIBRATION_ENABLED, true)) return
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        } catch (_: Throwable) {}
    }

    private fun playTone(toneType: Int, durationMs: Int) {
        if (!prefs().getBoolean(KEY_TONES_ENABLED, true)) return
        try {
            val gen = toneGen ?: ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { toneGen = it }
            gen.startTone(toneType, durationMs)
        } catch (_: Throwable) { /* ToneGenerator nicht verfügbar – ignorierbar */ }
    }

    private fun speak(text: String) {
        if (!prefs().getBoolean(KEY_TTS_ENABLED, true)) return
        val t = tts ?: return
        if (!ttsReady) return
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speakeasy-status")
    }

    private fun shouldInitiate(localName: String, peerName: String, peerAddress: String): Boolean {
        // Lex-kleinerer Name initiiert. Bei Gleichstand entscheidet die Peer-MAC.
        val cmp = localName.compareTo(peerName)
        if (cmp != 0) return cmp < 0
        // Sehr seltener Fall (zwei identisch benannte Geräte): vergleiche unsere bevorzugt
        // ableitbare ID mit der Peer-MAC. `adapter.address` liefert auf Android 9+ meist
        // "02:00:00:00:00:00", deshalb fallback auf unsere Bluetooth-Namen-Hash plus
        // Peer-Adresse als deterministischen Tiebreaker.
        val ownToken = adapter.address ?: localName.hashCode().toString()
        return ownToken.compareTo(peerAddress) < 0
    }

    private fun ownAdapterName(): String = try {
        adapter.name ?: ""
    } catch (_: SecurityException) { "" }

    private fun BluetoothDevice.deviceLabel(): String = try {
        name ?: address
    } catch (_: SecurityException) { address }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speakeasy:intercom").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Action „Trennen" – ein einziger Tipper auf Lockscreen / Heads-Up beendet
        // die Verbindung. Wichtig auf dem Motorrad mit Handschuhen, weil die App
        // dafür nicht erst geöffnet werden muss.
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, IntercomService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_disconnect,
            getString(R.string.btn_disconnect),
            stopPi,
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_intercom)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent)
            .addAction(stopAction)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Intercom", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aktive Intercom-Verbindung"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingHandover()
        cancelPendingReconnect()
        cancelPendingDirectAccept()
        abandonAudioFocus()
        try { audio?.stop() } catch (_: Throwable) {}
        try { connection?.close() } catch (_: Throwable) {}
        try { link?.cancel() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { toneGen?.release() } catch (_: Throwable) {}
        tts = null; ttsReady = false; toneGen = null
        releaseWakeLock()
    }

    companion object {
        private const val TAG = "IntercomService"
        private const val CHANNEL_ID = "intercom"
        private const val NOTIFICATION_ID = 1

        const val ACTION_HOST = "com.speakeasy.intercom.HOST"
        const val ACTION_SEARCH = "com.speakeasy.intercom.SEARCH"
        const val ACTION_JOIN = "com.speakeasy.intercom.JOIN"
        const val ACTION_ACCEPT = "com.speakeasy.intercom.ACCEPT"
        const val ACTION_DIRECT = "com.speakeasy.intercom.DIRECT"
        const val ACTION_STOP = "com.speakeasy.intercom.STOP"
        const val EXTRA_DEVICE = "device"

        private const val PREFS = "speakeasy"
        const val KEY_MIC_MODE = "mic_mode"
        const val KEY_PEER_VOLUME = "peer_volume"
        const val KEY_GATE_BIAS = "gate_bias_db"

        // Schlüssel aus dem PreferenceManager-Default (settings_prefs).
        const val KEY_CODEC = "codec"
        const val KEY_OPUS_BITRATE_KBPS = "opus_bitrate_kbps"
        const val KEY_OPUS_FEC_LOSS = "opus_fec_loss_percent"
        const val KEY_HEARTBEAT_SECONDS = "heartbeat_seconds"
        const val KEY_TTS_ENABLED = "tts_enabled"
        const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        const val KEY_TONES_ENABLED = "tones_enabled"
        const val KEY_AUTO_DIM_ENABLED = "auto_dim_enabled"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_LANGUAGE = "language"
        const val KEY_THEME = "theme"
        const val KEY_AGC_ENABLED = "agc_enabled"
        const val KEY_ADAPTIVE_BITRATE = "adaptive_bitrate"

        // Settings-Polish (seit v1.7-beta17).
        const val KEY_AUTO_DIM_SPEED = "auto_dim_speed"      // "fast" / "default" / "medium" / "slow"
        const val KEY_DISCOVERABLE_TIMEOUT = "discoverable_timeout_s" // 60 / 120 / 300 (Int)

        fun loadProfiles(context: Context): List<Profile> = ProfileStore.loadAll(context)

        fun loadMicMode(context: Context): AudioEngine.MicMode {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MIC_MODE, AudioEngine.MicMode.WIND_FILTER.name)
            return runCatching { AudioEngine.MicMode.valueOf(name!!) }
                .getOrDefault(AudioEngine.MicMode.WIND_FILTER)
        }

        // Backoff für Auto-Reconnect: 1 s, 3 s, 8 s. Anzahl bestimmt Versuchszahl.
        private val RECONNECT_DELAYS = longArrayOf(1_000L, 3_000L, 8_000L)
        // Wartezeit für den Tiebreaker-Fallback, falls die Gegenseite nichts unternimmt.
        private const val HANDOVER_FALLBACK_MS = 6_000L

        // (Stale-Link-Logik liegt jetzt direkt im AudioEngine-Heartbeat-Thread:
        //  3× Roh-RTT > 1,5 s ODER 12 s ohne PONG ⇒ IOException ⇒ Reconnect-Pfad.
        //  Konstanten siehe AudioEngine.STALE_RTT_THRESHOLD_MS / STALE_RTT_SAMPLES /
        //  PONG_TIMEOUT_MS.)

        fun host(context: Context) {
            context.startForegroundService(
                Intent(context, IntercomService::class.java).setAction(ACTION_HOST)
            )
        }

        fun search(context: Context) {
            context.startForegroundService(
                Intent(context, IntercomService::class.java).setAction(ACTION_SEARCH)
            )
        }

        fun join(context: Context, device: BluetoothDevice) {
            context.startForegroundService(
                Intent(context, IntercomService::class.java)
                    .setAction(ACTION_JOIN)
                    .putExtra(EXTRA_DEVICE, device)
            )
        }

        fun acceptInvitation(context: Context, device: BluetoothDevice) {
            context.startForegroundService(
                Intent(context, IntercomService::class.java)
                    .setAction(ACTION_ACCEPT)
                    .putExtra(EXTRA_DEVICE, device)
            )
        }

        fun directConnect(context: Context, device: BluetoothDevice) {
            context.startForegroundService(
                Intent(context, IntercomService::class.java)
                    .setAction(ACTION_DIRECT)
                    .putExtra(EXTRA_DEVICE, device)
            )
        }

        fun stopIntent(context: Context) {
            context.startService(
                Intent(context, IntercomService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
