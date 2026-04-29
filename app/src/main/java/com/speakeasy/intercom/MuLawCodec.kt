package com.speakeasy.intercom

/**
 * G.711 μ-law: 8-bit logarithmische Kompression, halbiert die Bandbreite gegenüber
 * 16-bit PCM bei nahezu identischer Sprachqualität (Telefonie-Standard).
 *
 * Eingang: 16-bit PCM mono
 * Ausgang: 1 Byte pro Sample
 */
class MuLawCodec : Codec {
    override val pcmFrameBytes = 640
    override val maxEncodedBytes = 320
    override val name = "μ-law"

    override fun encode(pcm: ByteArray, pcmLen: Int, out: ByteArray): Int {
        val samples = pcmLen / 2
        var i = 0
        while (i < samples) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = pcm[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            out[i] = encodeSample(sample)
            i++
        }
        return samples
    }

    override fun decode(encoded: ByteArray, encLen: Int, out: ByteArray): Int {
        var i = 0
        while (i < encLen) {
            val sample = DECODE_TABLE[encoded[i].toInt() and 0xFF].toInt()
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            i++
        }
        return encLen * 2
    }

    /** Standard-G.711-Algorithmus (Wikipedia: μ-law algorithm). */
    private fun encodeSample(pcm: Short): Byte {
        var sample = pcm.toInt()
        val sign = if (sample < 0) 0x80 else 0
        if (sign != 0) sample = -sample
        if (sample > CLIP) sample = CLIP
        sample += BIAS
        var exp = 7
        var mask = 0x4000
        while ((sample and mask) == 0 && exp > 0) {
            exp--
            mask = mask shr 1
        }
        val mantissa = (sample shr (exp + 3)) and 0x0F
        return ((sign or (exp shl 4) or mantissa).inv() and 0xFF).toByte()
    }

    companion object {
        private const val CLIP = 32635
        private const val BIAS = 0x84

        // Decoding-Lookup-Tabelle (256 Einträge) – einmalig berechnet, dann konstant.
        private val DECODE_TABLE = ShortArray(256) { i ->
            val u = i.inv() and 0xFF
            val sign = u and 0x80
            val exp = (u shr 4) and 0x07
            val man = u and 0x0F
            var sample = ((man shl 3) + BIAS) shl exp
            sample -= BIAS
            if (sign != 0) sample = -sample
            sample.toShort()
        }
    }
}
