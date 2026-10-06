/* Fault-inject the ECI boundary while running the production JNI implementation. */
#include <assert.h>
#include <stdio.h>
#include <stdatomic.h>
#include "../../jni/vvtts_core.c"

static int fail_setup, calls, stop_calls, deletes, polls, settling;
static atomic_int waiting;
static VvtsSession *active;
static void *old_text;
static int cancel_during_copy;
static short pcm_fixture[256];
const int32_t ev_paramRange[18][2] = {
    {0,1},{0,1},{0,3},{0,1},{0,100},{0,48000},{0,100},{0,1},{0,1},
    {0,INT32_MAX},{0,1},{0,1},{0,1},{2,INT32_MAX},{220,INT32_MAX},{0,INT32_MAX},{220,INT32_MAX},{0,0}
};
ECIHand eciNewEx(int dialect) { return (void *)1; }
ECIHand eciDelete(ECIHand h) { deletes++; return NULL; }
int vv_register_callback(ECIHand h, ECICallback cb, void *data) { active = data; return fail_setup != 1; }
int eciSetOutputBuffer(ECIHand h, int n, short *p) { return fail_setup != 2; }
int eciSetParam(ECIHand h, int p, int v) { calls++; return fail_setup == 3 ? -1 : 1; }
int eciSetVoiceParam(ECIHand h, int v, int p, int x) { calls++; return 0; }
int eciGetVoiceParam(ECIHand h, int v, int p) { calls++; return 50; }
int eciCopyVoice(ECIHand h, int a, int b) { calls++; return fail_setup == 4 ? 0 : 1; }
int eciClearInput(ECIHand h) { calls++; return 1; }
int eciStop(ECIHand h) { stop_calls++; atomic_store(&waiting, 0); return 1; }
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
int et_insertIndex(void *h, long index) { return 1; }
int et_addText(void *h, const char *text) { assert(strcmp(text, "hello") == 0); return 1; }
int et_synthesize(void *h) {
    memcpy(active->chunk, pcm_fixture, sizeof(pcm_fixture));
    vv_cb(h, eciWaveformBuffer, 256, active);
    if (cancel_during_copy == 2 || cancel_during_copy == 3) atomic_store(&waiting, 1);
    return 1;
}
size_t chs_build_pcm(const unsigned char *src, size_t n, short **out) { *out = NULL; return 0; }

typedef struct { jsize len; short samples[]; } Array;
static jsize array_length(JNIEnv *env, jarray a) { return 5; }
static jboolean exception(JNIEnv *env) { return JNI_FALSE; }
static void clear_exception(JNIEnv *env) {}
static void copy_bytes(JNIEnv *env, jbyteArray a, jsize start, jsize len, jbyte *out) {
    memcpy(out, "hello", 5);
    if (cancel_during_copy == 1)
        Java_com_xw_vvtts_core_VvttsCore_nativeStop(env, NULL, active->id);
}
static jshortArray new_array(JNIEnv *env, jsize n) {
    Array *a = malloc(sizeof(Array) + sizeof(short) * n); assert(a); a->len = n; return (jshortArray)a;
}
static void copy_shorts(JNIEnv *env, jshortArray array, jsize start, jsize n, const jshort *data) {
    Array *a = (Array *)array; assert(start + n <= a->len); memcpy(a->samples + start, data, n * sizeof(short));
}
static struct JNINativeInterface_ jni = {.GetArrayLength=array_length,.ExceptionCheck=exception,
    .ExceptionClear=clear_exception,.GetByteArrayRegion=copy_bytes,.NewShortArray=new_array,.SetShortArrayRegion=copy_shorts};
static JNIEnv env = &jni;
#define INIT() Java_com_xw_vvtts_core_VvttsCore_nativeInitEngine(&env,NULL,NULL,NULL,0x10000)
#define SYNTH(h) Java_com_xw_vvtts_core_VvttsCore_nativeSynthesize(&env,NULL,h,0x10000,(jbyteArray)1,0,NULL)
#define SHUT(h) Java_com_xw_vvtts_core_VvttsCore_nativeShutdown(&env,NULL,h)

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

int main(void) {
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
    assert(Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(&env,NULL,h,5,2)==-1);
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
    short tiny[]={1000,-1000}; vv_fade_edges(tiny,2); assert(tiny[0]==0 && tiny[1]==0);
    assert(vv_dialect_shipped(0x70000) && vv_dialect_shipped(0x90000));
    puts("PASS native: setup failures, stop generations, settlement ownership, repeat synthesis, control guards, ranges, PCM edges");
}
