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

// ECI 函数签名（从 libeci.so 反汇编确认）
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

// 直接写 ECI 参数到内存（绕过 eciSetVoiceParam 的限制）
// voice=0: 参数存储在 hECI + param*4 + 192
// voice>0: 参数存储在 hECI + (voice-9)*80 + param*4 + 352
typedef void (*setRealWorldParams_fn)(void* hECI, int param);
typedef int (*eciCopyVoice_fn)(void* hECI, int srcVoice, int dstVoice);

// ECI 活动参数接口（eciSetParam：设置当前活动语音的参数，4参数签名）
// param 枚举：2=音调, 6=音量, 7=语速（从 nativeSetProsody 反汇编确认）
// 其他 ECI 经典参数：1=音高范围?, 需实测
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

// ===== 核心突破：直接调用广荣内部的 ECI 包装函数 =====
// 广荣 nativeSetProsody（已验证可用）通过 grHandle+0x8088 的函数指针调 ECI：
//   fn(hECI, 0, param, value)  — 4参数
// 该函数与导出符号 eciSetVoiceParam 行为不同（无掩码限制，param 2/6/7 均生效）。
// zh ECI handle 在 grHandle+16，en 在 grHandle+16456。
// ===== Klatt 钩子：粗糙度/气声/头部大小的正门 =====
// 苹果 Kona 就是用 eciRegisterKlattHooks2 注入角色音色的。
// KlattFrame 是 float 数组（ETI 标准布局），这里按 CSV 的
// roughness/breathiness/headSize 三维做全局修正。
typedef struct {
    float roughness;    // 0-100（CSV: roughness）
    float breathiness;  // 0-100（CSV: breathiness）
    float headSize;     // 0-100（CSV: headSize，50=中）
    float pitchScale;   // 音调倍率（CSV pitchBase/65）
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
// KlattFrame 字段索引（ETI 标准：0=F0 基频, 摩擦/浊音幅度在中段）
// 通过探针实验标定；下面用保守修正避免破音
#define KF_F0        0
#define KF_AV        8   // 浊音幅度（气声↑ → av 稍降）
#define KF_AF        9   // 摩擦幅度（粗糙度↑ → af 微升）
#define KF_SW        20  // 共振峰带宽缩放（头部大小↑ → 带宽变窄→声音"大"）
#define KF_FRAME_MAX 64

static void roleConstHook(void* pConst, void* userData) {
    LOGI("KLATT CONST HOOK CALLED pConst=%p", pConst);
    // 静态参数（头部大小）：KlattConstantParams
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
    // 音调
    if (g_role.pitchScale != 1.0f && frame[KF_F0] > 0) {
        frame[KF_F0] *= g_role.pitchScale;
    }
    // 粗糙度 → F0 微抖动（每8帧一次颤动）+ 摩擦增强
    if (g_role.roughness > 0) {
        static int tick = 0;
        tick++;
        if ((tick & 7) == 0) {
            frame[KF_F0] *= (1.0f - 0.012f * (g_role.roughness / 100.0f));
        }
        if (KF_AF < KF_FRAME_MAX && frame[KF_AF] > 0) {
            frame[KF_AF] *= (1.0f + 0.006f * g_role.roughness);   // 最多 +60%
        }
    }
    // 气声 → 浊音幅度降低（漏气感）
    if (g_role.breathiness > 0 && KF_AV < KF_FRAME_MAX && frame[KF_AV] > 0) {
        frame[KF_AV] *= (1.0f - 0.004f * g_role.breathiness);      // 最多 -40%
    }
    // 头部大小 → 带宽缩放（50 为中性）
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
    // 判断句柄层级：ECIinstance 的第一个字段是 SynthThread 指针。
    // 如果 *(void**)eci 看起来是有效指针且与 eci 不同，说明 eci 是 ECIinstance（需要解引用）；
    // 广荣 grHandle+16 存的是 eciNewEx 返回值 = ECIinstance*。
    // eciRegisterKlattHooks2 内部自己解引用（ldr x0,[x0]），所以直接传 ECIinstance 即可。
    // 但之前直接传没生效，尝试解引用后的 SynthThread。
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

// 读广荣内部 ECI 参数（与 nativeSetEciParam 同款函数指针，x1=voice? x2=param? 待确认读法）
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeGetEciParam(JNIEnv* env, jclass clazz,
                                                         jlong grHandle, jint dialect,
                                                         jint param) {
    if (grHandle == 0) return -1;
    char* p = (char*)grHandle;
    // 找读函数：广荣 0x8088 是写；ECI 经典读接口是 eciGetParam(h, param)
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

// 切换 ECI 标准 voice（苹果 CSV：Reed=1, Shelley=2, Sandy=3, Rocko=4,
// Flo=6, Grandma=7, Grandpa=8, Eddy=9）
// 正确入口：SynthThread::addParam("v", voiceNumber)
// （ECIinstance::eciSetStandardVoice → sendAnnotation → 最终就是 addParam("v", N)，
//   但它经过 ETIEvent 会在引擎空闲时崩，所以直接调最底层的 addParam）
// SynthThread 指针在 ECIinstance 偏移 224 处（反汇编 ldr x2,[x0],#224 确认）
typedef int (*addParam_fn)(void* self, const char* name, unsigned int value);

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_voicejni_VoiceNative_nativeSetStandardVoice(JNIEnv* env, jclass clazz,
                                                             jlong grHandle, jint dialect,
                                                             jint voice) {
    void* eci = get_eci_handle(grHandle, dialect);
    if (eci == NULL) { LOGE("invalid handle grHandle=%llx dialect=0x%x", (long long)grHandle, dialect); return -1; }

    // ECIinstance 偏移 224 = SynthThread*
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

// 用 eciAddText2 发送带 SAPI 标记的文本（fAnnotationsIn=true 让引擎解析标记而非朗读）
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

// 完整合成流程：eciAddText2(带annotations) → eciSynthesize → 收集PCM
// 替代广荣的 nativeSynthesize（它用旧 eciAddText 不支持标记）

typedef struct {
    short* buffer;
    int capacity;
    int position;
} PcmCollector;

// ECI 回调：收集 PCM 样本
// ECI 音频回调签名：(hECI, format, samples, count, userData)
// 实际由 eciRegisterCallback 注册的回调在合成时被调用

// 简化方案：用 eciAddText2 + eciSynthesize2 走完整个流程
// 但需要先注册 PCM 回调 —— 由广荣 nativeInit 已经做了
// 所以这里只需要：eciReset → eciAddText2 → eciSynthesize → 等完成

// 广荣 nativeSynthesize 的内部流程（从反汇编推断）:
// 1. eciReset(hECI)
// 2. eciSetOutput(hECI, samples_buffer, buffer_size, &format)
// 3. eciAddText(hECI, text, size)
// 4. eciSynthesize(hECI, 1)
// 5. eciWait(hECI) 
// 6. PCM 从 samples_buffer 读出

// 我们复刻同样流程但用 eciAddText2：
typedef int (*eciReset_fn)(void*);
typedef int (*eciAddText2_fn)(void*, void*, int, int, int, int);
typedef int (*eciSynthesize2_fn)(void*, int);
typedef int (*eciWait_fn)(void*);
typedef int (*eciSetOutput_fn)(void*, void*, int, void*);

// 广荣 nativeSynthesize 返回 short[]，内部从 buffer 取 PCM
// 我们用同样方式但传 eciAddText2

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
    void* fnWait = NULL; // eciWait 不存在，用 eciSpeaking 轮询
    
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
    
    // 4. Wait for completion - 用广荣 nativeSynthesize 的同步方式
    //    实际上 ECI 在多线程模式下 eciSynthesize 是异步的
    //    广荣 nativeSynthesize 内部有自己的等待逻辑
    //    我们这里用简单轮询 eciSpeaking
    
    (*env)->ReleaseByteArrayElements(env, text, buf, 0);
    
    // PCM 由广荣的回调收集到 nativeSynthesize 用的 buffer
    // 但我们不在 nativeSynthesize 上下文中...
    // 这个方案需要更深入的集成，暂时返回 null
    return NULL;
}


// 简单方案：单独发 SAPI 标记（不带文本），标记留在引擎内部影响后续合成
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
    // 从日志字符串 "eciAddText: text=%p, Annotation=%d" 可知有 annotation 参数
    void* fnAdd = resolve_symbol("eciAddText");
    if (!fnAdd) {
        (*env)->ReleaseStringUTFChars(env, mark, markStr);
        LOGE("eciAddText not found");
        return -1;
    }
    
    // eciAddText 签名需要确认。经典 ECI API:
    // ECIHand eciAddText(ECIHand hECI, void* text, int size, int annotation)
    // 但实际 C ABI 可能不同。用 eciAddText2 (6 参数版) 更安全
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
    // 具体参数布局不确定，尝试常见格式
    // eciAddText 可能是 (hECI, text, size, annotation) 4参数
    typedef int (*fn4)(void*, void*, int, int);
    int ret = ((fn4)fnAdd)(eci, (void*)markStr, markLen, 1);
    LOGI("SAPI mark via eciAddText(4args): ret=%d", ret);
    
    (*env)->ReleaseStringUTFChars(env, mark, markStr);
    return ret;
}
// 测试 voice 表是否被加载
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
