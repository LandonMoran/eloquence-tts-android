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
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
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

/* ------------------------------------------------------------------ */
/* 4x polyphase windowed-sinc resampler (11,025 Hz ->  44,100 Hz(.
 *  Zero-dependency (libm only(, Kaiser-windowed sinc anti-aliasing
 *  low-pass with a ~5.5 kHz cutoff, 64 taps per phase.  The engine
 *  itself stays at its native 11,025 Hz (eciSampleRate =  1(; this
 *  block is the single place the 44.1k upsampling happens, right before
 *  the PCM is handed to the Java layer.  (Resampling approach reviewed
 *  against the NVDA-IBMTTS-Driver: that driver leaves the rate to the synth;
 *  quality upsample belongs in the audio path, so we do it here.(
 */
#define VV_RSP_TAPS 64        /* taps per polyphase branch */
#define VV_RSP_PHASES 4      /* upsampling factor */
#define VV_RSP_HALF (VV_RSP_TAPS / 2)
#define VV_RSP_CUTOFF 0.498f  /* ~5500 Hz / 11025 Hz (cycles per input sample; sinc t is in input-sample units) */
#define VV_RSP_BETA 8.0f    /* Kaiser window shape (~50 dB stopband( */

/* Modified Bessel I0 (series, 15 terms plenty for x <=  16(. */
static float vv_rsp_bessel_i0(float x) {
    float sum =  1.0f, term =  1.0f;
    for (int k =  1; k <=  15; k++) {
        term *= (x / (2.0f * k)) * (x / (2.0f * k));
        sum += term;
        if (term <  1e-12f) break;
    }
    return sum;
}

/* Static polyphase coefficient table, built once (thread-safe via a
 * simple guard -- the Android app synthesizes from one serialized worker, so
 * a mutex would be overkill;the guard just keeps double-init race-free
 * for the two-session warm-up path. */
static float vv_rsp_coeff[VV_RSP_PHASES][VV_RSP_TAPS];
static int  vv_rsp_ready =  0;

static void vv_rsp_build(void) {
    const float L2 = (float)VV_RSP_HALF;
    const float fc = VV_RSP_CUTOFF;
    for (int p =  0; p < VV_RSP_PHASES; p++) {
        float sum =  0.0f;
        for (int k =  0; k < VV_RSP_TAPS; k++) {
                    /* fractional position of this tap within the prototype:  taps
                     * sit at t = (k - L2) - (p/4), so phase p advances the output
                     * grid by p/4 sample.  Window spans
                     * t/L2 in [-1,1]. */
                    float t = ((float)k - L2) - ((float)p / (float)VV_RSP_PHASES);
                    float s = t / L2;
                                        if (s >  1.0f) s =  1.0f; else if (s < -1.0f) s = -1.0f;
                                        float w = vv_rsp_bessel_i0(VV_RSP_BETA * (float)sqrt(1.0f - s * s)) / vv_rsp_bessel_i0(VV_RSP_BETA);
                    float sinc = (t ==  0.0f) ? (float)(2.0 * 3.14159265358979323846 * fc) :
                                          (float)(sin(2.0 * 3.14159265358979323846 * fc * t) / t);
                    vv_rsp_coeff[p][k] = sinc * w;
                    sum += vv_rsp_coeff[p][k];
                }
        /* Normalize: preserve unity DC gain per phase (polyphase upsample
         * must not change overall loudness(. */
        for (int k =  0; k < VV_RSP_TAPS; k++)
            vv_rsp_coeff[p][k] /= sum;
    }
    vv_rsp_ready =  1;
}

/* Resample a signed 16-bit mono buffer 4x.  Returns  0 on success
 * with *out allocated (caller frees( and *outn =  4*in_n;  -1 on alloc failure. */
static int vv_resample_4x(const short *in, size_t in_n, short **out, size_t *outn) {
    if (in_n == 0) { *outn =  0; return 0; }
    if (!vv_rsp_ready) vv_rsp_build();

    short *zbuf = (short *)calloc(in_n +  2 * VV_RSP_HALF, sizeof(short));  /* zero padding both sides */
    if (!zbuf) return -1;
    memcpy(zbuf + VV_RSP_HALF, in, in_n * sizeof(short));

    size_t out_n = in_n * VV_RSP_PHASES;
    short *o = (short *)malloc(out_n * sizeof(short));
    if (!o) { free(zbuf); return -1; }

    for (size_t n =  0; n < out_n; n++) {
        const size_t i = n >>  2;          /* source sample index */
        const int   p = (int)(n &     3);        /* polyphase branch */
        float acc =  0.0f;
        const short *src = zbuf + VV_RSP_HALF + i;   /* centered on in[i] */
        for (int k =  0; k < VV_RSP_TAPS; k++) {
            /* h[p][k] pairs with input sample at offset (k - 32( from the
             * center (in[i]:: the coefficient table was built symmetric, so
             * the sweep below covers that exact stereo window. */
            acc += vv_rsp_coeff[p][k] * (float)src[k - VV_RSP_HALF];
        }
        /* Round-to-nearest with 16-bit clipping. */
        float v = acc;
        if (v >  32767.0f) v =  32767.0f;
        else if (v < -32768.0f) v = -32768.0f;
        o[n] = (short)(v >=  0.0f ? v +  0.5f : v -  0.5f);
    }
    free(zbuf);
    *out = o;
    *outn = out_n;
    return  0;
}
/* ------------------------------------------------------------------ */

typedef struct {
    ECIHand hECI;
    short   chunk[APP_SAMPLES]; /* callback scratch */
    short  *pcm;                /* accumulated waveform */
    size_t  pcmLen, pcmCap;
    void   *text;               /* text kept alive for the engine's thread */
    int     synthBusy;
    volatile int cancel; /* set by stop/shutdown to abort the wait loop fast */
    int     failed;      /* engine-state-invalidation flag (see vv_fail_session( */
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
     * eciSpeaking (as the old bridge did for Apple's object.. */
    __android_log_print(ANDROID_LOG_INFO, "SPD", "wait_entry pcm=%zu", s->pcmLen);
    for (int i =  0; i < 4000 && s->hECI && eciSpeaking(s->hECI) && !s->cancel; i++) {
        if ((i % 100) ==  0)
            __android_log_print(ANDROID_LOG_INFO, "SPD", "wait i=%d pcm=%zu", i, s->pcmLen);
        /* Polling is what collects the engine's queued samples: the engine
         * posts chunks into the app queue and only a poll delivers them, so
         * this loop's SLEEP is a hard ceiling on the drain rate.  Keep it
         * short -- 500us -- or long utterances take seconds to come out. */
        struct timespec ts = {0, 500000L}; /* 0.5 ms */
        nanosleep(&ts, NULL);
    }
    __android_log_print(ANDROID_LOG_INFO, "SPD", "wait_exit i_done pcm=%zu", s->pcmLen);
    s->synthBusy =  0;
}

/* vv_settle: bounded poll used when re-entering synthesis right after a
 * stop aborted the wait loop -- eciStop stops handing samples but the
 * engine's own thread still has to finish; one session must never be driven
 * from two threads concurrently, so wait for the engine to go quiet before
 * touching text/pcm again. */
static void vv_settle(VvtsSession *s) {
    for (int i = 0; i < 4000 && s->hECI && eciSpeaking(s->hECI) && !s->cancel; i++) {
        struct timespec ts = {0, 500000L}; /* 0.5 ms: poll = collect, keep it short */
        nanosleep(&ts, NULL);
    }
}

/* The engine emits short lead-in/trailing silence around every utterance;
 * for short TalkBack labels those fixed pauses dominate the perceived swipe
 * latency, so trim them before resampling.  A >-33 dBFS sample stops the
 * scan, so real speech is never eaten; near-empty blips are left alone. */
static void vv_trim_silence(short *pcm, size_t *pn) {
    size_t n = *pn;
    if (n < 32) return;
    const int TH = 700;
    size_t cap = (n < 2 * 11025) ? n : (2 * 11025);
    size_t lead = 0;
    while (lead < n && lead < cap && pcm[lead] > -TH && pcm[lead] < TH) lead++;
    if (n - lead < 16) return;
    size_t tail = 0;
    while (tail < n - 1 && tail < cap && pcm[n - 1 - tail] > -TH && pcm[n - 1 - tail] < TH) tail++;
    size_t keep = n - lead - tail;
    if (keep < 16) return;
    for (size_t k = 0; k < keep; k++) pcm[k] = pcm[lead + k];
    *pn = keep;
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

/* ------------------------------------------------------------------ */
/* A failed / JNI-exception synthesis path can leave the engine input/PCM
 * state undefined: mark the session failed so no later call trusts it,, and
 * answer the empty-array sentinel ( a 0-length PCM array( so THE
 * Kotlin wrapper can distinguish "engine state invalidated" from the ordinary
 * "no audio" null,and drop/rebuild its cached handle.  (Contract: null
 * = no audio,, non-empty = PCM,, empty array = engine state poisoned(. */
static void vv_fail_session(VvtsSession *s) {
    /* Poisoned sessions keep hECI alive: shutdown must still be able to
     * eciStop/eciDelete it; only the reusable state is torn down here, so
     * a later call on the same handle answers the sentinel instead. */
    s->failed = 1;
    s->pcmLen = 0;
    s->synthBusy = 0;
}

static jshortArray vv_empty_result(JNIEnv *env) {
    jshortArray out = (*env)->NewShortArray(env,  0);
    if ((*env)->ExceptionCheck(env)) {   /* OOME even on a 0-len array */
        (*env)->ExceptionClear(env);
        return NULL;
    }
    return out;
}
/* ------------------------------------------------------------------ */

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

    /* A stop may have aborted the previous wait loop: re-sync with the
     * engine's thread before we touch text/pcm for the new utterance. */
    s->cancel = 0;
    vv_settle(s);

    if (dialect == 0x60000) {
        /* Chinese: the engine's chs rules are unbuilt stubs, so synthesize
         * from the oracle bank instead -- raw PCM keyed by the GB18030 bytes
         * the app already sends (charsetId == CHARSET_GBK).  No engine call,
         * no synthesis thread; the session still owns the buffer. */
        __android_log_print(ANDROID_LOG_INFO, "CHS_ORACLE", "synth len=%d charset=%d first=%02x%02x%02x%02x",
                            (int)len, charsetId,
                            len > 0 ? (unsigned char)((char *)buf)[0] : 0,
                            len > 1 ? (unsigned char)((char *)buf)[1] : 0,
                            len > 2 ? (unsigned char)((char *)buf)[2] : 0,
                            len > 3 ? (unsigned char)((char *)buf)[3] : 0);
        short *pcm = NULL;
        size_t samples = chs_build_pcm((const unsigned char *)buf, (size_t)len, &pcm);
        __android_log_print(ANDROID_LOG_INFO, "CHS_ORACLE", "build_pcm samples=%zu pcm=%p", samples, (void *)pcm);
        if (samples == 0) return NULL;
        /* Oracle clips are captured at the engine's native 11.025 kHz (the
         * same rate eci.ini fixed the en-us path at(.  Playback always runs
         * at 44.1 kHz (SAMPLE_RATE(, so zh must take the same 4x
         * upsampling as the engine branch below -- else Chinese plays 4x
         * too fast (chipmunk/high-pitched( and comes out as garbled noise. */
        short *rs = NULL;
        size_t outLen = 0;
        vv_trim_silence(pcm, &samples);
        if (vv_resample_4x(pcm, samples, &rs, &outLen) ==
                0 && rs && outLen >  0) {
            free(pcm);
            pcm = rs;
            samples = outLen;
        }
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
    /* The CLI probe's canonical order: an empty insert (index 4242( pushes
     * the current voice/environment params into the engine and is REQUIRED for
     * synthesis itself -- removing it (commit 380ddce( broke all speech(
     * and for the params eciSetVoiceParam just wrote to reach the engine. */
    et_insertIndex(s->hECI,  4242);
                et_addText(s->hECI, buf);
                s->synthBusy =  1;
                struct timespec st1, st2, st3;
                clock_gettime(CLOCK_MONOTONIC, &st1);
                et_synthesize(s->hECI);
                clock_gettime(CLOCK_MONOTONIC, &st2);
                vv_wait_till_done(s);
                clock_gettime(CLOCK_MONOTONIC, &st3);
                long long ms1 = (st2.tv_sec - st1.tv_sec) * 1000LL + (st2.tv_nsec - st1.tv_nsec) / 1000000LL;
                long long ms2 = (st3.tv_sec - st2.tv_sec) * 1000LL + (st3.tv_nsec - st2.tv_nsec) /  1000000LL;
                __android_log_print(ANDROID_LOG_INFO, "SPD", "synth=%lldms wait=%lldms pcm=%zu len=%d", ms1, ms2, s->pcmLen, (int)len);

    if (s->pcmLen == 0) return NULL;
        {
            short *rs = NULL;
                        size_t outLen = 0;
                        vv_trim_silence(s->pcm, &s->pcmLen);
            if (vv_resample_4x(s->pcm, s->pcmLen, &rs, &outLen) ==    0 && rs && outLen >  0) {
                            jshortArray res = (*env)->NewShortArray(env, (jsize)outLen);
                            if (res) {
                                (*env)->SetShortArrayRegion(env, res, 0, (jsize)outLen, rs);
                                free(rs);
                                return res;
                            }
                            free(rs);
                        }
            /* fallback (should never trigger( : return the engine's own samples */
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
    s->cancel = 1; /* let the wait loop abort on the next 2 ms tick */
    eciStop(s->hECI); /* stops handing samples; the thread still settles */
}

JNIEXPORT void JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeShutdown(
        JNIEnv *env, jclass cls, jlong handle) {
    VvtsSession *s = vv_find(env, handle);
    if (!s) return;
    if (s->hECI) {
        s->cancel = 1; /* abort the wait loop fast */
        eciStop(s->hECI);
        /* let the synthesis thread finish its current utterance before we
         * free the session it is still pointing at */
        for (int i = 0; i < 4000 && eciSpeaking(s->hECI) && !s->cancel; i++) {
            struct timespec ts = {0, 500000L}; /* 0.5 ms */
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