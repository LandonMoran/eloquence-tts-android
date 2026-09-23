#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "VvTtsVoice"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define GR_ECI_ZH 16
#define GR_ECI_EN 16456

// ECI function signatures (confirmed from libeci.so disassembly)
typedef int (*eciSetVoiceParam_fn)(void*, int, int, int);   // hECI, voice, param, value
typedef int (*eciGetVoiceParam_fn)(void*, int, int);        // hECI, voice, param

static void* get_eci_handle(jlong grHandle, int dialect) {
    if (grHandle == 0) return NULL;
    char* p = (char*)grHandle;
    int off = (dialect == 0x10000) ? GR_ECI_EN : GR_ECI_ZH;
    return *(void**)(p + off);
}

static void* resolve_symbol(const char* name) {
    void* sym = dlsym(RTLD_DEFAULT, name);
    if (sym != NULL) return sym;
    void* lib = dlopen("libeci.so", RTLD_NOW | RTLD_GLOBAL);
    if (lib == NULL) {
        LOGE("dlopen libeci.so failed: %s", dlerror());
        return NULL;
    }
    return dlsym(lib, name);
}

// Write ECI params straight into memory (bypassing eciSetVoiceParam's limits)
// voice=0: params live at hECI + param*4 + 192
// voice>0: params live at hECI + (voice-9)*80 + param*4 + 352
typedef void (*setRealWorldParams_fn)(void* hECI, int param);
typedef int (*eciCopyVoice_fn)(void* hECI, int srcVoice, int dstVoice);

// ECI active-param interface:(eciSetParam sets the current voice's params;4-arg signature)
// param enum:2=pitch, 6=volume, 7=speed (confirmed from nativeSetProsody disassembly)
// Other classic ECI params:1=pitch range？ needs testing
typedef int (*eciSetParam_fn)(void* hECI, int param, int value);
typedef int (*eciGetParam_fn)(void* hECI, int param);

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeSetParam(JNIEnv* env, jclass clazz,
                                                      jlong grHandle, jint dialect,
                                                      jint param, jint value) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) { LOGE("invalid handle grHandle=%llx dialect=0x%x", (long long)grHandle, dialect); return -1; }
    eciSetParam_fn fn = (eciSetParam_fn)resolve_symbol("eciSetParam");
    if (fn == NULL) { LOGE("eciSetParam not found"); return -1; }
    int ret = fn(eci, param, value);
    LOGI("eciSetParam param=%d value=%d ret=%d", param, value, ret);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeGetParam(JNIEnv* env, jclass clazz,
                                                      jlong grHandle, jint dialect,
                                                      jint param) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) return -1;
    eciGetParam_fn fn = (eciGetParam_fn)resolve_symbol("eciGetParam");
    if (fn == NULL) { LOGE("eciGetParam not found"); return -1; }
    return fn(eci, param);
}

// ===== Key breakthrough:calling Guangrong's internal ECI wrapper directly =====
// Guangrong's nativeSetProsody(verified working)calls ECI via the function pointer at grHandle+0x8088:
//   fn(hECI, 0, param, value) — 4 args
// Behaves differently from the exported eciSetVoiceParam(no mask limits;params 2/6/7 all work)
// zh ECI handle sits at grHandle+16;en at grHandle+16456.
// ===== Klatt hooks:the proper path for roughness/breath/head-size =====
// Apple Kona injects voice character via eciRegisterKlattHooks2.
// KlattFrame is a float array(ETI standard layout);here we apply the CSV's
// roughness/breathiness/headSize three dimensions as a global correction.
typedef struct {
    float roughness;    // 0-100(CSV: roughness)
    float breathiness;  // 0-100(CSV: breathiness)
    float headSize;     // 0-100 (CSV: headSize; 50=mid(
    float pitchScale;   // pitch multiplier (CSV pitchBase/65
    int   active;
} RoleKlatt;

static RoleKlatt g_role = {0, 0, 50, 1.0f, 0};
static char g_logPath[256] = {0};
static void klog(const char* fmt, ...) {
    if (g_logPath[0] == 0) return;
    FILE* f = fopen(g_logPath, "a");
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fprintf(f, "\n");
    fclose(f);
}
// KlattFrame field indices(ETI standard:0=F0 fundamental freq;friction/voiced amplitude mid-block)
// calibrated via probe experiments;conservative corrections below avoid crackle
#define KF_F0        0
#define KF_AV        8   // voiced amplitude (breath up -> av dips slightly
#define KF_AF        9   // friction amplitude (roughness up -> af creeps up
#define KF_SW        20  // formant bandwidth scale (head-size up -> narrower bandwidth -> "bigger" voice
#define KF_FRAME_MAX 64

static void roleConstHook(void* pConst, void* userData) {
    LOGI("KLATT CONST HOOK CALLED pConst=%p", pConst);
    // static params(head size):KlattConstantParams
}

static void roleDynamicHook(float* frame, void* userData) {
    if (!g_role.active || frame == NULL) return;
    static int hookCalls = 0;
    if (hookCalls < 3) {
        hookCalls++;
        klog("HOOK frame: [0]=%.1f [1]=%.1f [2]=%.1f [3]=%.1f [4]=%.1f [5]=%.1f [6]=%.1f [7]=%.1f [8]=%.1f [9]=%.1f [10]=%.1f [12]=%.1f [14]=%.1f [16]=%.1f [20]=%.1f",
             frame[0], frame[1], frame[2], frame[3], frame[4], frame[5], frame[6], frame[7],
             frame[8], frame[9], frame[10], frame[12], frame[14], frame[16], frame[20]);
    }
    // pitch
    if (g_role.pitchScale != 1.0f && frame[KF_F0] > 0) {
        frame[KF_F0] *= g_role.pitchScale;
    }
    // roughness -> F0 micro-tremor(wobble every 8 frames)+ friction boost
    if (g_role.roughness > 0) {
        static int tick = 0;
        tick++;
        if ((tick & 7) == 0) {
            frame[KF_F0] *= (1.0f - 0.012f * (g_role.roughness / 100.0f));
        }
        if (KF_AF < KF_FRAME_MAX && frame[KF_AF] > 0) {
            frame[KF_AF] *= (1.0f + 0.006f * g_role.roughness);   // up to +60%
        }
    }
    // breath -> lower voiced amplitude(airy feel)
    if (g_role.breathiness > 0 && KF_AV < KF_FRAME_MAX && frame[KF_AV] > 0) {
        frame[KF_AV] *= (1.0f - 0.004f * g_role.breathiness);      // up to -40%
    }
    // head size -> bandwidth scaling(50 is neutral)
    if (g_role.headSize != 50.0f && KF_SW < KF_FRAME_MAX) {
        float delta = (g_role.headSize - 50.0f) / 50.0f;           // -1..1
        if (frame[KF_SW] > 0) frame[KF_SW] *= (1.0f - 0.10f * delta);
    }
}
JNIEXPORT void JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeInitKlattLog(JNIEnv* env, jclass clazz,
                                                          jstring path) {
    if (path == NULL) return;
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p) {
        snprintf(g_logPath, sizeof(g_logPath), "%s", p);
        (*env)->ReleaseStringUTFChars(env, path, p);
        klog("klatt log initialized: %s", g_logPath);
    }
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeRegisterKlattHooks(JNIEnv* env, jclass clazz,
                                                                jlong grHandle, jint dialect) {
    klog("registerKlattHooks enter grHandle=%llx dialect=0x%x", (long long)grHandle, dialect);
    if (grHandle == 0) return -1;
    char* p = (char*)grHandle;
    int off = (dialect == 0x10000) ? 16456 : 16;
    void* eci = *(void**)(p + off);
    klog("  eci=%p", eci);
    if (eci == NULL) return -1;
    void* fn = resolve_symbol("eciRegisterKlattHooks2");
    klog("  fn=%p", fn);
    if (fn == NULL) return -1;
    // Handle-level check:the first field of an ECIinstance is a SynthThread pointer.
    // If *(void**)eci looks like a valid pointer and differs from eci,theeci is an ECIinstance(needs deref);
    // Guangrong grHandle+16 holds eciNewEx's return ＝ ECIinstance*.
    // eciRegisterKlattHooks2 derefs internally(ldr x0,[x0]),so passing the ECIinstance straight works.
    // but passing it directly didn't take before;trying the deref'd SynthThread instead.
    void* synth = *(void**)eci;
    klog("  try variant A: pass eci as-is (%p)", eci);
    typedef int (*reg_fn)(void*, void(*)(void*, void*), void(*)(float*, void*), void*);
    int ret = ((reg_fn)fn)(eci, roleConstHook, roleDynamicHook, NULL);
    klog("  variant A ret=%d", ret);
    if (ret != 0 && synth != NULL) {
        klog("  try variant B: pass synth (%p)", synth);
        ret = ((reg_fn)fn)(synth, roleConstHook, roleDynamicHook, NULL);
        klog("  variant B ret=%d", ret);
    }
    LOGI("registerKlattHooks dialect=0x%x ret=%d", dialect, ret);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeSetRoleKlatt(JNIEnv* env, jclass clazz,
                                                          jfloat roughness, jfloat breathiness,
                                                          jfloat headSize, jfloat pitchScale) {
    g_role.roughness = roughness;
    g_role.breathiness = breathiness;
    g_role.headSize = headSize;
    g_role.pitchScale = pitchScale;
    g_role.active = 1;
    LOGI("roleKlatt rough=%.0f breath=%.0f head=%.0f pitch=%.2f", roughness, breathiness, headSize, pitchScale);
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeSetEciParam(JNIEnv* env, jclass clazz,
                                                         jlong grHandle, jint dialect,
                                                         jint param, jint value) {
    if (grHandle == 0) return -1;
    char* p = (char*)grHandle;
    void* fn = *(void**)(p + 0x8088);
    if (fn == NULL) { LOGE("fnptr at grHandle+0x8088 is NULL"); return -1; }
    int off = (dialect == 0x10000) ? 16456 : 16;
    void* eci = *(void**)(p + off);
    if (eci == NULL) { LOGE("eci handle null for dialect 0x%x", dialect); return -1; }
    typedef int (*eci_fn4)(void*, int, int, int);
    int ret = ((eci_fn4)fn)(eci, 0, param, value);
    LOGI("gr fnptr(eci, 0, param=%d, value=%d) ret=%d", param, value, ret);
    return ret;
}

// Read Guangrong's internal ECI params(same fn-ptr family as nativeSetEciParam;x1=voice？ x2=param？ read convention TBD)
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeGetEciParam(JNIEnv* env, jclass clazz,
                                                         jlong grHandle, jint dialect,
                                                         jint param) {
    if (grHandle == 0) return -1;
    char* p = (char*)grHandle;
    // Looking for the read fn:Guangrong 0x8088 is write;the classic ECI read API is eciGetParam(h, param)
    void* fn = dlsym(RTLD_DEFAULT, "eciGetParam");
    if (fn == NULL) { LOGE("eciGetParam export not found"); return -1; }
    int off = (dialect == 0x10000) ? 16456 : 16;
    void* eci = *(void**)(p + off);
    if (eci == NULL) return -1;
    typedef int (*eci_get_fn)(void*, int);
    int val = ((eci_get_fn)fn)(eci, param);
    LOGI("eciGetParam(param=%d) = %d", param, val);
    return val;
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeSetVoiceParam(JNIEnv* env, jclass clazz,
                                                          jlong grHandle, jint dialect,
                                                          jint voice, jint param, jint value) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) { LOGE("invalid handle grHandle=%llx dialect=0x%x", (long long)grHandle, dialect); return -1; }

    eciSetVoiceParam_fn fn = (eciSetVoiceParam_fn)resolve_symbol("eciSetVoiceParam");
    if (fn == NULL) { LOGE("eciSetVoiceParam not found"); return -1; }
    int ret = fn(eci, voice, param, value);
    LOGI("eciSetVoiceParam voice=%d param=%d value=%d ret=%d", voice, param, value, ret);
    return ret;
}

// Switching to an ECI standard voice(Apple CSV:Reed=1,Shelley=2,Sandy=3,Rocko=4,
// Flo=6, Grandma=7, Grandpa=8, Eddy=9)
// Correct entry:SynthThread::addParam("v", voiceNumber)
//(ECIinstance::eciSetStandardVoice -> sendAnnotation -> ultimately addParam("v", N);
//   but that path goes through ETIEvent and crashes when idle,so call the bottom-most addParam)
// SynthThread pointer sits at ECIinstance offset 224(ldr x2,[x0],#224 in the disassembly)
typedef int (*addParam_fn)(void* self, const char* name, unsigned int value);

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeSetStandardVoice(JNIEnv* env, jclass clazz,
                                                             jlong grHandle, jint dialect,
                                                             jint voice) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) { LOGE("invalid handle grHandle=%llx dialect=0x%x", (long long)grHandle, dialect); return -1; }

// ECIinstance offset 224 ＝ SynthThread*
    void* synth = *(void**)((char*)eci + 224);
    if (synth == NULL) { LOGE("SynthThread is null at eci+224"); return -1; }

    void* fn = resolve_symbol("_ZN11SynthThread8addParamEPcj");
    if (fn == NULL) { LOGE("SynthThread::addParam not found"); return -1; }

    int ret = ((addParam_fn)fn)(synth, "v", (unsigned int)voice);
    LOGI("setStandardVoice via addParam synth=%p voice=%d ret=%d", synth, voice, ret);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeGetVoiceParam(JNIEnv* env, jclass clazz,
                                                          jlong grHandle, jint dialect,
                                                          jint voice, jint param) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) return 0;
    eciGetVoiceParam_fn fn = (eciGetVoiceParam_fn)resolve_symbol("eciGetVoiceParam");
    if (fn == NULL) return 0;
    return fn(eci, voice, param);
}

// Send SAPI-tagged text via eciAddText2(fAnnotationsIn=true makes the engine parse tags instead of speaking them)
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeAddTextWithAnnotations(JNIEnv* env, jclass clazz,
                                                                    jlong grHandle, jint dialect,
                                                                    jbyteArray text) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) return -1;
    jsize len = (*env)->GetArrayLength(env, text);
    jbyte* buf = (*env)->GetByteArrayElements(env, text, NULL);
    
    void* fn = resolve_symbol("eciAddText2");
    if (fn == NULL) { 
        (*env)->ReleaseByteArrayElements(env, text, buf, 0);
        LOGE("eciAddText2 not found"); 
        return -1; 
    }
    // eciAddText2(hECI, text, size, codeSet=0, fAnnotationsIn=1, extra=0)
    typedef int (*fn6)(void*, void*, int, int, int, int);
    int ret = ((fn6)fn)(eci, buf, len, 0, 1, 0);
    (*env)->ReleaseByteArrayElements(env, text, buf, 0);
    LOGI("eciAddText2 with annotations: len=%d ret=%d", len, ret);
    return ret;
}

// Full synthesis flow:eciAddText2(with annotations)-> eciSynthesize -> collect PCM
// Replaces Guangrong's nativeSynthesize(it used the old eciAddText without tag support)

typedef struct {
    short* buffer;
    int capacity;
    int position;
} PcmCollector;

// ECI callback:collect PCM samples
// ECI audio callback sig:(hECI, format,samples,,count,,userData)
// the callback registered via eciRegisterCallback gets invoked during synthesis

// Simplified plan:run the whole flow with eciAddText2 + eciSynthesize2
// but a PCM callback must be registered first — Guangrong's nativeInit already did it
// so here we only need:eciReset -> eciAddText2 -> eciSynthesize -> wait for done

// Guangrong's nativeSynthesize internals(inferred from the disassembly):
// 1. eciReset(hECI)
// 2. eciSetOutput(hECI, samples_buffer, buffer_size, &format)
// 3. eciAddText(hECI, text, size)
// 4. eciSynthesize(hECI, 1)
// 5. eciWait(hECI) 
// 6. PCM read out of samples_buffer

// We replicate the same flow,but with eciAddText2:
typedef int (*eciReset_fn)(void*);
typedef int (*eciAddText2_fn)(void*, void*, int, int, int, int);
typedef int (*eciSynthesize2_fn)(void*, int);
typedef int (*eciWait_fn)(void*);
typedef int (*eciSetOutput_fn)(void*, void*, int, void*);

// Guangrong's nativeSynthesize returns short[],pulling PCM from its internal buffer
// We do the same but feed it eciAddText2

JNIEXPORT jshortArray JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeSynthesizeWithAnnotations(
        JNIEnv* env, jclass clazz,
        jlong grHandle, jint dialect, jbyteArray text) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) return NULL;
    
    jsize len = (*env)->GetArrayLength(env, text);
    jbyte* buf = (*env)->GetByteArrayElements(env, text, NULL);
    
    void* fnReset = resolve_symbol("eciReset");
    void* fnAdd2 = resolve_symbol("eciAddText2");
    void* fnSynth = resolve_symbol("eciSynthesize");
    void* fnWait = NULL; // eciWait doesn't exist; poll with eciSpeaking
    
    if (!fnReset || !fnAdd2 || !fnSynth) {
        (*env)->ReleaseByteArrayElements(env, text, buf, 0);
        LOGE("missing ECI functions");
        return NULL;
    }
    
    // 1. Reset
    ((eciReset_fn)fnReset)(eci);
    
    // 2. AddText2 with annotations=true
    typedef int (*fn6)(void*, void*, int, int, int, int);
    int addRet = ((fn6)fnAdd2)(eci, buf, len, 0, 1, 0);
    LOGI("eciAddText2 ret=%d", addRet);
    
    // 3. Synthesize
    ((eciSynthesize2_fn)fnSynth)(eci, 1);
    
    // 4. Wait for completion — using Guangrong's nativeSynthesize sync approach
    //    actually ECI's eciSynthesize is async in multithreaded mode
    //    Guangrong's nativeSynthesize has its own wait logic inside
    //    we just poll eciSpeaking here
    
    (*env)->ReleaseByteArrayElements(env, text, buf, 0);
    
    // PCM is gathered by Guangrong's callback into nativeSynthesize's buffer
    // but we're not inside nativeSynthesize's context...
    // this approach needs deeper integration;return null for now
    return NULL;
}


// Simple approach:send the SAPI tag alone(no text);it stays in the engine and affects subsequent synthesis
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeInjectSapiMark(JNIEnv* env, jclass clazz,
                                                            jlong grHandle, jint dialect,
                                                            jstring mark) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) return -1;
    const char* markStr = (*env)->GetStringUTFChars(env, mark, NULL);
    if (!markStr) return -1;
    int markLen = strlen(markStr);
    
    // eciAddText(hECI, text, size, annotation=1)
    // the log string "eciAddText: text=%p, Annotation=%d" shows there's an annotation arg.
    void* fnAdd = resolve_symbol("eciAddText");
    if (!fnAdd) {
        (*env)->ReleaseStringUTFChars(env, mark, markStr);
        LOGE("eciAddText not found");
        return -1;
    }
    
// eciAddText's signature needs confirming.Classic ECI API:
    // ECIHand eciAddText(ECIHand hECI, void* text, int size, int annotation)
// but the real C ABI may differ;eciAddText2(6-arg version)is safer
    void* fnAdd2 = resolve_symbol("eciAddText2");
    if (fnAdd2) {
        // eciAddText2(hECI, text, size, codeSet, fAnnotationsIn, synthMode)
        typedef int (*fn6)(void*, void*, int, int, int, int);
        int ret = ((fn6)fnAdd2)(eci, (void*)markStr, markLen, 0, 1, 0);
        LOGI("SAPI mark via eciAddText2: ret=%d", ret);
        (*env)->ReleaseStringUTFChars(env, mark, markStr);
        return ret;
    }
    
    // fallback: eciAddText
// exact arg layout is uncertain;try common formats yo
// eciAddText might be(hECI, text,size,,annotation)4 args
    typedef int (*fn4)(void*, void*, int, int);
    int ret = ((fn4)fnAdd)(eci, (void*)markStr, markLen, 1);
    LOGI("SAPI mark via eciAddText(4args): ret=%d", ret);
    
    (*env)->ReleaseStringUTFChars(env, mark, markStr);
    return ret;
}
// test whether the voice table got loaded
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeTestVoiceTable(JNIEnv* env, jclass clazz,
                                                            jlong grHandle, jint dialect) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) return -1;
    
    void* fnName = resolve_symbol("eciGetVoiceName");
    if (!fnName) { LOGE("eciGetVoiceName not found"); return -1; }
    
    // eciGetVoiceName(hECI, voice, name_buffer)
    typedef int (*fn3s)(void*, int, char*);
    char name[256];
    for (int v = 0; v <= 9; v++) {
        memset(name, 0, sizeof(name));
        int ret = ((fn3s)fnName)(eci, v, name);
        LOGI("voice[%d] name='%s' ret=%d", v, name, ret);
    }
    return 0;
}
