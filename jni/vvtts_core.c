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
#include <stdatomic.h>
#include "eci_compat.h"
#include "pcm_limits.h"
#include "vv_resample.h"
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>
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

/* ------------------------------------------------------------------ */

typedef struct VvtsSession {
    int32_t outputHz; /* current output sample rate; engine-native 44,100 default */
    size_t maxPcm;    /* 60 s of PCM at outputHz: callback accumulation cap */

    ECIHand hECI;
    int32_t dialect;            /* dialect the handle was initialized for (#113) */
    short   chunk[APP_SAMPLES]; /* callback scratch */
    short  *pcm;                /* accumulated waveform */
    size_t  pcmLen, pcmCap;
    void   *text;               /* text kept alive for the engine's thread */
    jlong id;
    unsigned references;
    int closing;
    struct VvtsSession *next;
    pthread_mutex_t operation;
    atomic_int synthBusy;
    atomic_uint stopGeneration;
    unsigned activeGeneration;
    atomic_int cancel; /* set by stop/shutdown to abort the wait loop fast */
    atomic_int fatal;  /* set by the callback on overflow/alloc failure: synthesis must fail, not degrade ( see vv_fail( ) */
atomic_int failed;      /* engine-state-invalidation flag (see vv_fail_session( */
    atomic_int retired;    /* engine wedge: dead session; reclaim only in shutdown after eciSpeaking proves quiet */
} VvtsSession;

/** Report whether a session has a live engine and has not failed or been retired. */
static int vv_usable(const VvtsSession *s) {
    return s && s->hECI &&
           !atomic_load(&s->failed) &&
           !atomic_load(&s->retired);
}

/** Detect cancellation, including a stop that raced the current synthesis generation. */
static int vv_cancelled(const VvtsSession *s) {
    return atomic_load(&s->cancel) ||
           atomic_load(&s->stopGeneration) != s->activeGeneration;
}

static void vv_fail_session(VvtsSession *s);  /* poison late-alloc-failure path (realloc fail below( */

/* Fatal callback error: flag the session so the wait loop stops fast (cancel)
 * and nativeSynthesize fails instead of returning partial or rate-mismatched audio. */
/** Mark a callback failure as fatal and cancel synthesis instead of returning partial audio. */
static int vv_fail(VvtsSession *s) {
    atomic_store(&s->failed, 1);
    atomic_store(&s->fatal, 1);
    atomic_store(&s->cancel, 1);
    return eciDataProcessed;
}


/* The engine calls back on its own synthesis thread; the caller only reads
 * s->pcm after waiting for eciSpeaking to go quiet, so no locking is needed
 * as long as one session is never driven from two threads at once (the
 * Kotlin side already serialises with its synthesis lock). */
/** Append waveform callbacks within allocation bounds; poison the session on overflow or allocation failure. */
static int vv_cb(ECIHand h, ECIMessage message, int param, void *data) {
    VvtsSession *s = (VvtsSession *)data;
    if (!vv_usable(s) || vv_cancelled(s) || message != eciWaveformBuffer)
        return eciDataProcessed;
    if (param < 0) return eciDataProcessed;
    {
        size_t n = (size_t)param;
        if (n > APP_SAMPLES) return vv_fail(s);  /* contract violation:the engine sent more samples than the configured output buffer holds -- never silently truncate (#46( */
        if (n > SIZE_MAX - s->pcmLen) return vv_fail(s);  /* size_t add would wrap (#39( */
        size_t need = s->pcmLen + n;
        if (need > s->maxPcm) return vv_fail(s);  /* 60 s cap at the current output rate keeps jsize bounds (#39( */
        if (need > s->pcmCap) {
            size_t cap = s->pcmCap ? s->pcmCap : 16384;
            while (cap < need) {
                if (cap > SIZE_MAX /  2) return vv_fail(s);
                cap *=  2;
            }
            if (cap > s->maxPcm) cap = s->maxPcm;  /* cap >= need already, so squeezing keeps it valid */
            if (cap > SIZE_MAX / sizeof(short)) return vv_fail(s);
            short *p = (short *)realloc(s->pcm, cap * sizeof(short));
            if (!p) {
                vv_fail(s);  /* allocation failure stops synthesis (#42( instead of continuing with a stale buffer */
                vv_fail_session(s);
                return eciDataProcessed;
            }
            s->pcm = p;
            s->pcmCap = cap;
        }
        memcpy(s->pcm + s->pcmLen, s->chunk, n * sizeof(short));
        s->pcmLen += n;
    }
    return eciDataProcessed;
}

#define VV_DRAIN_MAX_ITERS 40000  /* 0.5 ms each: ~20 s of drain before retirement */
#define VV_STOP_DRAIN_MAX_ITERS 2000  /* cancelled/stale gen: ~1 s is ample (eciStop already stopped samples) before the session is retired so a fresh generation can proceed */

/** Poll until synthesis is quiet, forwarding cancellation and retiring sessions that exceed the drain bound. */
static void vv_wait_till_done(VvtsSession *s) {
    /* openevv's engine cannot abandon an utterance: eciStop stops the
     * samples but the synthesis thread still has to finish it.  A fixed
     * iteration bound is NOT proof of finish: a long utterance can keep
     * eciSpeaking() true for well over 2 s, and returning while the engine
     * is still speaking lets the caller read/resample s->pcm while the
     * synthesis callback writes into it -- corruption.  Separate the stop
     * request from the drain: poll eciSpeaking() until it actually goes
     * quiet.  The generous bound exists only to guard against an engine
     * wedge: if quiet cannot be proven within it, the session is retired
     * rather than hanging the synthesis thread forever. */
    __android_log_print(ANDROID_LOG_INFO, "SPD", "wait_entry pcm=%zu", s->pcmLen);
    int iters = 0;
    int stopSent = 0;
    int stopAt = 0;
    /* Anchor the cancelled-drain bound to the moment the stop is sent, not to a
     * global iters floor: if onStop lands mid-drain on a legitimate long chunk
     * (already past VV_STOP_DRAIN_MAX_ITERS), the engine still needs time to
     * finish after eciStop.  Give it VV_STOP_DRAIN_MAX_ITERS more iters from
     * stopAt, never exceeding the full VV_DRAIN_MAX_ITERS cap. */
    while (s->hECI && eciSpeaking(s->hECI) &&
           iters < VV_DRAIN_MAX_ITERS &&
           iters < (stopSent ? stopAt + VV_STOP_DRAIN_MAX_ITERS : VV_DRAIN_MAX_ITERS)) {
        if (vv_cancelled(s) && !stopSent) {
            if (!eciStop(s->hECI)) atomic_store(&s->failed, 1);
            stopSent = 1;
            stopAt = iters;
        }
        /* Polling is what collects the engine's queued samples: the engine
         * posts chunks into the app queue and only a poll delivers them, so
         * a poll must keep coming until the engine reports quiet.  Keep the
         * poll short -- 500us -- or long utterances take seconds to come out. */
        struct timespec ts = {0, 500000L}; /* 0.5 ms */
        nanosleep(&ts, NULL);
        iters++;
    }
    if (s->hECI && eciSpeaking(s->hECI)) {
        atomic_store(&s->retired, 1); // Never reuse buffers while the engine may still own them.
    }
    __android_log_print(ANDROID_LOG_INFO, "SPD", "wait_exit i_done pcm=%zu", s->pcmLen);
}

/* vv_settle: bounded poll used when re-entering synthesis right after a
 * stop aborted the wait loop -- eciStop stops handing samples but the
 * engine's own thread still has to finish; one session must never be driven
 * from two threads concurrently, so wait for the engine to go quiet before
 * touching text/pcm again. */
/** Prove the previous utterance is quiet before buffer reuse, or retire the session on timeout. */
static int vv_settle(VvtsSession *s) {
    for (int i = 0; i < VV_STOP_DRAIN_MAX_ITERS; i++) {
        if (!eciSpeaking(s->hECI)) return 1;
        struct timespec ts = {0, 500000L};
        nanosleep(&ts, NULL);
    }
    atomic_store(&s->retired, 1);
    return 0;
}

/* The engine emits short lead-in/trailing silence around every utterance;
 * for short TalkBack labels those fixed pauses dominate the perceived swipe
 * latency, so trim them before resampling.  A >-33 dBFS sample stops the
 * scan, so real speech is never eaten; near-empty blips are left alone. */
/* #313: An engine 'success' that delivers NO sample above the speech floor is
 * silent output: the engine failed to voice the text, but handing it on as
 * valid PCM would have the utterance progress with no audible speech.  The
 * engine already trims edge silence, so any sustained region surviving that is
 * fully at-or-under the floor is degenerate -- collapse it to failure instead
 * of handing silence for real speech (same ±700 floor as vv_trim_silence. */
static int vv_has_audio(const short *pcm, size_t n) {
    for (size_t i = 0; i < n; ++i) if (pcm[i] >= 700 || pcm[i] <= -700) return 1;
    return 0;
}

/* IDs never expose addresses or get reused. The registry owns the lifetime;
 * every JNI operation acquires a reference before touching session storage. */
static pthread_mutex_t vv_registry_lock = PTHREAD_MUTEX_INITIALIZER;
static VvtsSession *vv_sessions;
static jlong vv_next_id = 1;
static int vv_destroy(VvtsSession *s);

/* Once removed from the public registry, cleanup owns the whole session,
 * including callback storage. A refused delete is retried after quiescence. */
static pthread_mutex_t vv_cleanup_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t vv_cleanup_ready = PTHREAD_COND_INITIALIZER;
static VvtsSession *vv_cleanup_queue;
static unsigned vv_pending_cleanup;
static int vv_cleanup_started;

/** Retry destruction of retired sessions, preserving callback storage until the engine releases ownership. */
static void *vv_cleanup_worker(void *unused) {
    (void)unused;
    for (;;) {
        pthread_mutex_lock(&vv_cleanup_lock);
        while (!vv_cleanup_queue) pthread_cond_wait(&vv_cleanup_ready, &vv_cleanup_lock);
        VvtsSession *s = vv_cleanup_queue;
        vv_cleanup_queue = s->next;
        pthread_mutex_unlock(&vv_cleanup_lock);
        int freed = vv_destroy(s);
        pthread_mutex_lock(&vv_cleanup_lock);
        if (freed) --vv_pending_cleanup;
        else {
            // Append so one wedged session cannot starve other retired sessions.
            VvtsSession **tail = &vv_cleanup_queue;
            while (*tail) tail = &(*tail)->next;
            s->next = NULL;
            *tail = s;
        }
        pthread_mutex_unlock(&vv_cleanup_lock);
        if (!freed) {
            struct timespec delay = {0, 100000000L};
            nanosleep(&delay, NULL);
        }
    }
    return NULL;
}

/** Start the shared deferred-cleanup thread once; return zero if thread creation fails. */
static int vv_start_cleanup(void) {
    pthread_mutex_lock(&vv_cleanup_lock);
    if (!vv_cleanup_started) {
        pthread_t thread;
        if (!pthread_create(&thread, NULL, vv_cleanup_worker, NULL)) {
            pthread_detach(thread);
            vv_cleanup_started = 1;
        }
    }
    int ready = vv_cleanup_started;
    pthread_mutex_unlock(&vv_cleanup_lock);
    return ready;
}

/** Destroy an unreferenced session immediately or transfer ownership to the cleanup queue. */
static void vv_dispose(VvtsSession *s) {
    if (vv_destroy(s)) return;
    pthread_mutex_lock(&vv_cleanup_lock);
    s->next = vv_cleanup_queue;
    vv_cleanup_queue = s;
    ++vv_pending_cleanup;
    pthread_cond_signal(&vv_cleanup_ready);
    pthread_mutex_unlock(&vv_cleanup_lock);
}

/** Look up a public session ID and acquire a lifetime reference, or return NULL. */
static VvtsSession *vv_find(JNIEnv *env, jlong handle) {
    (void)env;
    pthread_mutex_lock(&vv_registry_lock);
    VvtsSession *s = vv_sessions;
    while (s && s->id != handle) s = s->next;
    if (s) ++s->references;
    pthread_mutex_unlock(&vv_registry_lock);
    return s;
}

/** Release a JNI lifetime reference and dispose a closing session after its last reference leaves. */
static void vv_release(VvtsSession **reference) {
    VvtsSession *s = *reference;
    if (!s) return;
    pthread_mutex_lock(&vv_registry_lock);
    int destroy = --s->references == 0 && s->closing;
    pthread_mutex_unlock(&vv_registry_lock);
    if (destroy) vv_dispose(s);
}

/** Release an acquired operation mutex when a scoped JNI guard exits. */
static void vv_unlock(pthread_mutex_t **lock) {
    if (*lock) pthread_mutex_unlock(*lock);
}

/* Clang (Android) and GCC (host tests) release these guards on every return. */
#define VV_REFERENCE(handle) \
    VvtsSession *s __attribute__((cleanup(vv_release))) = vv_find(env, handle)
#define VV_OPERATION(failure) \
    pthread_mutex_t *op __attribute__((cleanup(vv_unlock))) = NULL; \
    if (!s || pthread_mutex_trylock(&s->operation)) return failure; \
    op = &s->operation


/* Dialect whitelist: only the language modules linked into this build
 * (build_native.sh LANGS) may be instantiated.  eo_newEx builds its voice
 * table by indexing a static structure from the FAMILY number of the
 * requested dialect; for a family that has no module in this build the
 * structure shape is different, so the engine walks garbage and SIGSEGVs
 * (observed with 0x60000/zh-CN on a build without lang/chs).  Reject
 * unknown dialects up front so the caller gets a clean NULL handle instead
 * of a killed process -- this is what keeps an accidental wrong-language
 * request from ever crashing the app (or TalkBack's TTS session). */
/** Accept only dialect IDs whose voice modules are linked into this build. */
static int vv_dialect_shipped(int32_t dialect) {
    switch (dialect) {
    case 0x10000: case 0x10001:  /* enus, engb */
    case 0x20000: case 0x20001: case 0x20002:  /* eses, esus, esmx */
    case 0x30000: case 0x30001:  /* frfr, frca */
    case 0x40000:                /* dede */
    case 0x50000:                /* itit */
    case 0x70000: case 0x90000: /* ptb, fin */
    case 0x80000:                /* jajp */
    case 0x110000:               /* plpl */
    case 0x60000:                /* chs -- linked; synthesis is oracle-fed */
        return 1;
    default:
        return 0;
    }
}

/** Create and register a configured session, returning an opaque ID or zero on failure. */
JNIEXPORT jlong JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeInitEngine(
        JNIEnv *env, jclass cls, jstring configDir, jstring libDir, jint dialect) {
    if (!vv_start_cleanup()) return 0;
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
    if (pthread_mutex_init(&s->operation, NULL)) { free(s); return 0; }
    s->hECI = eciNewEx((int)dialect);
    s->dialect = (int32_t)dialect;
    if (!s->hECI) {
        pthread_mutex_destroy(&s->operation);
        free(s);
        return 0;
    }
    if (!vv_register_callback(s->hECI, vv_cb, s) ||
        !eciSetOutputBuffer(s->hECI, APP_SAMPLES, s->chunk) ||
        eciSetParam(s->hECI, eciSampleRate, VV_ENGINE_RATE) < 0) {
        vv_dispose(s);
        return 0;
    }
    /* Engine-native resampling: eciSampleRate code 5 == 44,100 Hz output, the
     * Android playback rate kotlin advertises.  The engine builds its sinc
     * converter under this (see vv_resample.h); trim/fade/cap all key off
     * outputHz, so a later rate change via nativeSetParam stays consistent. */
    s->outputHz = VV_OUTPUT_HZ;
    s->maxPcm = (size_t)VV_OUTPUT_HZ * 60;
    pthread_mutex_lock(&vv_registry_lock);
    if (vv_next_id == INT64_MAX) {
        pthread_mutex_unlock(&vv_registry_lock);
        vv_dispose(s);
        return 0;
    }
    s->id = vv_next_id++;
    s->next = vv_sessions;
    vv_sessions = s;
    pthread_mutex_unlock(&vv_registry_lock);
    return s->id;
}

/* ------------------------------------------------------------------ */
/* A failed / JNI-exception synthesis path can leave the engine input/PCM
 * state undefined: mark the session failed so no later call trusts it,, and
 * answer the empty-array sentinel ( a 0-length PCM array( so THE
 * Kotlin wrapper can distinguish "engine state invalidated" from the ordinary
 * "no audio" null,and drop/rebuild its cached handle.  (Contract: null
 * = no audio,, non-empty = PCM,, empty array = engine state poisoned(. */
/** Permanently invalidate a session after synthesis failure, retaining buffers until safe cleanup. */
static void vv_fail_session(VvtsSession *s) {
    /* Poisoned sessions keep hECI alive: shutdown must still be able to
     * eciStop/eciDelete it; only the reusable state is torn down here, so
     * a later call on the same handle answers the sentinel instead. */
    atomic_store(&s->failed, 1);
    /* Live text/PCM are left intact until settlement or shutdown. */
}

static jshortArray vv_empty_result(JNIEnv *env) {
    /* Leave allocation exceptions pending so the Kotlin wrapper can signal failure. */
    return (*env)->NewShortArray(env, 0);
}
/* ------------------------------------------------------------------ */

/** Synthesize with the session operation held; return PCM, NULL on cancellation, or an empty failure result. */
static jshortArray vv_synthesize(
        JNIEnv *env, jclass cls, VvtsSession *s, jint dialect,
        jbyteArray text, jint charsetId, jstring outPath) {
    if (!s) return NULL;
    if (atomic_load(&s->failed)) return vv_empty_result(env);
    if (atomic_load(&s->retired)) return vv_empty_result(env); /* engine wedge: never re-drive this session */
    if (!s->hECI) return NULL;
    if (!text) return NULL;
    /* #113: a handle is initialized for one dialect; synthesizing text marked
     * for another would silently mix language modules and param sets. Refuse
     * loudly instead (the Kotlin side treats a NULL result as a failed job). */
    if ((int32_t)dialect != s->dialect) {
        __android_log_print(ANDROID_LOG_WARN, "VvttsCore",
                            "dialect mismatch: handle=%d, request=%d",
                            (int)s->dialect, (int)dialect);
        return NULL;
    }

    const char *operation = "GetArrayLength";
    jsize len = (*env)->GetArrayLength(env, text);
    if ((*env)->ExceptionCheck(env)) goto failed;
    if (len <= 0 || (size_t)len > VV_MAX_TEXT_BYTES) return NULL;

    operation = "malloc text buffer";
    void *buf = malloc((size_t)len + 1);
    if (!buf) goto failed;
    operation = "GetByteArrayRegion";
    (*env)->GetByteArrayRegion(env, text, 0, len, (jbyte *)buf);
    if ((*env)->ExceptionCheck(env)) {
        free(buf);
        goto failed;
    }
    ((char *)buf)[len] = '\0';

    /* charsetId describes how the byte array was encoded by the Kotlin
     * side (1252 for Western, GB18030 for zh).  openevv reads the caller's
     * bytes as the language module's own byte set, which is the Windows
     * Western set for the shipped languages -- matching the 1252 path the
     * app already uses.  The engine copies what it needs of the text, but
     * we keep it alive for the length of the call to be safe. */
    operation = "vv_settle";
    if (!vv_settle(s) || !vv_usable(s)) {
        free(buf);
        return vv_empty_result(env);
    }
    if (vv_cancelled(s)) { free(buf); return NULL; }
    free(s->text);
    s->text = buf;
    s->pcmLen = 0;
    atomic_store(&s->fatal, 0);

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
        if (samples == 0) { free(pcm); return NULL; }
    /* (#40( refuse to hand a jsize array longer than the resampler can
    * 4x back into jsize bounds. */
    if (samples > VV_MAX_PCM_SAMPLES) { free(pcm); return NULL; }
        /* Oracle clips are captured at the engine's native 11.025 kHz (the
         * same rate eci.ini fixed the en-us path at(.  Playback always runs
         * at 44.1 kHz (SAMPLE_RATE(, so zh must take the same 4x
         * upsampling as the engine branch below -- else Chinese plays 4x
         * too fast (chipmunk/high-pitched( and comes out as garbled noise. */
        short *rs = NULL;
        size_t outLen = 0;
        vv_trim_silence(pcm, &samples, 11025); /* oracle clips are engine-native rate */
        operation = "vv_resample_4x (oracle)";
    if (vv_resample_4x(pcm, samples, &rs, &outLen) != 0 || !rs || outLen == 0) {
    /* #137: never fall back to the engine's native 11,025 Hz -- Kotlin
    * always advertises 44,100; wrong-rate output is a format violation. */
    free(pcm);
    return NULL;
    }
    free(pcm);
    vv_fade_edges(rs, outLen, 44100);
    pcm = rs;
    samples = outLen;
        free(s->pcm);
        s->pcm = pcm;
        s->pcmLen = samples;
        s->pcmCap = samples;
        operation = "NewShortArray (oracle)";
        jshortArray out = (*env)->NewShortArray(env, (jsize)samples);
        if (!out || (*env)->ExceptionCheck(env)) goto failed;
        operation = "SetShortArrayRegion (oracle)";
        (*env)->SetShortArrayRegion(env, out, 0, (jsize)samples, pcm);
        if ((*env)->ExceptionCheck(env)) goto failed;
        return vv_cancelled(s) ? NULL : out;
}

    operation = "eciClearInput";
    if (!eciClearInput(s->hECI)) {
        /* #134: a rejected clear means the previous input state is undefined;
         * never synthesize on top of it. */
        __android_log_print(ANDROID_LOG_ERROR, "VvTtsCore", "eciClearInput failed: refusing to synthesize on stale input state (#134)");
        goto failed;
    }
    /* The CLI probe's canonical order: an empty insert (index 4242( pushes
     * the current voice/environment params into the engine and is REQUIRED for
     * synthesis itself -- removing it (commit 380ddce( broke all speech(
     * and for the params eciSetVoiceParam just wrote to reach the engine. */
    operation = "et_insertIndex";
    if (!et_insertIndex(s->hECI, 4242)) goto failed;
    operation = "et_addText";
       if (!et_addText(s->hECI, buf)) {
           /* #135: a rejected text insertion must never be followed by
            * synthesis of stale/empty input; stop here and return failure. */
           __android_log_print(ANDROID_LOG_ERROR, "VvTtsCore", "et_addText failed: refusing to synthesize stale/empty input (#135)");
           goto failed;
       }
       if (vv_cancelled(s)) return NULL;
       struct timespec st1, st2, st3;
       clock_gettime(CLOCK_MONOTONIC, &st1);
       int synthRet = et_synthesize(s->hECI);
       if (!synthRet) {
    /* #136: a rejected synthesis must be terminal for this request;
    * never let stale PCM escape as a normal result. */

    __android_log_print(ANDROID_LOG_ERROR, "VvTtsCore", "et_synthesize failed (%d): synthesis terminal (#136)", synthRet);
    goto failed;
    }
    clock_gettime(CLOCK_MONOTONIC, &st2);
    operation = "vv_wait_till_done";
    vv_wait_till_done(s);
    if (atomic_load(&s->fatal)) return vv_empty_result(env);
    if (vv_cancelled(s)) {
        return (atomic_load(&s->retired) || atomic_load(&s->failed)) ? vv_empty_result(env) : NULL;
    }
    clock_gettime(CLOCK_MONOTONIC, &st3);
    long long ms1 = (st2.tv_sec - st1.tv_sec) * 1000LL + (st2.tv_nsec - st1.tv_nsec) / 1000000LL;
    long long ms2 = (st3.tv_sec - st2.tv_sec) * 1000LL + (st3.tv_nsec - st2.tv_nsec) /  1000000LL;
    __android_log_print(ANDROID_LOG_INFO, "SPD", "synth=%lldms wait=%lldms pcm=%zu len=%d", ms1, ms2, s->pcmLen, (int)len);
    if (atomic_load(&s->failed)) goto failed;
    if (atomic_load(&s->retired)) {
        /* Timeout mid-flight: the engine thread may still be writing s->pcm.
         * Return failure WITHOUT reading, trimming or resampling PCM; the
         * session stays cached until shutdown reclaims it safely. */
        __android_log_print(ANDROID_LOG_ERROR, "SPD", "synth: session retired; returning null");
        return vv_empty_result(env);
    }
    if (s->pcmLen == 0) return NULL;
    /* Engine synthesized natively at s->outputHz (44,100 default): trim and
     * edge-fade only -- no 4x sinc; the engine's own resampler already
     * produced the Android playback rate (see vv_resample.h). */
    vv_trim_silence(s->pcm, &s->pcmLen, s->outputHz);
    /* #313: an engine result that is fully silent is silent speech:the engine failed
     * to voice the text but returned a nonempty buffer.  Handing it to the framework
     * would advance utterance progress with no audible output.  Collapse to failure. */
    if (!vv_has_audio(s->pcm, s->pcmLen)) {

        __android_log_print(ANDROID_LOG_ERROR, "SPD", "synth: engine returned silent PCM; treating as failure (#313)");
        return NULL;
    }
    vv_fade_edges(s->pcm, s->pcmLen, s->outputHz);
    size_t outLen = s->pcmLen;
    operation = "NewShortArray";
    jshortArray out = (*env)->NewShortArray(env, (jsize)outLen);
    if (!out || (*env)->ExceptionCheck(env)) goto failed;
    operation = "SetShortArrayRegion";
    (*env)->SetShortArrayRegion(env, out, 0, (jsize)outLen, s->pcm);
    if ((*env)->ExceptionCheck(env)) goto failed;
    return vv_cancelled(s) ? NULL : out;

failed:
    __android_log_print(ANDROID_LOG_ERROR, "VvttsCore",
                        "nativeSynthesize failed during %s (dialect=0x%x, JNI exception=%d)",
                        operation, (unsigned int)dialect, (int)(*env)->ExceptionCheck(env));
    vv_fail_session(s);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return vv_empty_result(env);
}

/** Acquire session lifetime and operation guards, then synthesize under a captured stop generation. */
JNIEXPORT jshortArray JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSynthesize(
        JNIEnv *env, jclass cls, jlong handle, jint dialect,
        jbyteArray text, jint charsetId, jstring outPath) {
    VV_REFERENCE(handle);
    VV_OPERATION(NULL);
    if (!s) return NULL;
    unsigned generation = atomic_load(&s->stopGeneration);
    int expected = 0;
    if (!atomic_compare_exchange_strong(&s->synthBusy, &expected, 1)) return NULL;
    s->activeGeneration = generation;
    // Never clear stopGeneration: a stop racing this start must remain observable.
    jshortArray result = vv_synthesize(env, cls, s, dialect, text, charsetId, outPath);
    atomic_store(&s->synthBusy, 0);
    return result;
}

/** Set a validated active-voice parameter; return -1 for invalid, busy, or unusable sessions. */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetVoiceParam(
        JNIEnv *env, jclass cls, jlong handle, jint voice, jint param, jint value) {
    VV_REFERENCE(handle);
    VV_OPERATION(-1);
    if (!vv_usable(s)) return -1;
    if (voice != 0 || param < 0 || param >= eciNumVoiceParams) return -1;
    int maximum = param == eciSpeed ? 250 : param == eciGender ? 1 : 100;
    if (value < 0 || value > maximum) return -1;
    return eciSetVoiceParam(s->hECI, voice, param, value);
}

/** Read a validated voice parameter; return -1 for invalid, busy, or unusable sessions. */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeGetVoiceParam(
        JNIEnv *env, jclass cls, jlong handle, jint voice, jint param) {
    VV_REFERENCE(handle);
    VV_OPERATION(-1);
    if (!vv_usable(s)) return -1;
    if (voice < 0 || voice > ECI_LAST_VOICE || param < 0 || param >= eciNumVoiceParams) return -1;
    return eciGetVoiceParam(s->hECI, voice, param);
}

/** Set an engine parameter only within its canonical range and the bridge's fixed format contract. */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(
        JNIEnv *env, jclass cls, jlong handle, jint param, jint value) {
    VV_REFERENCE(handle);
    VV_OPERATION(-1);
    if (!vv_usable(s) || param < 0 || param >= eciNumParams) return -1;
    // Use the engine's authoritative ranges; its setter also rejects reserved
    // indices, invalid rates and unsupported dialect changes. Never voice-clamp.
    extern const int32_t ev_paramRange[18][2];
    if (param == 11 || param == 17 || value < ev_paramRange[param][0] ||
        value > ev_paramRange[param][1]) return -1;
    // The bridge owns encoding and voice-parameter units.
    // Native-rate locking was the old engine-native guard: it kept the voice
    // at 11,025 and forced our 4x sinc.  With engine-native resampling the
    // whole valid range is allowed and s->outputHz tracks the live rate so
    // trim/fade/cap stay mathematically attached to what the engine emits.
    if (param == eciLanguageDialect && value != s->dialect) return -1;
    if (param == eciRealWorldUnits && value != 0) return -1;
    int32_t r = eciSetParam(s->hECI, param, value);
    if (r < 0) return r;
    if (param == eciSampleRate) {
        static const int32_t CODE_HZ[7] = {8000, 11025, 22050, 16000, 32000, 44100, 48000};
        int32_t hz = (value >= 0 && value <= 6) ? CODE_HZ[value] : value;
        if (hz >= 8000 && hz <= 48000) {
            s->outputHz = hz;
            s->maxPcm = (size_t)hz * 60;
        }
    }
    return 0;
}

/* The app's presets are numbered like the old Apple CSV (which skips 5);
 * openevv's voices are 1..8 in the same order -- Reed, Shelley, Sandy,
 * Rocko, Flo, Grandma, Grandpa, Eddy.  Copy the chosen preset onto voice 0
 * (the active voice the Kotlin side then tunes by voice params). */
/** Copy a shipped preset to active voice zero, returning 1 on success or -1 on failure. */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetStandardVoice(
        JNIEnv *env, jclass cls, jlong handle, jint voiceNumber) {
    VV_REFERENCE(handle);
    VV_OPERATION(-1);
    if (!vv_usable(s)) return -1;
    if (voiceNumber < 1 || voiceNumber > ECI_PRESET_VOICES) return -1;
    return eciCopyVoice(s->hECI, voiceNumber, 0) ? 1 : -1;
}

/** Signal cancellation by advancing the generation without calling the engine from the stop thread. */
JNIEXPORT void JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeStop(
        JNIEnv *env, jclass cls, jlong handle) {
    VV_REFERENCE(handle);
    if (!vv_usable(s)) return;
    // The owning synthesis thread performs eciStop while polling. Calling the
    // legacy engine itself from the stop thread races its non-atomic queue state.
    atomic_fetch_add(&s->stopGeneration, 1);

}

/** Remove a public session ID, cancel work, and defer disposal until active references leave. */
JNIEXPORT void JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeShutdown(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    pthread_mutex_lock(&vv_registry_lock);
    VvtsSession **link = &vv_sessions;
    while (*link && (*link)->id != handle) link = &(*link)->next;
    VvtsSession *s = *link;
    int destroy = 0;
    if (s) {
        *link = s->next;
        s->closing = 1;
        atomic_store(&s->cancel, 1);
        atomic_fetch_add(&s->stopGeneration, 1);
        destroy = s->references == 0;
    }
    pthread_mutex_unlock(&vv_registry_lock);
    // An active call retains ownership and performs destruction on return.
    if (destroy) vv_dispose(s);
}

/** Free session storage only after engine quiescence and successful deletion; return zero to retry. */
static int vv_destroy(VvtsSession *s) {
    if (s->hECI) {
        atomic_store(&s->cancel, 1); /* let a concurrent wait loop abort fast */
        eciStop(s->hECI);
        /* Let the synthesis thread finish its current utterance before we
         * free the session it is still pointing at.  This drain ignores
         * cancel: aborting it would eciDelete while the worker still owns
         * the session (use-after-free). */
        for (int i = 0; i < VV_DRAIN_MAX_ITERS && eciSpeaking(s->hECI); i++) {
            struct timespec ts = {0, 500000L}; /* 0.5 ms */
            nanosleep(&ts, NULL);
        }
        if (eciSpeaking(s->hECI)) {
            // Cleanup keeps ownership and retries; never free live callback data.
            return 0;
        }
        if (eciDelete(s->hECI)) return 0; // Failed cleanup still owns callback storage.
        s->hECI = NULL;
    }
    free(s->text);
    free(s->pcm);
    pthread_mutex_destroy(&s->operation);
    free(s);
    return 1;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}
