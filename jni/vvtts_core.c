/*
 * vvtts_core.c -- JNI bridge between the VvTts Kotlin port and the openevv
 * Eloquence engine (IBM's embedded ViaVoice / ETI Eloquence reimplemented in
 * portable C, MIT). Replaces the former bridge that dlopened the converted
 * Apple libeci object and reached into it by vtable offset.
 *
 * The Kotlin contract (VvttsCore.kt / EloquenceEngine.kt) is preserved
 * byte for byte:
 *   - eight natives, same signatures
 *   - PCM signed 16-bit mono at 11,025 Hz (eciSampleRate value 1)
 *   - dialect ids are IBM ECILanguageDialect constants (0x10000 = en-US)
 *   - voice parameters 0..7 (gender, head, pitch base, fluctuation,
 *     roughness, breathiness, speed, volume) applied to voice 0, the
 *     engine's active voice
 *
 * The engine reads no file at run time and wants nothing but libm and
 * pthreads, so this library is self-contained for the APK.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "eci.h"
#include "chs_oracle_synth.h"

/* The engine's own text layer (upstream cli/probe.c path): the eci*
 * shims for add/synthesize are inert, so call the et_* entry points
 * directly.  The handle is opaque (OldInst* in the engine); void* is
 * ABI-compatible. */
extern int et_insertIndex(void *h, long index);
extern int et_addText(void *h, const char *text);
extern int et_synthesize(void *h);

#define APP_SAMPLES 4096

typedef struct {
    ECIHand hECI;
    short   chunk[APP_SAMPLES]; /* callback scratch */
    short  *pcm;                /* accumulated waveform */
    size_t  pcmLen, pcmCap;
    void   *text;               /* text kept alive for the engine's thread */
    int     synthBusy;
} VvtsSession;

/* The engine calls back on its own synthesis thread; the caller only reads
 * s->pcm after waiting for eciSpeaking to go quiet, so no locking is needed
 * as long as one session is never driven from two threads at once (the
 * Kotlin side already serialises with its synthesis lock). */
static int vv_cb(ECIHand h, ECIMessage message, int param, void *data) {
    VvtsSession *s = (VvtsSession *)data;
    if (message != eciWaveformBuffer)
        return eciDataProcessed;
    if (param < 0) return eciDataProcessed;
    {
        size_t n = (size_t)param;
        if (n > APP_SAMPLES) n = APP_SAMPLES;
        if (s->pcmLen + n > s->pcmCap) {
            size_t cap = s->pcmCap ? s->pcmCap : 16384;
            while (cap < s->pcmLen + n) cap *= 2;
            short *p = (short *)realloc(s->pcm, cap * sizeof(short));
            if (!p) return eciDataProcessed; /* drop rather than lose the session */
            s->pcm = p;
            s->pcmCap = cap;
        }
        memcpy(s->pcm + s->pcmLen, s->chunk, n * sizeof(short));
        s->pcmLen += n;
    }
    return eciDataProcessed;
}

static void vv_wait_till_done(VvtsSession *s) {
    /* openevv's engine cannot abandon an utterance: eciStop stops the
     * samples but the synthesis thread still has to finish it.  Poll
     * eciSpeaking (as the old bridge did for Apple's object). */
    for (int i = 0; i < 8000 && s->hECI && eciSpeaking(s->hECI); i++) {
        struct timespec ts = {0, 2000000L}; /* 2 ms */
        nanosleep(&ts, NULL);
    }
    s->synthBusy = 0;
}

static VvtsSession *vv_find(JNIEnv *env, jlong handle) {
    return (handle == 0) ? NULL : (VvtsSession *)(intptr_t)handle;
}

/* Dialect whitelist: only the language modules linked into this build
 * (build_native.sh LANGS) may be instantiated.  eo_newEx builds its voice
 * table by indexing a static structure from the FAMILY number of the
 * requested dialect; for a family that has no module in this build the
 * structure shape is different, so the engine walks garbage and SIGSEGVs
 * (observed with 0x60000/zh-CN on a build without lang/chs).  Reject
 * unknown dialects up front so the caller gets a clean NULL handle instead
 * of a killed process -- this is what keeps an accidental wrong-language
 * request from ever crashing the app (or TalkBack's TTS session). */
static int vv_dialect_shipped(int32_t dialect) {
    switch (dialect) {
    case 0x10000: case 0x10001:  /* enus, engb */
    case 0x20000: case 0x20001: case 0x20002:  /* eses, esus, esmx */
    case 0x30000: case 0x30001:  /* frfr, frca */
    case 0x40000:                /* dede */
    case 0x50000:                /* itit */
    case 0x80000:                /* jajp */
    case 0x110000:               /* plpl */
    case 0x60000:                /* chs -- linked; synthesis is oracle-fed */
        return 1;
    default:
        return 0;
    }
}

/* Clinch: pitch range.  openevv voice params are 0..100 for pitch baseline,
 * the Kotlin preset table sends 40..120 (Apple's range); clamp at the bridge
 * so we never hand the engine a value it does not understand. */
static int vv_clamp_voice_param(int param, int value) {
    switch (param) {
    case eciPitchBaseline:
        if (value < 0) return 0;
        if (value > 100) return 100;
        return value;
    case eciSpeed:
        if (value < 0) return 0;
        if (value > 250) return 250;   /* openevv: 0..250 */
        return value;
    default:
        if (value < 0) return 0;
        if (value > 100) return 100;
        return value;
    }
}

JNIEXPORT jlong JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeInitEngine(
        JNIEnv *env, jclass cls, jstring configDir, jstring libDir, jint dialect) {
    VvtsSession *s = (VvtsSession *)calloc(1, sizeof(VvtsSession));
    if (!s) return 0;
    /* configDir and libDir are legacy from the Apple-libs era; openevv
     * wants nothing from the filesystem at run time. */
    if (!vv_dialect_shipped((int)dialect)) {
        /* Missing language module in this build: refuse instead of letting
         * eciNewEx walk the unbuilt voice table and segfault. */
        free(s);
        return 0;
    }
    s->hECI = eciNewEx((int)dialect);
    if (!s->hECI) {
        free(s);
        return 0;
    }
    eciRegisterCallback(s->hECI, vv_cb, s);
    eciSetOutputBuffer(s->hECI, APP_SAMPLES, s->chunk);  /* callback first: engine
                                                           * refuses a buffer until it has
                                                           * somewhere to report samples */
    eciSetParam(s->hECI, eciSampleRate, 1); /* 11,025 Hz, the app's rate */
    return (jlong)(intptr_t)s;
}

JNIEXPORT jshortArray JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSynthesize(
        JNIEnv *env, jclass cls, jlong handle, jint dialect,
        jbyteArray text, jint charsetId, jstring outPath) {
    VvtsSession *s = vv_find(env, handle);
    if (!s || !s->hECI) return NULL;
    if (!text) return NULL;

    jsize len = (*env)->GetArrayLength(env, text);
    if (len <= 0) return NULL;

    void *buf = malloc((size_t)len + 1);
    if (!buf) return NULL;
    (*env)->GetByteArrayRegion(env, text, 0, len, (jbyte *)buf);
    ((char *)buf)[len] = '\0';

    /* charsetId describes how the byte array was encoded by the Kotlin
     * side (1252 for Western, GB18030 for zh).  openevv reads the caller's
     * bytes as the language module's own byte set, which is the Windows
     * Western set for the shipped languages -- matching the 1252 path the
     * app already uses.  The engine copies what it needs of the text, but
     * we keep it alive for the length of the call to be safe. */
    if (s->text) free(s->text);
    s->text = buf;
    s->pcmLen = 0;

    if (dialect == 0x60000) {
        /* Chinese: the engine's chs rules are unbuilt stubs, so synthesize
         * from the oracle bank instead -- raw PCM keyed by the GB18030 bytes
         * the app already sends (charsetId == CHARSET_GBK).  No engine call,
         * no synthesis thread; the session still owns the buffer. */
        short *pcm = NULL;
        size_t samples = chs_build_pcm((const unsigned char *)buf, (size_t)len, &pcm);
        if (samples == 0) return NULL;
        free(s->pcm);
        s->pcm = pcm;
        s->pcmLen = samples;
        s->pcmCap = samples;
        jshortArray out = (*env)->NewShortArray(env, (jsize)samples);
        if (!out) return NULL;
        (*env)->SetShortArrayRegion(env, out, 0, (jsize)samples, pcm);
        return out;
    }

    eciClearInput(s->hECI);
    et_insertIndex(s->hECI, 4242);   /* upstream cli probe's index */
    et_addText(s->hECI, buf);
    s->synthBusy = 1;
    et_synthesize(s->hECI);
    vv_wait_till_done(s);

    if (s->pcmLen == 0) return NULL;
    {
        jshortArray out = (*env)->NewShortArray(env, (jsize)s->pcmLen);
        if (!out) return NULL;
        (*env)->SetShortArrayRegion(env, out, 0, (jsize)s->pcmLen, s->pcm);
        return out;
    }
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetVoiceParam(
        JNIEnv *env, jclass cls, jlong handle, jint voice, jint param, jint value) {
    VvtsSession *s = vv_find(env, handle);
    if (!s || !s->hECI) return -1;
    return eciSetVoiceParam(s->hECI, voice, param,
                            vv_clamp_voice_param(param, value));
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeGetVoiceParam(
        JNIEnv *env, jclass cls, jlong handle, jint voice, jint param) {
    VvtsSession *s = vv_find(env, handle);
    if (!s || !s->hECI) return -1;
    return eciGetVoiceParam(s->hECI, voice, param);
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(
        JNIEnv *env, jclass cls, jlong handle, jint param, jint value) {
    VvtsSession *s = vv_find(env, handle);
    if (!s || !s->hECI) return -1;
    return eciSetParam(s->hECI, param, value);
}

/* The app's presets are numbered like the old Apple CSV (which skips 5);
 * openevv's voices are 1..8 in the same order -- Reed, Shelley, Sandy,
 * Rocko, Flo, Grandma, Grandpa, Eddy.  Copy the chosen preset onto voice 0
 * (the active voice the Kotlin side then tunes by voice params). */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetStandardVoice(
        JNIEnv *env, jclass cls, jlong handle, jint voiceNumber) {
    VvtsSession *s = vv_find(env, handle);
    if (!s || !s->hECI) return -1;
    {
        int from = voiceNumber;
        if (from < 1) from = 1;
        if (from > 8) from = 8;
        return eciCopyVoice(s->hECI, from, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeStop(
        JNIEnv *env, jclass cls, jlong handle) {
    VvtsSession *s = vv_find(env, handle);
    if (!s || !s->hECI) return;
    eciStop(s->hECI); /* stops handing samples; the thread still settles */
}

JNIEXPORT void JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeShutdown(
        JNIEnv *env, jclass cls, jlong handle) {
    VvtsSession *s = vv_find(env, handle);
    if (!s) return;
    if (s->hECI) {
        eciStop(s->hECI);
        /* let the synthesis thread finish its current utterance before we
         * free the session it is still pointing at */
        for (int i = 0; i < 8000 && eciSpeaking(s->hECI); i++) {
            struct timespec ts = {0, 2000000L};
            nanosleep(&ts, NULL);
        }
        eciDelete(s->hECI);
    }
    free(s->text);
    free(s->pcm);
    free(s);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}