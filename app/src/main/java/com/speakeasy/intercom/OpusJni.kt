package com.speakeasy.intercom

/**
 * Niedrige JNI-Brücke zu libopus. Kein State auf Kotlin-Seite – das Native-Handle
 * wird in [OpusCodec] gehalten.
 *
 * 16 kHz mono / 20 ms Frames (320 Samples = 640 Byte PCM Eingang).
 * Encoder läuft als VOIP / Wideband / VBR mit FEC + DTX, Decoder unterstützt PLC.
 */
internal object OpusJni {
    init {
        System.loadLibrary("speakeasy_opus")
    }

    @JvmStatic external fun nativeCreate(bitrate: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetBitrate(handle: Long, bitrate: Int): Int
    @JvmStatic external fun nativeGetBitrate(handle: Long): Int
    @JvmStatic external fun nativeSetPacketLossPercent(handle: Long, percent: Int): Int
    @JvmStatic external fun nativeEncode(
        handle: Long, pcm: ByteArray, pcmLen: Int,
        out: ByteArray, outCapacity: Int
    ): Int

    /** [encoded] = null + [encLen] = 0 → PLC-Pfad (Frame-Loss-Concealment). */
    @JvmStatic external fun nativeDecode(
        handle: Long, encoded: ByteArray?, encLen: Int,
        out: ByteArray, outCapacityBytes: Int,
        useFec: Boolean
    ): Int
}
