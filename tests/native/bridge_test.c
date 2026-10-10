/* Fault-inject the ECI boundary while running the production JNI implementation. */
#include <assert.h>
#include <stdio.h>
#include <stdatomic.h>
#include "../../jni/vvtts_core.c"

static int fail_setup, calls, polls, settling;
static atomic_int stop_calls, deletes, refuse_delete, reclaimed;
static atomic_int waiting;
static VvtsSession *active;
static void *old_text;
static int cancel_during_copy;
static short pcm_fixture[256];
const int32_t ev_paramRange[18][2] = {
    {0,1},{0,1},{0,3},{0,1},{0,100},{0,48000},{0,100},{0,1},{0,1},
    {0,INT32_MAX},{0,1},{0,1},{0,1},{2,INT32_MAX},{220,INT32_MAX},{0,INT32_MAX},{220,INT32_MAX},{0,0}
};
/** Return a sentinel native handle for JNI constructor tests. */
ECIHand eciNewEx(int dialect) { return (void *)1; }
/** Count deletion attempts and optionally refuse destruction to exercise deferred cleanup. */
ECIHand eciDelete(ECIHand h) { deletes++; if (atomic_load(&refuse_delete)) return h; reclaimed++; return NULL; }
/** Capture the session callback data and optionally reject callback registration. */
int vv_register_callback(ECIHand h, ECICallback cb, void *data) { active = data; return fail_setup != 1; }
/** Inject acceptance or failure of the native output-buffer setup. */
int eciSetOutputBuffer(ECIHand h, int n, short *p) { return fail_setup != 2; }
/** Count parameter writes and inject engine-parameter setup failure. */
int eciSetParam(ECIHand h, int p, int v) { calls++; return fail_setup == 3 ? -1 : 1; }
/** Count accepted voice-parameter writes. */
int eciSetVoiceParam(ECIHand h, int v, int p, int x) { calls++; return 0; }
/** Count voice-parameter reads and return a fixed midpoint fixture. */
int eciGetVoiceParam(ECIHand h, int v, int p) { calls++; return 50; }
/** Count preset copies and optionally reject configuration. */
int eciCopyVoice(ECIHand h, int a, int b) { calls++; return fail_setup == 4 ? 0 : 1; }
/** Count input clears and optionally reject preparation for synthesis. */
int eciClearInput(ECIHand h) { calls++; return fail_setup != 5; }
/** Record cancellation and release the simulated speaking loop. */
int eciStop(ECIHand h) { stop_calls++; atomic_store(&waiting, 0); return 1; }
/** Simulate speaking while asserting old text and PCM remain valid during settling. */
int eciSpeaking(ECIHand h) {
    if (settling) {
        assert(active->text == old_text);
        assert(active->pcmLen == 7);
        settling = 0;
        return 1;
    }
    if (atomic_load(&waiting)) { polls++; return 1; }
    return 0;
}
/** Inject index insertion success or failure. */
int et_insertIndex(void *h, long index) { return fail_setup != 8; }
/** Validate fixture text and optionally reject native input submission. */
int et_addText(void *h, const char *text) { assert(strcmp(text, "hello") == 0); return fail_setup != 6; }
/** Deliver fixture PCM and optionally simulate failure or continued speaking for cancellation tests. */
int et_synthesize(void *h) {
    if (fail_setup == 7) return 0;
    memcpy(active->chunk, pcm_fixture, sizeof(pcm_fixture));
    vv_cb(h, eciWaveformBuffer, 256, active);
    if (cancel_during_copy == 2 || cancel_during_copy == 3) atomic_store(&waiting, 1);
    return 1;
}
/** Disable oracle synthesis in the bridge fixture; oracle behavior is tested separately. */
size_t chs_build_pcm(const unsigned char *src, size_t n, short **out) { *out = NULL; return 0; }

typedef struct { jsize len; short samples[]; } Array;
/** Return the length of the fixed hello text fixture. */
static jsize array_length(JNIEnv *env, jarray a) { return 5; }
/** Report that the JNI fixture has no pending exception. */
static jboolean exception(JNIEnv *env) { return JNI_FALSE; }
/** Provide a no-op exception clear for the exception-free JNI fixture. */
static void clear_exception(JNIEnv *env) {}
/** Copy fixture text and optionally race cancellation against the JNI input copy. */
static void copy_bytes(JNIEnv *env, jbyteArray a, jsize start, jsize len, jbyte *out) {
    memcpy(out, "hello", 5);
    if (cancel_during_copy == 1)
        Java_com_xw_vvtts_core_VvttsCore_nativeStop(env, NULL, active->id);
}
/** Allocate a host-backed JNI short-array fixture and record its length. */
static jshortArray new_array(JNIEnv *env, jsize n) {
    Array *a = malloc(sizeof(Array) + sizeof(short) * n); assert(a); a->len = n; return (jshortArray)a;
}
/** Copy PCM into a host array after asserting the destination bounds. */
static void copy_shorts(JNIEnv *env, jshortArray array, jsize start, jsize n, const jshort *data) {
    Array *a = (Array *)array; assert(start + n <= a->len); memcpy(a->samples + start, data, n * sizeof(short));
}
static struct JNINativeInterface_ jni = {.GetArrayLength=array_length,.ExceptionCheck=exception,
    .ExceptionClear=clear_exception,.GetByteArrayRegion=copy_bytes,.NewShortArray=new_array,.SetShortArrayRegion=copy_shorts};
static JNIEnv env = &jni;
#define INIT() Java_com_xw_vvtts_core_VvttsCore_nativeInitEngine(&env,NULL,NULL,NULL,0x10000)
#define SYNTH(h) Java_com_xw_vvtts_core_VvttsCore_nativeSynthesize(&env,NULL,h,0x10000,(jbyteArray)1,0,NULL)
#define SHUT(h) Java_com_xw_vvtts_core_VvttsCore_nativeShutdown(&env,NULL,h)

/** Race stop or shutdown against active synthesis and assert concurrent operations are rejected. */
static void *stop_thread(void *ignored) {
    while (!atomic_load(&waiting)) { struct timespec t={0,100000}; nanosleep(&t,NULL); }
    if (cancel_during_copy == 3) {
        // Shutdown removes the public handle immediately but must retain the active call.
        jlong id = active->id;
        assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,id,5,1)==-1);
        SHUT(id);
    } else Java_com_xw_vvtts_core_VvttsCore_nativeStop(&env,NULL,active->id);
    return NULL;
}

/** Read the deferred-cleanup count under its mutex. */
static unsigned pending_cleanup(void) {
    pthread_mutex_lock(&vv_cleanup_lock);
    unsigned count = vv_pending_cleanup;
    pthread_mutex_unlock(&vv_cleanup_lock);
    return count;
}

/** Wait within a bounded interval and assert all deferred sessions have been reclaimed. */
static void await_cleanup(void) {
    for (int i = 0; i < 500 && pending_cleanup(); ++i) {
        struct timespec delay = {0, 10000000L}; nanosleep(&delay, NULL);
    }
    assert(pending_cleanup() == 0);
}

/** Compare the production resampler against its zero-padded reference. */
static void test_resampler_equivalence(void) {
    const float (*coeff)[VV_RSP_TAPS] = vv_resample_table();
    unsigned state = 0x6d2b79f5u;
    const size_t lengths[] = {1, 2, 3, 31, 32, 63, 64, 65, 257, 4096};
    for (size_t c = 0; c < sizeof(lengths) / sizeof(lengths[0]); ++c) {
        const size_t n = lengths[c];
        short *input = malloc(n * sizeof(*input));
        assert(input);
        for (size_t i = 0; i < n; ++i) {
            state = state * 1664525u + 1013904223u;
            input[i] = (short)(state >> 16);
        }
        short *padded = calloc(n + 2 * VV_RSP_HALF, sizeof(*padded));
        short *expected = malloc(n * VV_RSP_PHASES * sizeof(*expected));
        assert(padded && expected);
        memcpy(padded + VV_RSP_HALF, input, n * sizeof(*input));
        for (size_t out = 0; out < n * VV_RSP_PHASES; ++out) {
            const size_t i = out >> 2;
            const int phase = (int)(out & 3);
            float acc = 0.0f;
            const short *src = padded + VV_RSP_HALF + i;
            for (int k = 0; k < VV_RSP_TAPS; ++k)
                acc += coeff[phase][k] * (float)src[k - VV_RSP_HALF];
            if (acc > 32767.0f) acc = 32767.0f;
            else if (acc < -32768.0f) acc = -32768.0f;
            expected[out] = (short)(acc >= 0.0f ? acc + 0.5f : acc - 0.5f);
        }
        short *actual = NULL;
        size_t actual_n = 0;
        assert(vv_resample_4x(input, n, &actual, &actual_n) == 0);
        assert(actual_n == n * VV_RSP_PHASES);
        assert(memcmp(actual, expected, actual_n * sizeof(*actual)) == 0);
        free(actual);
        free(expected);
        free(padded);
        free(input);
    }
}

/** Do not trim when leading and trailing silence overlap after preserving edge padding. */
static void test_trim_silence_overlapping_edges(void) {
    const size_t n = 22066;
    short *pcm = calloc(n, sizeof(*pcm));
    assert(pcm);
    size_t samples = n;
    vv_trim_silence(pcm, &samples, 11025);
    assert(samples == n);
    free(pcm);
}

/** Exercise JNI setup failures, parameter bounds, PCM endpoints, cancellation, and session lifetime races. */
static void test_silent_engine_output(void) {
    short zero[64] = {0};
    assert(vv_has_audio(zero, 64) == 0);
    short voiced[64]; for (int k=0;k<64;k++) voiced[k]=(k==7)?800:0;
        assert(vv_has_audio(voiced, 64) == 1);
        short at700[64]; for (int k=0;k<64;k++) at700[k]=(k==7)?700:0;      /* |700| is voiced per vv_trim_silence */
        assert(vv_has_audio(at700, 64) == 1);
        short atNeg700[64];for (int k=0;k<64;k++) atNeg700[k]=(k==7)?-700:0;/* exact -700 voiced */
        assert(vv_has_audio(atNeg700, 64) == 1);
        short below[64]; for (int k=0;k<64;k++) below[k]=(k==7)?699:0;   /* 699 < TH: silent */
        assert(vv_has_audio(below, 64) == 0);
    for (int i=0;i<256;i++) pcm_fixture[i]=0;         /* engine returns all-silent */
    jlong h = INIT(); assert(h);
    assert(SYNTH(h) == NULL);                          /* must fail, never silent 'speech' */
    SHUT(h);
    for (int i=0;i<256;i++) pcm_fixture[i]=(i>32 && i<220)?4000:0;
}

/** Exercise JNI setup failures, parameter bounds, PCM endpoints, cancellation, and session lifetime races. */
int main(void) {
    test_resampler_equivalence();
    test_trim_silence_overlapping_edges();
    test_silent_engine_output();
    for (int i=0;i<256;i++) pcm_fixture[i] = i > 32 && i < 220 ? 4000 : 0;
    for (fail_setup=1;fail_setup<=3;fail_setup++) { int before=deletes; assert(INIT()==0); assert(deletes==before+1); }
    fail_setup=0;
    jlong h=INIT(); assert(h);
    VvtsSession *s=active;
    // The first mutation boundary must follow settlement, even after stop.
    s->text=strdup("previous"); old_text=s->text; s->pcmLen=7;
    s->pcmCap=256; s->pcm=calloc(256,sizeof(short)); settling=1;
    Array *a=(Array *)SYNTH(h); assert(a && a->len>0); assert(a->samples[0]==0 && a->samples[a->len-1]==0); free(a);
    // A stop during JNI text capture must survive start and suppress all new engine work.
    cancel_during_copy=1; int before=calls; assert(SYNTH(h)==NULL); assert(calls==before);
    // Stop -> immediate synth is reusable, with a fresh generation, hundreds of times.
    cancel_during_copy=0;
    for(int i=0;i<200;i++) {
        Java_com_xw_vvtts_core_VvttsCore_nativeStop(&env,NULL,h);
        a=(Array *)SYNTH(h); assert(a && a->len>0); free(a);
    }
    cancel_during_copy=2;
    pthread_t thread; pthread_create(&thread,NULL,stop_thread,NULL);
    assert(SYNTH(h)==NULL); pthread_join(thread,NULL); assert(stop_calls>0);
    cancel_during_copy=0;
    // Busy admission must reject a concurrent request without changing state.
    s->synthBusy=1; assert(SYNTH(h)==NULL); s->synthBusy=0;
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,8,0)>=0);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,7,2)==-1);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,2)>=0 && s->outputHz==22050 && s->maxPcm==22050u*60);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,1)>=0 && s->outputHz==11025 && s->maxPcm==11025u*60);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,5)>=0 && s->outputHz==44100 && s->maxPcm==44100u*60);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,11,0)==-1);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,18,0)==-1);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetVoiceParam(&env,NULL,h,0,2,101)==-1);
    for(int voice=0;voice<=9;voice++) {
        int r=Java_com_xw_vvtts_core_VvttsCore_nativeSetStandardVoice(&env,NULL,h,voice);
        assert(voice>=1 && voice<=8 ? r>=0 : r<0);
    }
    fail_setup=4; assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetStandardVoice(&env,NULL,h,1)<0); fail_setup=0;
    for(int retired=0;retired<2;retired++) {
        s->failed=!retired; s->retired=retired; before=calls;
        assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetVoiceParam(&env,NULL,h,0,2,50)<0);
        assert(Java_com_xw_vvtts_core_VvttsCore_nativeGetVoiceParam(&env,NULL,h,0,2)<0);
        assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetStandardVoice(&env,NULL,h,1)<0);
        assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,1)<0);
        Java_com_xw_vvtts_core_VvttsCore_nativeStop(&env,NULL,h);
        assert(calls==before);
        a=(Array *)SYNTH(h); assert(a && a->len==0); free(a);
    }
    s->failed=0; s->retired=0;
    assert(vv_cb(s->hECI,eciWaveformBuffer,APP_SAMPLES+1,s)==eciDataProcessed);
    a=(Array *)SYNTH(h); assert(a && a->len==0); free(a);
    SHUT(h);
    // Repeated destruction and stale/arbitrary handles must never dereference freed storage.
    SHUT(h); SHUT(INT64_MAX);
    Java_com_xw_vvtts_core_VvttsCore_nativeStop(&env,NULL,h);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,1)==-1);
    assert(SYNTH(h)==NULL);
    jlong old = h; h=INIT(); assert(h && h!=old);
    cancel_during_copy=3; before=deletes;
    pthread_create(&thread,NULL,stop_thread,NULL);
    assert(SYNTH(h)==NULL); pthread_join(thread,NULL);
    assert(deletes==before+1); SHUT(h); assert(deletes==before+1);
    assert(vv_sessions==NULL);
    cancel_during_copy=0;
    for (fail_setup=5;fail_setup<=8;fail_setup++) {
        h=INIT(); assert(h);
        a=(Array *)SYNTH(h); assert(a && a->len==0); free(a);
        int count=calls;
        assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,1)==-1);
        a=(Array *)SYNTH(h); assert(a && a->len==0); free(a);
        assert(calls==count); SHUT(h);
    }
    fail_setup=0;
    // Initial setup cannot publish an unusable handle, even if deletion refuses.
    for (fail_setup=1; fail_setup<=3; ++fail_setup) {
        int freed = reclaimed;
        refuse_delete=1;
        assert(INIT()==0);
        assert(vv_sessions==NULL && pending_cleanup()==1);
        // Callback storage remains valid and reachable by cleanup after init returns.
        assert(active->hECI && reclaimed==freed);
        refuse_delete=0; await_cleanup(); assert(reclaimed==freed+1);
    }
    fail_setup=0;
    // A Kotlin shutdown transfers ownership, including failed deletes, to native.
    h=INIT(); int freed=reclaimed; refuse_delete=1;
    SHUT(h); assert(vv_sessions==NULL && pending_cleanup()==1);
    SHUT(h); assert(pending_cleanup()==1);
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,1)==-1);
    refuse_delete=0; await_cleanup(); assert(reclaimed==freed+1);
    short tiny[]={1000,-1000}; vv_fade_edges(tiny,2,44100); assert(tiny[0]==0 && tiny[1]==0);
    assert(vv_dialect_shipped(0x70000) && vv_dialect_shipped(0x90000));
    puts("PASS native: setup failures, stop generations, settlement ownership, repeat synthesis, control guards, ranges, PCM edges");
}
