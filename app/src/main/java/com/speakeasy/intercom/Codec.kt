package com.speakeasy.intercom

/**
 * Audio-Codec-Abstraktion. Aktuell ist [PcmCodec] aktiv (Roh-PCM 16 kHz mono),
 * [OpusCodec] ist als Skelett vorbereitet und wird ab Beta 1.4 aktiviert,
 * sobald die NDK-Lib eingebunden ist.
 */
interface Codec {
    /** Eingangs-Frame-Länge in Bytes (PCM 16-bit mono). */
    val pcmFrameBytes: Int
    /** Worst-Case-Größe eines codierten Frames. */
    val maxEncodedBytes: Int
    /** Kurzname für Diagnose-Anzeige. */
    val name: String

    /** Codiert einen PCM-Frame, gibt die Anzahl produzierter Bytes zurück. */
    fun encode(pcm: ByteArray, pcmLen: Int, out: ByteArray): Int

    /** Decodiert einen empfangenen Frame in PCM, gibt die Anzahl PCM-Bytes zurück. */
    fun decode(encoded: ByteArray, encLen: Int, out: ByteArray): Int

    fun close() {}
}

/** Kein Codec – PCM unverändert weitergeben. */
class PcmCodec : Codec {
    override val pcmFrameBytes = 640
    override val maxEncodedBytes = 640
    override val name = "PCM"

    override fun encode(pcm: ByteArray, pcmLen: Int, out: ByteArray): Int {
        System.arraycopy(pcm, 0, out, 0, pcmLen)
        return pcmLen
    }

    override fun decode(encoded: ByteArray, encLen: Int, out: ByteArray): Int {
        System.arraycopy(encoded, 0, out, 0, encLen)
        return encLen
    }
}
