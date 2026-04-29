package com.speakeasy.intercom

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nimmt Audio vom verbundenen Bluetooth-Headset über SCO auf und tauscht es
 * mit dem Peer über die RFCOMM-Streams aus.
 *
 * Wire-Protokoll (Type-Length-Value):
 *   `[type:1][len_hi:1][len_lo:1][payload:len]`
 *   * Type 0 AUDIO   – payload = codierter Audio-Frame (Codec-spezifisch)
 *   * Type 1 PING    – payload = 8 Byte Sender-Timestamp (für RTT-Messung)
 *   * Type 2 PONG    – payload = 8 Byte echo des Ping-Timestamps
 */
class AudioEngine(private val context: Context) {

    enum class MicMode { OPEN, WIND_FILTER, VOICE_GATE }

    /** Snapshot der aktuellen Engine-Zustände für die Diagnose-Ansicht. */
    data class Diagnostics(
        val running: Boolean,
        val codecName: String,
        val micMode: MicMode,
        val aecActive: Boolean,
        val nsActive: Boolean,
        val framesTx: Long,
        val framesRx: Long,
        val rttMs: Long,
        val avgRttMs: Long,
        val noiseFloorDb: Double,
        val gateThresholdDb: Double,
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var running = false
    @Volatile private var muted = false
    /** Pausiert (Capture-/Playback-Loop skippt Audio-IO), aber RFCOMM/Heartbeat
     *  bleiben aktiv. Wird vom IntercomService bei Audio-Focus-Loss (Anruf,
     *  Alarm) gesetzt und bei -Gain wieder gelöscht – Verbindung bleibt am
     *  Leben, danach geht's nahtlos weiter. */
    @Volatile private var paused = false
    @Volatile private var lastRxAtMs = 0L
    @Volatile private var micMode: MicMode = MicMode.WIND_FILTER
    @Volatile private var peerVolume = 1.0f
    /** Bias auf die automatische Gate-Schwelle, in dB (-10 = empfindlicher, +10 = strenger). */
    @Volatile private var gateBiasDb = 0.0
    // Opus (VOIP / Wideband / VBR ~24 kbps) mit FEC + DTX – stabilste Sprachqualität
    // bei BT-Classic-typischen Spikes. μ-law/PCM bleiben als Fallback verfügbar.
    @Volatile private var codec: Codec = OpusCodec()
    @Volatile private var heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_DEFAULT_MS
    private val agc = Agc()

    /** Soll die Bitrate automatisch an die Funkqualität angepasst werden? */
    @Volatile private var adaptiveBitrateEnabled = false
    /** Vom User gewählte „normale" Opus-Bitrate (= obere Grenze des Adaptiv-Korridors). */
    @Volatile private var configuredOpusBitrate = OpusCodec.DEFAULT_BITRATE
    @Volatile private var adaptiveCurrentLow = false
    @Volatile private var adaptiveDownStreak = 0
    @Volatile private var adaptiveUpStreak = 0

    fun setCodec(c: Codec) { codec = c }
    fun setHeartbeatIntervalMs(ms: Long) {
        // 2–10 Sekunden – kürzer = schneller Dead-Link, mehr Funklast.
        heartbeatIntervalMs = ms.coerceIn(2_000L, 10_000L)
    }
    fun setAgcEnabled(enabled: Boolean) {
        if (agc.enabled != enabled) agc.reset()
        agc.enabled = enabled
    }

    /** Aktiviert die adaptive Bitrate. [highBitrateBps] ist die User-Sollwert-
     *  Bitrate (= obere Grenze; nach unten geht's auf [ADAPTIVE_LOW_BITRATE_BPS]). */
    /** Pausiert Mic-Capture und Peer-Wiedergabe; RFCOMM + Heartbeat bleiben aktiv. */
    fun pause() {
        if (paused) return
        paused = true
        Log.i(TAG, "Audio paused (focus loss)")
    }

    /** Setzt Audio nach einem Pause-Aufruf wieder fort. */
    fun resume() {
        if (!paused) return
        paused = false
        Log.i(TAG, "Audio resumed (focus gain)")
    }

    fun isPaused(): Boolean = paused

    fun setAdaptiveBitrate(enabled: Boolean, highBitrateBps: Int) {
        adaptiveBitrateEnabled = enabled
        configuredOpusBitrate = highBitrateBps
        adaptiveDownStreak = 0
        adaptiveUpStreak = 0
        adaptiveCurrentLow = false
        // Falls deaktiviert: User-Bitrate sofort wiederherstellen.
        if (!enabled) (codec as? OpusCodec)?.setBitrate(highBitrateBps)
    }

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var heartbeatThread: Thread? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private val outputLock = Any()

    var levelListener: ((sentRms: Float, recvRms: Float) -> Unit)? = null
    var rttListener: ((rttMs: Long) -> Unit)? = null
    var peerBatteryListener: ((percent: Int) -> Unit)? = null

    @Volatile private var lastSentRms = 0f
    @Volatile private var lastRecvRms = 0f
    @Volatile private var framesTx = 0L
    @Volatile private var framesRx = 0L
    @Volatile private var lastRttMs = 0L
    @Volatile private var avgRttMs = 0L
    @Volatile private var noiseFloorDb = -50.0
    @Volatile private var gateThresholdDb = VOICE_GATE_BASE_DB

    /** Zeitstempel des letzten empfangenen PONG (separat von lastRxAtMs, das auf
     *  ALLEN Frames triggert). Wenn Audio-Frames noch trickeln aber PONGs ausbleiben,
     *  ist das ein klares Indiz, dass die Funkstrecke „klemmt" und der bestehende
     *  RFCOMM-Channel sich nicht von selbst erholt. */
    @Volatile private var lastPongAtMs = 0L

    /** Aufeinanderfolgende PONGs mit hoher Einzel-RTT (kein Sliding-Average!).
     *  Mit dem Mittelwert dauerte es zu lange bis er bei einer hängenden Strecke
     *  über die Schwelle stieg, weil ältere gute Samples ihn glätten. */
    @Volatile private var consecutiveBadRttSamples = 0

    private val rttSamples = ArrayDeque<Long>()

    fun setMuted(value: Boolean) { muted = value }
    fun isMuted(): Boolean = muted
    fun setMicMode(mode: MicMode) { micMode = mode }
    fun setPeerVolume(volume: Float) {
        peerVolume = volume.coerceIn(0f, 1f)
        track?.setVolume(peerVolume)
    }
    fun setGateBiasDb(db: Double) { gateBiasDb = db.coerceIn(-15.0, 15.0) }

    fun snapshot(): Diagnostics = Diagnostics(
        running = running,
        codecName = codec.name,
        micMode = micMode,
        aecActive = aec?.enabled == true,
        nsActive = ns?.enabled == true,
        framesTx = framesTx,
        framesRx = framesRx,
        rttMs = lastRttMs,
        avgRttMs = avgRttMs,
        noiseFloorDb = noiseFloorDb,
        gateThresholdDb = gateThresholdDb,
    )

    @SuppressLint("MissingPermission")
    fun start(input: InputStream, output: OutputStream, onError: (Throwable) -> Unit) {
        if (running) return
        running = true
        lastRxAtMs = System.currentTimeMillis()
        framesTx = 0; framesRx = 0
        rttSamples.clear()
        agc.reset()

        routeAudioToBestDevice()

        val recordBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_IN, ENCODING
        ).coerceAtLeast(FRAME_BYTES * 4)

        val trackBufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_OUT, ENCODING
        ).coerceAtLeast(FRAME_BYTES * 4)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, recordBufBytes
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            running = false
            onError(IllegalStateException("AudioRecord konnte nicht initialisiert werden"))
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        }

        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(trackBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        trk.setVolume(peerVolume)
        record = rec
        track = trk

        rec.startRecording()
        trk.play()

        // Wenn der User während der Fahrt das Headset wechselt (USB ein-/ausstöpselt,
        // BT-Verbindung neu aufgebaut wird), re-routen wir live auf das neue Gerät.
        registerDeviceCallback()

        captureThread = thread(name = "intercom-capture", isDaemon = true) {
            val pcm = ByteArray(FRAME_BYTES)
            val encoded = ByteArray(codec.maxEncodedBytes.coerceAtLeast(FRAME_BYTES))
            val hpf = BiquadHpf(HPF_CUTOFF_HZ, SAMPLE_RATE.toDouble(), HPF_Q)
            var hangoverFrames = 0
            val noiseRing = DoubleArray(NOISE_FLOOR_FRAMES) { -120.0 }
            var noiseIdx = 0
            try {
                while (running) {
                    if (paused) {
                        // Während Pause kein Mic-Capture, aber Thread am Leben halten.
                        Thread.sleep(100)
                        continue
                    }
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n <= 0) continue

                    val mode = micMode
                    if (mode != MicMode.OPEN) hpf.processInPlace(pcm, n)

                    // AGC nach HPF: Wind ist schon raus, AGC sieht nur die Sprache.
                    agc.process(pcm, n)

                    val db = frameDb(pcm, n)

                    // Adaptive Gate-Schwelle: laufendes Min des Geräusch-Pegels + 6 dB + User-Bias.
                    noiseRing[noiseIdx] = db
                    noiseIdx = (noiseIdx + 1) % NOISE_FLOOR_FRAMES
                    val floor = noiseRing.min().coerceAtLeast(-80.0)
                    noiseFloorDb = floor
                    val threshold = (floor + GATE_HEADROOM_DB + gateBiasDb)
                        .coerceIn(VOICE_GATE_BASE_DB - 10.0, VOICE_GATE_BASE_DB + 15.0)
                    gateThresholdDb = threshold

                    val gateOpen = if (mode == MicMode.VOICE_GATE) {
                        if (db > threshold) {
                            hangoverFrames = HANGOVER_FRAMES; true
                        } else if (hangoverFrames > 0) {
                            hangoverFrames--; true
                        } else false
                    } else true

                    val transmit = !muted && gateOpen
                    if (transmit) {
                        val encLen = codec.encode(pcm, n, encoded)
                        writeFrame(output, TYPE_AUDIO, encoded, encLen)
                        lastSentRms = dbToVu(db)
                        framesTx++
                    } else {
                        // Stille bewusst nicht übertragen → entlastet RFCOMM massiv,
                        // RTT bleibt niedrig. Empfänger blockt im readFrame, bis Audio kommt.
                        lastSentRms = 0f
                    }
                    levelListener?.invoke(lastSentRms, lastRecvRms)
                }
            } catch (e: IOException) {
                if (running) onError(e)
            } catch (t: Throwable) {
                if (running) onError(t)
            }
        }

        playbackThread = thread(name = "intercom-playback", isDaemon = true) {
            val encoded = ByteArray(MAX_FRAME_PAYLOAD)
            val pcm = ByteArray(FRAME_BYTES * 2)
            try {
                while (running) {
                    val frame = readFrame(input, encoded)
                    val nowMs = System.currentTimeMillis()
                    lastRxAtMs = nowMs
                    when (frame.type) {
                        TYPE_AUDIO -> {
                            // Hinweis: Auto-PLC nach Inter-Arrival-Lücke war in v1.5-beta1/2
                            // aktiv, hat aber Audio doppelt eingespielt, weil RFCOMM Frames
                            // in Bursts liefert (nicht in echtem 20-ms-Takt). Folge: extreme
                            // RTT-Spitzen, „echoartiges" Audio. → Zurück zu v1.4-Verhalten:
                            // Opus' interner Decoder-State glättet kleinen Jitter ohnehin.
                            // `OpusCodec.decodePlc()` bleibt als API erhalten, falls wir
                            // später Loss über echte Sequenznummern erkennen wollen.
                            framesRx++
                            val pcmLen = codec.decode(encoded, frame.length, pcm)
                            // Decoder-State immer aktualisieren (auch in Pause), aber
                            // nur an die AudioTrack durchschieben, wenn nicht pausiert.
                            if (!paused && pcmLen > 0) trk.write(pcm, 0, pcmLen)
                            lastRecvRms = if (paused) 0f else dbToVu(frameDb(pcm, pcmLen))
                            levelListener?.invoke(lastSentRms, lastRecvRms)
                        }
                        TYPE_PING -> {
                            // Antwort mit identischem Payload (= unser-Sendezeitstempel).
                            writeFrame(output, TYPE_PONG, encoded, frame.length)
                        }
                        TYPE_PONG -> {
                            if (frame.length >= 8) {
                                val sentMs = readLongBE(encoded)
                                val rtt = (System.currentTimeMillis() - sentMs).coerceAtLeast(0L)
                                lastRttMs = rtt
                                rttSamples.addLast(rtt)
                                while (rttSamples.size > RTT_WINDOW) rttSamples.removeFirst()
                                avgRttMs = rttSamples.average().toLong()
                                lastPongAtMs = nowMs
                                // Roh-Sample-basierter Stale-Link-Counter: drei in Folge
                                // über 1,5 s ⇒ Heartbeat-Thread erzwingt Reconnect.
                                consecutiveBadRttSamples = if (rtt > STALE_RTT_THRESHOLD_MS) {
                                    consecutiveBadRttSamples + 1
                                } else 0
                                // Adaptive-Bitrate-Hysterese: bei schlechter Strecke
                                // runter, bei guter wieder hoch (asymmetrisch: schnell
                                // runter, langsam hoch, damit's nicht pumpt).
                                applyAdaptiveBitrate(avgRttMs)
                                // Geglätteter Mittelwert geht an die Anzeige – einzelne
                                // Spikes durch SCO-Kollisionen reißen sonst die Bars runter.
                                rttListener?.invoke(avgRttMs)
                            }
                        }
                        TYPE_STATUS -> {
                            // 1 Byte Akku-Prozent (0..100). Ältere App-Versionen ohne
                            // STATUS-Frame ignorieren diesen Pfad einfach.
                            if (frame.length >= 1) {
                                val pct = (encoded[0].toInt() and 0xFF).coerceIn(0, 100)
                                peerBatteryListener?.invoke(pct)
                            }
                        }
                        else -> Log.w(TAG, "Unbekannter Frame-Typ ${frame.type}")
                    }
                }
                if (running) onError(IOException("Verbindung vom Peer beendet"))
            } catch (e: IOException) {
                if (running) onError(e)
            } catch (t: Throwable) {
                if (running) onError(t)
            }
        }

        heartbeatThread = thread(name = "intercom-heartbeat", isDaemon = true) {
            val timestamp = ByteArray(8)
            val statusPayload = ByteArray(1)
            var lastStatusAtMs = 0L
            // Reset bei jeder neuen Engine-Session.
            lastPongAtMs = 0L
            consecutiveBadRttSamples = 0
            val sessionStartMs = System.currentTimeMillis()
            try {
                while (running) {
                    Thread.sleep(heartbeatIntervalMs)
                    if (!running) break
                    writeLongBE(System.currentTimeMillis(), timestamp)
                    writeFrame(output, TYPE_PING, timestamp, timestamp.size)

                    // Akku-Prozent alle ~30 s mitsenden. Wenig Funklast (1 Byte
                    // Payload + 3 Byte TLV-Header), aber praktisch auf langen Touren.
                    val now = System.currentTimeMillis()
                    if (now - lastStatusAtMs >= STATUS_INTERVAL_MS) {
                        lastStatusAtMs = now
                        val pct = readBatteryPercent()
                        if (pct in 0..100) {
                            statusPayload[0] = pct.toByte()
                            writeFrame(output, TYPE_STATUS, statusPayload, 1)
                        }
                    }

                    // 1) Dead-Link auf ALLEN Frames – fängt Totalausfall ab.
                    val sinceRx = now - lastRxAtMs
                    val deadLinkMs = (heartbeatIntervalMs * 5 / 2).coerceAtLeast(8_000L)
                    if (sinceRx > deadLinkMs) {
                        if (running) onError(IOException("Heartbeat-Timeout (${sinceRx} ms)"))
                        break
                    }

                    // 2) PONG-Timeout: explizit nur PONGs, unabhängig von Audio-Frames.
                    //    Wenn Audio aus dem RFCOMM-Buffer noch trickelt, aber Timestamp-
                    //    Echos längst nicht mehr durchkommen, klemmt die Strecke und
                    //    der bestehende RFCOMM-Channel erholt sich nicht von selbst.
                    //    Greift erst nach einer Schonfrist, damit der erste PONG
                    //    überhaupt eintreffen kann.
                    if (now - sessionStartMs > PONG_TIMEOUT_MS) {
                        val sincePong = if (lastPongAtMs > 0L) now - lastPongAtMs
                        else now - sessionStartMs
                        if (sincePong > PONG_TIMEOUT_MS) {
                            if (running) onError(IOException("PONG-Timeout (${sincePong} ms)"))
                            break
                        }
                    }

                    // 3) Stale-Link via aufeinanderfolgender Roh-RTT-Samples.
                    //    PONGs kommen zwar an, aber persistent über 1,5 s ⇒
                    //    BT-Controller in degradiertem Zustand, frischer Connect nötig.
                    if (consecutiveBadRttSamples >= STALE_RTT_SAMPLES) {
                        consecutiveBadRttSamples = 0
                        if (running) onError(IOException("Stale-Link (RTT > ${STALE_RTT_THRESHOLD_MS} ms)"))
                        break
                    }
                }
            } catch (_: InterruptedException) { /* normales Stop */ }
            catch (e: IOException) { if (running) onError(e) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        unregisterDeviceCallback()
        try { record?.stop() } catch (_: IllegalStateException) {}
        try { track?.stop() } catch (_: IllegalStateException) {}
        try { aec?.release() } catch (_: Throwable) {}
        try { ns?.release() } catch (_: Throwable) {}
        record?.release(); record = null
        track?.release(); track = null
        aec = null; ns = null
        captureThread?.interrupt(); captureThread = null
        playbackThread?.interrupt(); playbackThread = null
        heartbeatThread?.interrupt(); heartbeatThread = null
        codec.close()
        clearAudioRouting()
    }

    // -- Wire-Protokoll --------------------------------------------------------

    private data class Frame(val type: Byte, val length: Int)

    @Throws(IOException::class)
    private fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray, length: Int) {
        synchronized(outputLock) {
            out.write(type.toInt())
            out.write((length ushr 8) and 0xFF)
            out.write(length and 0xFF)
            if (length > 0) out.write(payload, 0, length)
            out.flush()
        }
    }

    @Throws(IOException::class)
    private fun readFrame(input: InputStream, payload: ByteArray): Frame {
        val type = input.read()
        if (type < 0) throw IOException("Stream geschlossen (header)")
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) throw IOException("Stream geschlossen (length)")
        val length = (hi shl 8) or lo
        if (length > payload.size) throw IOException("Frame zu groß: $length > ${payload.size}")
        var read = 0
        while (read < length) {
            val n = input.read(payload, read, length - read)
            if (n < 0) throw IOException("Stream geschlossen (payload)")
            read += n
        }
        return Frame(type.toByte(), length)
    }

    private fun writeLongBE(value: Long, dst: ByteArray) {
        for (i in 0..7) dst[i] = (value ushr ((7 - i) * 8) and 0xFF).toByte()
    }

    /**
     * Adaptive-Bitrate-State-Machine. Wird einmal pro empfangenem PONG aufgerufen.
     *
     * Hochsetzen (langsam, 5 Samples) und Runtersetzen (schnell, 3 Samples)
     * verhindert „Pumpen" bei wechselhafter Funkqualität. Zwischen den Schwellen
     * (400–800 ms) werden beide Counter zurückgesetzt – nur in Folge
     * tatsächlich anhaltende RTT-Werte triggern den Wechsel.
     */
    private fun applyAdaptiveBitrate(avgRtt: Long) {
        if (!adaptiveBitrateEnabled) return
        val opus = codec as? OpusCodec ?: return
        when {
            avgRtt > ADAPTIVE_RTT_HIGH_MS -> {
                adaptiveUpStreak = 0
                adaptiveDownStreak++
                if (!adaptiveCurrentLow && adaptiveDownStreak >= ADAPTIVE_DOWN_SAMPLES) {
                    adaptiveCurrentLow = true
                    Log.i(TAG, "Adaptive: → LOW (${ADAPTIVE_LOW_BITRATE_BPS / 1000} kbps), avgRTT=$avgRtt ms")
                    opus.setBitrate(ADAPTIVE_LOW_BITRATE_BPS)
                }
            }
            avgRtt < ADAPTIVE_RTT_LOW_MS -> {
                adaptiveDownStreak = 0
                adaptiveUpStreak++
                if (adaptiveCurrentLow && adaptiveUpStreak >= ADAPTIVE_UP_SAMPLES) {
                    adaptiveCurrentLow = false
                    Log.i(TAG, "Adaptive: → HIGH (${configuredOpusBitrate / 1000} kbps), avgRTT=$avgRtt ms")
                    opus.setBitrate(configuredOpusBitrate)
                }
            }
            else -> {
                // Hysterese-Zone: weder rauf noch runter. Counter zurücksetzen,
                // damit nur tatsächlich aufeinanderfolgende Werte zählen.
                adaptiveDownStreak = 0
                adaptiveUpStreak = 0
            }
        }
    }

    /** Aktueller Akku-Stand des eigenen Telefons in Prozent, oder -1 bei Fehler. */
    private fun readBatteryPercent(): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: run {
            // Fallback über Sticky-Broadcast für ältere Builds.
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        }
    } catch (_: Throwable) { -1 }

    private fun readLongBE(src: ByteArray): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (src[i].toLong() and 0xFF)
        return v
    }

    // -- DSP-Helfer ------------------------------------------------------------

    private fun frameDb(buf: ByteArray, n: Int): Double {
        if (n < 2) return -120.0
        var sum = 0.0
        var i = 0
        val end = (n / 2) * 2
        while (i < end) {
            val low = buf[i].toInt() and 0xFF
            val high = buf[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()
            sum += (sample * sample).toDouble()
            i += 2
        }
        val mean = sum / (end / 2)
        val linear = sqrt(mean) / 32768.0
        if (linear <= 1e-7) return -120.0
        return 20.0 * log10(linear)
    }

    private fun dbToVu(db: Double): Float {
        val pct = (db - DB_MIN_FS) / (DB_MAX_FS - DB_MIN_FS)
        return pct.toFloat().coerceIn(0f, 1f)
    }

    private class BiquadHpf(cutoffHz: Double, sampleRate: Double, q: Double) {
        private val b0: Double; private val b1: Double; private val b2: Double
        private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        init {
            val omega = 2.0 * Math.PI * cutoffHz / sampleRate
            val sinO = sin(omega)
            val cosO = cos(omega)
            val alpha = sinO / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosO) / 2.0) / a0
            b1 = -(1.0 + cosO) / a0
            b2 = ((1.0 + cosO) / 2.0) / a0
            a1 = (-2.0 * cosO) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun processInPlace(buf: ByteArray, n: Int) {
            var i = 0
            val end = (n / 2) * 2
            while (i < end) {
                val low = buf[i].toInt() and 0xFF
                val high = buf[i + 1].toInt()
                val x = ((high shl 8) or low).toShort().toDouble()
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x
                y2 = y1; y1 = y
                val out = y.toInt().coerceIn(-32768, 32767)
                buf[i] = (out and 0xFF).toByte()
                buf[i + 1] = ((out shr 8) and 0xFF).toByte()
                i += 2
            }
        }
    }

    // -- Audio-Routing ---------------------------------------------------------

    /**
     * Wählt das beste verfügbare Comm-Device für den Intercom-Betrieb.
     * Priorität (Mic + Speaker bevorzugt):
     *   1. BT-SCO (Helm-Headset, klassischer Hands-Free-Profil)
     *   2. BT-LE-Audio (Android 13+; LC3-fähige Headsets, halber Akku-Verbrauch)
     *   3. USB-Headset (USB-C-Kabel mit Mic)
     *   4. 3,5-mm-Klinkenheadset
     *   5. Hörgerät (TYPE_HEARING_AID)
     * Nichts gefunden → null (System nimmt Built-in-Mic + Earpiece/Speaker).
     */
    private fun pickBestCommDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val devices = audioManager.availableCommunicationDevices
        val priority = mutableListOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            priority += AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        priority += AudioDeviceInfo.TYPE_USB_HEADSET
        priority += AudioDeviceInfo.TYPE_WIRED_HEADSET
        priority += AudioDeviceInfo.TYPE_HEARING_AID
        for (type in priority) {
            devices.firstOrNull { it.type == type }?.let { return it }
        }
        return null
    }

    /**
     * Routet Audio auf das beste verfügbare Headset (Helm-BT, USB-/Klinken-Kabel),
     * fällt sonst auf System-Default (Built-in-Mic + Earpiece/Speaker) zurück.
     * Idempotent – kann vom AudioDeviceCallback bei Plug-Änderungen mehrfach
     * aufgerufen werden.
     */
    private fun routeAudioToBestDevice() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val pick = pickBestCommDevice()
            if (pick != null) {
                val ok = audioManager.setCommunicationDevice(pick)
                Log.i(TAG, "Comm-Device → ${deviceTypeName(pick.type)} (${pick.productName}) ok=$ok")
            } else {
                audioManager.clearCommunicationDevice()
                Log.i(TAG, "Comm-Device → kein Headset gefunden, System-Default (Built-in)")
            }
            return
        }

        // Android < 12: alte API. Wired-Headset wird vom System automatisch
        // geroutet, sobald MODE_IN_COMMUNICATION gesetzt ist. BT-SCO müssen
        // wir explizit anfordern.
        @Suppress("DEPRECATION") audioManager.isSpeakerphoneOn = false
        @Suppress("DEPRECATION")
        if (audioManager.isBluetoothScoAvailableOffCall) {
            @Suppress("DEPRECATION") audioManager.startBluetoothSco()
            @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = true
            Log.i(TAG, "Legacy: startBluetoothSco aktiviert")
        } else {
            Log.i(TAG, "Legacy: kein SCO – System-Default")
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT-SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BT-LE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Klinke-Headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hörgerät"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Hörmuschel"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Telefon-Lautsprecher"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Telefon-Mic"
        else -> "Type-$type"
    }

    /**
     * Beobachtet während der Verbindung Plug/Unplug-Events. Tipisches Szenario:
     * User stöpselt mitten auf der Tour das Klinken-Kabel ein → wir sollen
     * sofort darauf umrouten, ohne dass die Verbindung neu aufgebaut werden muss.
     */
    private fun registerDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (deviceCallback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                if (!running) return
                Log.i(TAG, "AudioDevicesAdded → reroute")
                routeAudioToBestDevice()
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                if (!running) return
                Log.i(TAG, "AudioDevicesRemoved → reroute")
                routeAudioToBestDevice()
            }
        }
        audioManager.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb
    }

    private fun unregisterDeviceCallback() {
        deviceCallback?.let {
            try { audioManager.unregisterAudioDeviceCallback(it) } catch (_: Throwable) {}
        }
        deviceCallback = null
    }

    private fun clearAudioRouting() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
        } catch (_: Throwable) {}
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_BYTES = 640
        private const val MAX_FRAME_PAYLOAD = 1024

        private const val TYPE_AUDIO: Byte = 0
        private const val TYPE_PING: Byte = 1
        private const val TYPE_PONG: Byte = 2
        private const val TYPE_STATUS: Byte = 3

        // Akku-Status alle 30 s mitsenden – häufig genug für Touren-Verlauf,
        // selten genug, um die Funkstrecke nicht zu belasten.
        private const val STATUS_INTERVAL_MS = 30_000L

        // Stale-Link-Detection (siehe heartbeat-Thread):
        //   - Einzelner PONG-Sample > 1,5 s ist ungewöhnlich (normale BT-Classic+SCO
        //     RTT liegt bei 200–500 ms). 3× in Folge = ~12 s bei 4-s-Heartbeat.
        //   - PONG-Timeout: 12 s ohne PONG, unabhängig davon ob Audio-Frames
        //     noch eintrudeln. Längere Schwelle wäre frustrierend, kürzere
        //     löst bei kurzen Spikes false positives aus.
        private const val STALE_RTT_THRESHOLD_MS = 1_500L
        private const val STALE_RTT_SAMPLES = 3
        private const val PONG_TIMEOUT_MS = 12_000L

        // Adaptive-Bitrate-Schwellen. Hysterese bewusst breit (400 ↔ 800 ms),
        // damit normale RTT-Schwankungen das Codec nicht ständig umstellen.
        // Asymmetrische Sample-Counts: 3 Samples runter (schnell schützen)
        // gegen 5 Samples hoch (vorsichtig zurück).
        private const val ADAPTIVE_RTT_HIGH_MS = 800L
        private const val ADAPTIVE_RTT_LOW_MS = 400L
        private const val ADAPTIVE_DOWN_SAMPLES = 3
        private const val ADAPTIVE_UP_SAMPLES = 5
        private const val ADAPTIVE_LOW_BITRATE_BPS = 16_000

        // Heartbeat seltener: spart Funkzeit für die eigentliche Sprache.
        // Konfigurierbar (2–10 s) über setHeartbeatIntervalMs.
        private const val HEARTBEAT_INTERVAL_DEFAULT_MS = 4_000L

        // (Frame-Gap-PLC-Konstanten in v1.5-beta3 entfernt – siehe Kommentar im
        // Playback-Thread. Opus’ interner Decoder-Jitter-Glättung reicht aus.)

        private const val DB_MIN_FS = -50.0
        private const val DB_MAX_FS = -5.0

        private const val HPF_CUTOFF_HZ = 150.0
        private const val HPF_Q = 0.707

        // Auto-Gate: 5 s Geschichte, +6 dB Headroom über dem laufenden Geräuschmin.
        private const val NOISE_FLOOR_FRAMES = 250
        private const val GATE_HEADROOM_DB = 6.0
        // Hardgrenze für die finale Schwelle (≈ Sicherheitsnetz, falls Floor falsch geschätzt).
        private const val VOICE_GATE_BASE_DB = -32.0
        private const val HANGOVER_FRAMES = 25

        private const val RTT_WINDOW = 8
    }
}
