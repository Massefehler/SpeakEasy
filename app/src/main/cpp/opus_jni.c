// JNI-Brücke zu libopus.
// Erwartet: 16 kHz mono, 20 ms Frames (= 320 Samples = 640 Byte PCM).
// Encoder mit FEC + DTX aktiv, Decoder kann PLC bei Frame-Loss anwenden.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <opus.h>
#include <android/log.h>

#define LOG_TAG "OpusJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

#define SAMPLE_RATE   16000
#define CHANNELS      1
#define FRAME_SAMPLES 320  // 20 ms @ 16 kHz

// Wrapper-Struct: Encoder + Decoder gemeinsam, ein Handle pro Codec-Instanz.
typedef struct {
    OpusEncoder *enc;
    OpusDecoder *dec;
} OpusPair;

JNIEXPORT jlong JNICALL
Java_com_speakeasy_intercom_OpusJni_nativeCreate(JNIEnv *env, jclass clazz, jint bitrate) {
    int err = 0;
    OpusPair *p = (OpusPair*) calloc(1, sizeof(OpusPair));
    if (!p) return 0;

    p->enc = opus_encoder_create(SAMPLE_RATE, CHANNELS, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || !p->enc) {
        LOGE("opus_encoder_create failed: %d", err);
        free(p);
        return 0;
    }

    // Bitrate, FEC, DTX – Standardwerte für stabile Sprache über instabile Funk.
    opus_encoder_ctl(p->enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(p->enc, OPUS_SET_VBR(1));               // VBR an
    opus_encoder_ctl(p->enc, OPUS_SET_VBR_CONSTRAINT(0));    // unconstrained VBR
    opus_encoder_ctl(p->enc, OPUS_SET_COMPLEXITY(5));        // 0–10, 5 = guter Trade-off
    opus_encoder_ctl(p->enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(p->enc, OPUS_SET_INBAND_FEC(1));        // FEC für Paketverlust
    opus_encoder_ctl(p->enc, OPUS_SET_PACKET_LOSS_PERC(10)); // 10 % erwarteter Loss
    opus_encoder_ctl(p->enc, OPUS_SET_DTX(1));               // Discontinuous TX
    opus_encoder_ctl(p->enc, OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND));

    p->dec = opus_decoder_create(SAMPLE_RATE, CHANNELS, &err);
    if (err != OPUS_OK || !p->dec) {
        LOGE("opus_decoder_create failed: %d", err);
        opus_encoder_destroy(p->enc);
        free(p);
        return 0;
    }

    LOGI("Opus init: %d Hz mono, %d kbps, FEC+DTX an", SAMPLE_RATE, bitrate / 1000);
    return (jlong)(intptr_t) p;
}

JNIEXPORT void JNICALL
Java_com_speakeasy_intercom_OpusJni_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    OpusPair *p = (OpusPair*)(intptr_t) handle;
    if (!p) return;
    if (p->enc) opus_encoder_destroy(p->enc);
    if (p->dec) opus_decoder_destroy(p->dec);
    free(p);
}

JNIEXPORT jint JNICALL
Java_com_speakeasy_intercom_OpusJni_nativeSetBitrate(JNIEnv *env, jclass clazz, jlong handle, jint bitrate) {
    OpusPair *p = (OpusPair*)(intptr_t) handle;
    if (!p || !p->enc) return -1;
    return opus_encoder_ctl(p->enc, OPUS_SET_BITRATE(bitrate));
}

JNIEXPORT jint JNICALL
Java_com_speakeasy_intercom_OpusJni_nativeGetBitrate(JNIEnv *env, jclass clazz, jlong handle) {
    OpusPair *p = (OpusPair*)(intptr_t) handle;
    if (!p || !p->enc) return -1;
    opus_int32 br = 0;
    if (opus_encoder_ctl(p->enc, OPUS_GET_BITRATE(&br)) != OPUS_OK) return -1;
    return (jint) br;
}

JNIEXPORT jint JNICALL
Java_com_speakeasy_intercom_OpusJni_nativeSetPacketLossPercent(JNIEnv *env, jclass clazz, jlong handle, jint percent) {
    OpusPair *p = (OpusPair*)(intptr_t) handle;
    if (!p || !p->enc) return -1;
    return opus_encoder_ctl(p->enc, OPUS_SET_PACKET_LOSS_PERC(percent));
}

// Encode: PCM-Bytes (Little-Endian S16) → Opus-Bitstream.
// Rückgabe: codierte Byte-Anzahl, oder negativ bei Fehler. 0 oder 1 = DTX-Stille.
JNIEXPORT jint JNICALL
Java_com_speakeasy_intercom_OpusJni_nativeEncode(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray pcm, jint pcmLen,
        jbyteArray out, jint outCapacity) {
    OpusPair *p = (OpusPair*)(intptr_t) handle;
    if (!p || !p->enc) return -1;
    if (pcmLen != FRAME_SAMPLES * 2) return -2;

    jbyte *pcmBuf = (*env)->GetByteArrayElements(env, pcm, NULL);
    jbyte *outBuf = (*env)->GetByteArrayElements(env, out, NULL);
    if (!pcmBuf || !outBuf) {
        if (pcmBuf) (*env)->ReleaseByteArrayElements(env, pcm, pcmBuf, JNI_ABORT);
        if (outBuf) (*env)->ReleaseByteArrayElements(env, out, outBuf, JNI_ABORT);
        return -3;
    }

    int n = opus_encode(p->enc,
                        (const opus_int16*) pcmBuf, FRAME_SAMPLES,
                        (unsigned char*) outBuf, outCapacity);

    (*env)->ReleaseByteArrayElements(env, pcm, pcmBuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outBuf, n > 0 ? 0 : JNI_ABORT);
    return n;
}

// Decode: Opus-Bytes → PCM. Bei encoded == NULL oder encLen == 0 wird PLC genutzt
// (Frame-Loss-Concealment), das interpoliert sauber über kurze Aussetzer.
JNIEXPORT jint JNICALL
Java_com_speakeasy_intercom_OpusJni_nativeDecode(
        JNIEnv *env, jclass clazz, jlong handle,
        jbyteArray encoded, jint encLen,
        jbyteArray out, jint outCapacityBytes,
        jboolean useFec) {
    OpusPair *p = (OpusPair*)(intptr_t) handle;
    if (!p || !p->dec) return -1;
    if (outCapacityBytes < FRAME_SAMPLES * 2) return -2;

    jbyte *encBuf = NULL;
    if (encoded != NULL && encLen > 0) {
        encBuf = (*env)->GetByteArrayElements(env, encoded, NULL);
        if (!encBuf) return -3;
    }
    jbyte *outBuf = (*env)->GetByteArrayElements(env, out, NULL);
    if (!outBuf) {
        if (encBuf) (*env)->ReleaseByteArrayElements(env, encoded, encBuf, JNI_ABORT);
        return -4;
    }

    int n = opus_decode(p->dec,
                        encBuf ? (const unsigned char*) encBuf : NULL,
                        encBuf ? encLen : 0,
                        (opus_int16*) outBuf, FRAME_SAMPLES,
                        useFec ? 1 : 0);

    if (encBuf) (*env)->ReleaseByteArrayElements(env, encoded, encBuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, outBuf, n > 0 ? 0 : JNI_ABORT);

    if (n < 0) return n;
    return n * 2; // Samples → Bytes
}
