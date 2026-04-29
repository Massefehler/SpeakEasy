package com.speakeasy.intercom

import kotlin.math.sqrt

/**
 * Soft-AGC für 16-bit-PCM-Frames.
 *
 * Tracking-Idee:
 *  - RMS jedes Frames messen.
 *  - Solange das Signal über dem Geräusch-Boden liegt, einen Soll-Gain anstreben,
 *    der den RMS auf [TARGET_RMS] hebt.
 *  - Schnelles **Attack** (Gain runter bei Lautwerden, ~50 ms),
 *    langsames **Release** (Gain hoch bei Leiserwerden, ~500 ms),
 *    damit die Lautstärke nicht „pumpt".
 *  - Hard-Limiter am Ausgang verhindert Clipping.
 *
 * Wirkt VOR dem Codec-Encode auf die rohen PCM-Bytes. Das Wind-Filter-HPF läuft
 * davor, AGC sieht also nur den sprachrelevanten Anteil. AEC/NS sind in der
 * AudioRecord-Quelle bereits aktiv – AGC kommt zusätzlich obendrauf.
 *
 * Bewusst kein WebRTC-AGC oder native Lib, weil der Algorithmus simpel ist und
 * 16 kHz Mono in Kotlin <0,5 % CPU kostet.
 */
class Agc {

    @Volatile var enabled: Boolean = true

    /** Aktueller, geglätteter Verstärkungs-Faktor (1.0 = neutral). */
    private var gain = 1.0

    fun reset() { gain = 1.0 }

    fun process(pcm: ByteArray, n: Int) {
        if (!enabled) return
        if (n < 4) return

        // RMS in [0..1] auf den frischen Frame.
        var sum = 0.0
        var i = 0
        val end = (n / 2) * 2
        while (i < end) {
            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val s = ((high shl 8) or low).toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        val rms = sqrt(sum / (end / 2)) / 32768.0

        // Bei sehr leisen Frames keinen extremen Boost ansetzen – das würde nur
        // Geräusch-Boden hochziehen und das Mikrofon scheinbar rauschen lassen.
        val targetGain = if (rms < NOISE_FLOOR) {
            // Smooth Richtung 1.0 zurück, damit die nächste echte Sprache nicht
            // mit blocked Gain startet.
            1.0
        } else {
            (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        }

        // Attack/Release: schnell runter, langsam hoch (klassisches Compressor-Pattern).
        val coef = if (targetGain < gain) ATTACK else RELEASE
        gain = gain * (1.0 - coef) + targetGain * coef

        // Gain anwenden + Hard-Limit, um Clipping bei Sprach-Spitzen zu verhindern.
        val g = gain
        i = 0
        while (i < end) {
            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val s = ((high shl 8) or low).toShort().toInt()
            var out = (s * g).toInt()
            if (out > 32_767) out = 32_767
            else if (out < -32_768) out = -32_768
            pcm[i] = (out and 0xFF).toByte()
            pcm[i + 1] = ((out shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    companion object {
        // Ziel: -20 dBFS (= 0.1 linear). Headroom für Sprach-Spitzen, ohne dass
        // der Empfänger den Pegel unangenehm laut findet.
        private const val TARGET_RMS = 0.10
        // Faktor 0.5 (= -6 dB) bis Faktor 8 (= +18 dB). Mehr Boost bringt im
        // Real-Test nichts ausser Geräusch-Verstärkung.
        private const val MIN_GAIN = 0.5
        private const val MAX_GAIN = 8.0
        // Unter -50 dBFS = wahrscheinlich Stille/Mic-Rauschen, nicht boosten.
        private const val NOISE_FLOOR = 0.003
        // Per Frame (20 ms): Attack 0.2 → Zeit-Konstante ~80 ms,
        //                    Release 0.02 → ~1 s – fühlt sich natürlich an.
        private const val ATTACK = 0.2
        private const val RELEASE = 0.02
    }
}
