package com.speakeasy.intercom

import android.util.Log

/**
 * Opus-Codec über libopus 1.5.x via JNI.
 *
 * - 16 kHz mono, 20 ms Frames (640 Byte PCM ein).
 * - VBR @ ~24 kbps (Default), VOIP-Profil, Wideband, FEC + DTX an.
 * - Decoder kann PLC: bei Frame-Loss [decodePlc] aufrufen, dann interpoliert
 *   Opus eine Lücke statt eines Knacks.
 *
 * Bandbreite gegenüber μ-law: ~6× weniger (16 kbps ≈ 2 KB/s vs. 16 KB/s μ-law).
 * Stabilität gegen Funk-Spikes: deutlich besser durch In-Band-FEC und PLC.
 */
class OpusCodec(
    private val bitrate: Int = DEFAULT_BITRATE,
    private val packetLossPercent: Int = DEFAULT_LOSS_PERCENT,
) : Codec {

    override val pcmFrameBytes = 640                  // 320 Samples × 2 Byte
    override val maxEncodedBytes = MAX_PACKET_BYTES   // sicheres Polster bei Burst-Frames
    override val name: String get() = "Opus ${actualBitrateKbps}k"

    private var handle: Long = 0L

    init {
        handle = OpusJni.nativeCreate(bitrate)
        if (handle == 0L) {
            Log.e(TAG, "Opus-Init fehlgeschlagen, Codec-Instanz unbenutzbar")
        } else {
            OpusJni.nativeSetPacketLossPercent(handle, packetLossPercent.coerceIn(0, 30))
        }
    }

    override fun encode(pcm: ByteArray, pcmLen: Int, out: ByteArray): Int {
        if (handle == 0L) return -1
        return OpusJni.nativeEncode(handle, pcm, pcmLen, out, out.size)
    }

    override fun decode(encoded: ByteArray, encLen: Int, out: ByteArray): Int {
        if (handle == 0L) return -1
        return OpusJni.nativeDecode(handle, encoded, encLen, out, out.size, false)
    }

    /**
     * Vom AudioEngine-Playback-Thread aufzurufen, wenn ein Frame fehlt
     * (z. B. nach kurzem Funkaussetzer). Liefert PCM-Bytes für genau einen
     * 20-ms-Frame, vom Opus-PLC interpoliert.
     */
    fun decodePlc(out: ByteArray): Int {
        if (handle == 0L) return -1
        return OpusJni.nativeDecode(handle, null, 0, out, out.size, false)
    }

    /** Aktuelle Encoder-Bitrate in bps (kann durch VBR von [bitrate] abweichen). */
    val actualBitrate: Int
        get() = if (handle == 0L) 0 else OpusJni.nativeGetBitrate(handle)

    val actualBitrateKbps: Int
        get() = (actualBitrate + 500) / 1000

    /**
     * Setzt die Encoder-Bitrate zur Laufzeit (kein Reinit nötig). Für Adaptive
     * Bitrate gedacht: bei schlechter Funkstrecke runter, bei guter wieder hoch.
     */
    fun setBitrate(bps: Int) {
        if (handle == 0L) return
        OpusJni.nativeSetBitrate(handle, bps)
    }

    override fun close() {
        if (handle != 0L) {
            OpusJni.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "OpusCodec"
        // 24 kbps reicht für gute Sprachqualität bei 16 kHz / Wideband-VOIP.
        const val DEFAULT_BITRATE = 24_000
        // 10 % Loss-Annahme passt zur typischen BT-Classic+SCO-Funkqualität.
        const val DEFAULT_LOSS_PERCENT = 10
        // Opus erlaubt theoretisch bis 1275 Byte / Frame; 400 ist großzügig
        // für 20 ms @ 64 kbps Spitzen.
        private const val MAX_PACKET_BYTES = 400
    }
}
