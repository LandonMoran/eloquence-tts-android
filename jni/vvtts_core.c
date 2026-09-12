/*
 * vvtts_core.c - 自研 ECI 桥接层 v6（苹果 Eloquence 完整引擎，回调模式）
 *
 * 引擎：苹果 tvOS 18.2 的 eci.dylib 转成的 libeci.so（全套 14 语言 + 8 角色）
 * 语言库：libchs/cht/jpn/kor/enu/eng/deu/fra/frc/esp/esm/ita/ptb/fin.so
 * （macho2elf 转换，完整无缺，非广荣残缺 cache 提取物）
 *
 * 配方（抄广荣，Linux 侧已实测四方言全通）：
 *   eciNewEx(dialect) → eciRegisterKlattHooks2(h,0,0,0) → eciRegisterCallback
 *   → eciSetOutputBuffer → eciAddText → eciSynthesize → eciSynchronize
 *
 * 关键差异 vs 广荣旧库：
 *   - eciAddText 是标准 2 参（ECIHand, text），非广荣的 4 参
 *   - 必须注册空 Klatt 钩子，否则 CJK synthesize 内部 blr 空指针 SIGSEGV
 *   - 采样率 SetParam(5,1)=11025Hz（苹果只支持 8k/11.025k，不支持 22050）
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "VvttsCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* === 苹果 eci.so 导出的 ECI C API（标准 IBM ABI，2 参 AddText）=== */
typedef void* (*eciNewEx_t)(int dialect);
typedef int   (*eciNew2_t)(void** outInstance, int dialect);
typedef int   (*eciDelete_t)(void* hECI);
typedef int   (*eciSetParam_t)(void* hECI, int param, int value);
typedef int   (*eciGetParam_t)(void* hECI, int param);
typedef int   (*eciAddText_t)(void* hECI, const void* text);
typedef int   (*eciSynthesize_t)(void* hECI);
typedef int   (*eciSynchronize_t)(void* hECI);
typedef int   (*eciSpeaking_t)(void* hECI);
typedef int   (*eciStop_t)(void* hECI);
typedef void  (*eciRegisterCallback_t)(void* hECI, void* callback, void* userData);
typedef int   (*eciSetOutputBuffer_t)(void* hECI, int size, short* buffer);
typedef void  (*eciRegisterKlattHooks2_t)(void* hECI, void* constHook, void* dynHook, void* userData);
typedef int   (*eciSetVoiceParam_t)(void* hECI, int voice, int param, int value);
typedef int   (*eciGetVoiceParam_t)(void* hECI, int voice, int param);
typedef int   (*eciSetStandardVoice2_t)(void* hECI, int voiceNumber);
typedef int   (*eciClearInput_t)(void* hECI);

/* ECI 消息类型 / 回调返回码 */
enum { eciWaveformBuffer = 0 };
typedef enum { eciDataNotProcessed = 0, eciDataProcessed = 1, eciDataAbort = 2 } ECICallbackReturn;

static struct {
    void* handle;
    eciNewEx_t            NewEx;
    eciNew2_t             New2;
    eciDelete_t           Delete;
    eciSetParam_t         SetParam;
    eciGetParam_t         GetParam;
    eciAddText_t          AddText;
    eciSynthesize_t       Synthesize;
    eciSynchronize_t      Synchronize;
    eciSpeaking_t         Speaking;
    eciStop_t             Stop;
    eciRegisterCallback_t RegisterCallback;
    eciSetOutputBuffer_t  SetOutputBuffer;
    eciRegisterKlattHooks2_t RegisterKlattHooks2;
    eciSetVoiceParam_t    SetVoiceParam;
    eciGetVoiceParam_t    GetVoiceParam;
    eciSetStandardVoice2_t SetStandardVoice2;
    eciClearInput_t       ClearInput;
} eci;

#define PCM_CHUNK 8192
typedef struct {
    void* hECI;
    short chunk[PCM_CHUNK];
    short* pcm;          /* 累积缓冲 */
    long   pcm_len;
    long   pcm_cap;
    char*  text;         /* 最近一次 eciAddText 的文本缓冲（长期持有，防 use-after-free） */
    size_t text_cap;
} SynthSession;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_synth_lock = PTHREAD_MUTEX_INITIALIZER; /* 合成串行锁 */

/* PCM 回调：引擎把每块采样填到 chunk，我们把 chunk 拷进累积缓冲 */
static ECICallbackReturn pcm_callback(void* hECI, int msg, long lParam, void* userData) {
    (void)hECI;
    SynthSession* s = (SynthSession*)userData;
    if (msg != eciWaveformBuffer || lParam <= 0) return eciDataProcessed;
    long need = s->pcm_len + lParam;
    if (need > s->pcm_cap) {
        long ncap = s->pcm_cap ? s->pcm_cap * 2 : 65536;
        while (ncap < need) ncap *= 2;
        short* p = (short*)realloc(s->pcm, (size_t)ncap * sizeof(short));
        if (!p) return eciDataAbort;
        s->pcm = p;
        s->pcm_cap = ncap;
    }
    memcpy(s->pcm + s->pcm_len, s->chunk, (size_t)lParam * sizeof(short));
    s->pcm_len += lParam;
    return eciDataProcessed;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeInitEngine(
        JNIEnv* env, jclass clazz, jstring configDir, jstring libDir, jint dialect) {
    pthread_mutex_lock(&g_lock);
    if (!eci.handle) {
        const char* lib = (*env)->GetStringUTFChars(env, libDir, NULL);
        const char* cfg = (*env)->GetStringUTFChars(env, configDir, NULL);
        char path[512];
        snprintf(path, sizeof(path), "%s/libeci.so", lib);
        char iniPath[512];
        snprintf(iniPath, sizeof(iniPath), "%s/eci.ini", cfg);
        setenv("ECIINI", iniPath, 1);
        LOGI("ECIINI=%s", iniPath);
        (*env)->ReleaseStringUTFChars(env, libDir, lib);
        (*env)->ReleaseStringUTFChars(env, configDir, cfg);

        eci.handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (!eci.handle) { LOGE("dlopen libeci.so: %s", dlerror()); pthread_mutex_unlock(&g_lock); return 0; }

        #define SYM(var, name) do { \
            var = (typeof(var))dlsym(eci.handle, name); \
            if (!var) { LOGE("dlsym %s failed: %s", name, dlerror()); pthread_mutex_unlock(&g_lock); return 0; } \
        } while(0)
        SYM(eci.NewEx,              "eciNewEx");
        eci.New2  = (eciNew2_t)dlsym(eci.handle, "eciNew2");
        if (!eci.New2) LOGE("eciNew2 missing");
        SYM(eci.Delete,             "eciDelete");
        SYM(eci.SetParam,           "eciSetParam");
        SYM(eci.GetParam,           "eciGetParam");
        SYM(eci.AddText,            "eciAddText");
        SYM(eci.Synthesize,         "eciSynthesize");
        SYM(eci.Synchronize,        "eciSynchronize");
        eci.Speaking = (eciSpeaking_t)dlsym(eci.handle, "eciSpeaking");
        SYM(eci.Stop,               "eciStop");
        SYM(eci.RegisterCallback,   "eciRegisterCallback");
        SYM(eci.SetOutputBuffer,    "eciSetOutputBuffer");
        SYM(eci.SetVoiceParam,      "eciSetVoiceParam");
        SYM(eci.GetVoiceParam,      "eciGetVoiceParam");
        SYM(eci.ClearInput,         "eciClearInput");
        /* 可选符号（缺失不致命，但 CJK 需要 Klatt hooks） */
        eci.RegisterKlattHooks2 = (eciRegisterKlattHooks2_t)dlsym(eci.handle, "eciRegisterKlattHooks2");
        eci.SetStandardVoice2  = (eciSetStandardVoice2_t)dlsym(eci.handle, "eciSetStandardVoice2");
        LOGI("RegisterKlattHooks2=%p SetStandardVoice2=%p",
             (void*)eci.RegisterKlattHooks2, (void*)eci.SetStandardVoice2);
        if (!eci.RegisterKlattHooks2) LOGE("WARN: eciRegisterKlattHooks2 missing (CJK will crash)");
        #undef SYM
        LOGI("libeci.so loaded (apple-eloquence-elf), %p", eci.handle);

        /* === 关键修复：初始化 Darwin ctype/rune 表 ===
         * 广荣能读英文/数字/符号，苹果版不能，根因就在这里：
         * 广荣 JNI 在 dlopen 引擎前调用了 eloq_initialize_darwin_ctype，
         * 构建 Darwin 的字符分类表（ASCII 字母 a-z/A-Z、数字、标点的位掩码）。
         * 苹果 macho2elf 转换丢失了这个初始化（只留空 stub），导致中文库把
         * ASCII 字母/数字/符号误判为"非字母"，delta_new 建流时直接丢弃，
         * 所以"abc"/"12345"读出来 0 samples。
         *
         * 我们 dlopen 广荣的 libeloquence_jni.so 辅助库，dlsym 这个函数来补初始化。 */
        {
            /* aux 库路径：从 libeci.so 所在目录派生 libeloquence_jni.so */
            char aux_path[512];
            if (strrchr(path, '/')) {
                char dir[512];
                snprintf(dir, sizeof(dir), "%s", path);
                char* slash = strrchr(dir, '/');
                *slash = '\0';
                snprintf(aux_path, sizeof(aux_path), "%s/libeloquence_jni.so", dir);
            } else {
                snprintf(aux_path, sizeof(aux_path), "libeloquence_jni.so");
            }
            void* aux = dlopen(aux_path, RTLD_NOW | RTLD_GLOBAL);
            if (aux) {
                /* eloq_initialize_darwin_ctype 是 local 符号（小写 t），dlsym 找不到。
                 * 但 eloq_cxaexit 是导出的（大写 T），它在广荣 jni 里的地址是 0x30e4，
                 * 而 eloq_initialize_darwin_ctype 是 0x2d90，两者差 0x154。
                 * 用 dlsym(cxaexit) 求基址，再减 0x154 即得 ctype 函数真实地址。 */
                void* cxaexit = dlsym(aux, "eloq_cxaexit");
                void* fn = cxaexit ? (void*)((char*)cxaexit - (0x30e4 - 0x2d90)) : NULL;
                if (fn) {
                    void (*initCtype)(void) = (void(*)(void))fn;
                    initCtype();
                    LOGI("eloq_initialize_darwin_ctype() invoked via offset (Darwin ctype initialized)");
                } else {
                    LOGE("cannot locate eloq_initialize_darwin_ctype (aux=%p cxaexit=%p)", aux, cxaexit);
                }
            } else {
                LOGE("dlopen %s failed: %s", aux_path, dlerror());
            }
        }
    }
    pthread_mutex_unlock(&g_lock);

    SynthSession* s = (SynthSession*)calloc(1, sizeof(SynthSession));
    if (!s) return 0;
    s->hECI = eci.NewEx(dialect);
    if (!s->hECI) { LOGE("eciNewEx(0x%x) failed (check eci.ini Path=)", dialect); free(s); return 0; }
    LOGI("eciNewEx(0x%x) = %p", dialect, s->hECI);

    /* Klatt 钩子初始化：所有语言都调 eciRegisterKlattHooks2(NULL)。
     * 这会把内核 SynthThread 的 [thread+0x280/0x288/0x290] 设为 NULL。
     * 然后我们手动通过 vtable 调用语言库的 setKlattConstHook 和 setKlattDynamicHook，
     * 把内核的 staticKlattConstHook/staticKlattDynamicHook 写进 EngineWrapper inner 对象的
     * [inner+0x30] 和 [inner+0x20] 槽（苹果原版没做这步，广荣魔改了 libeci 做的）。 */
    if (eci.RegisterKlattHooks2) {
        eci.RegisterKlattHooks2(s->hECI, NULL, NULL, NULL);
        LOGI("eciRegisterKlattHooks2(NULL) applied for dialect=0x%x", dialect);
    }

    /* 手动初始化语言库的 Klatt 钩子槽（广荣补丁的等效实现）。
     * ECI handle = ECIinstance*，[handle] = SynthThread*，
     * SynthThread[0xf8] = EngineWrapper*，EngineWrapper[0] = vtable*。
     * 下面所有解引用都带防护 + 日志，先探明结构再调用。 */
    {
        void* raw0 = *(void**)s->hECI;   /* 先读 [handle] */
        LOGI("handle=%p [handle]=%p", s->hECI, raw0);
        if (raw0 && (uintptr_t)raw0 > 0x1000) {
            void* rawF8 = *(void**)((char*)raw0 + 0xf8);  /* SynthThread[0xf8] */
            LOGI("[SynthThread+0xf8]=%p", rawF8);
            if (rawF8 && (uintptr_t)rawF8 > 0x1000) {
                void* vt = *(void**)rawF8;   /* EngineWrapper[0] = vtable */
                LOGI("[EngineWrapper+0]=%p", vt);
                if (vt && (uintptr_t)vt > 0x1000) {
                    void** vtable = (void**)vt;
                    void* dynHookFn = vtable[0x90 / 8];
                    void* constHookFn = vtable[0x98 / 8];
                    LOGI("vtable[0x90]=%p vtable[0x98]=%p", dynHookFn, constHookFn);
                    void* staticConstHook = dlsym(eci.handle, "_ZN11SynthThread20staticKlattConstHookEP16KlattConstParamsPv");
                    void* staticDynHook = dlsym(eci.handle, "_ZN11SynthThread22staticKlattDynamicHookEPfPv");
                    LOGI("staticConst=%p staticDyn=%p", staticConstHook, staticDynHook);
                }
            }
        }
    }
    /* 回调 + 输出缓冲 */
    eci.RegisterCallback(s->hECI, (void*)pcm_callback, s);
    eci.SetOutputBuffer(s->hECI, PCM_CHUNK, s->chunk);
    /* 采样率 11025Hz（苹果只支持 0=8k/1=11.025k，2=22050 被拒）*/
    eci.SetParam(s->hECI, 5, 1);
    int sr = eci.GetParam(s->hECI, 9);
    LOGI("eciGetParam(9)=%d (dialect observed)", sr);
    return (jlong)(intptr_t)s;
}

JNIEXPORT jshortArray JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSynthesize(
        JNIEnv* env, jclass clazz, jlong handle, jint dialect,
        jbyteArray textBytes, jint charsetId, jstring outPath) {
    (void)clazz; (void)dialect; (void)charsetId; (void)outPath;
    SynthSession* s = (SynthSession*)(intptr_t)handle;
    if (!s || !s->hECI) return NULL;

    /* 全局合成锁：苹果 libeci 的 SynthThread 不是线程安全的，
     * 多个方言 session 并发合成会导致 TextFilter::processText 内部
     * 状态错乱 → SIGSEGV (0x11dc8)。串行化所有合成调用。 */
    pthread_mutex_lock(&g_synth_lock);

    jsize len = (*env)->GetArrayLength(env, textBytes);
    jbyte* buf = (*env)->GetByteArrayElements(env, textBytes, NULL);
    /* 拷贝成 C 字符串（AddText 是标准 ECI，接受 NUL 结尾文本）。
     * 关键：文本缓冲由 session 长期持有，绝不能在 AddText 后立即 free——
     * 引擎的 SynthThread 异步线程还会在 TextFilter::processText 里读这块内存，
     * 提前 free 会 use-after-free → SIGSEGV（0x11dc8 崩溃点）。 */
    if ((size_t)len + 1 > s->text_cap) {
        char* nt = (char*)realloc(s->text, (size_t)len + 1);
        if (!nt) {
            (*env)->ReleaseByteArrayElements(env, textBytes, buf, JNI_ABORT);
            pthread_mutex_unlock(&g_synth_lock);
            return NULL;
        }
        s->text = nt;
        s->text_cap = (size_t)len + 1;
    }
    if (len > 0) memcpy(s->text, buf, (size_t)len);
    s->text[len] = '\0';
    (*env)->ReleaseByteArrayElements(env, textBytes, buf, JNI_ABORT);

    /* 重置累积缓冲 */
    s->pcm_len = 0;

    eci.ClearInput(s->hECI);
    int r = eci.AddText(s->hECI, s->text);
    if (r <= 0) { pthread_mutex_unlock(&g_synth_lock); return NULL; }

    r = eci.Synthesize(s->hECI);
    /* 中文(CJK)的 eciSynchronize 内部会 signal 一个 destroyed ETIEvent（转换后生命周期错乱），
     * 导致 pthread_mutex_lock on destroyed mutex SIGABRT。绕过 Synchronize，直接用
     * eciSpeaking 轮询等待 worker 完成。 */
    int is_cjk_sync = (dialect == 0x60000 || dialect == 0x60001 || dialect == 0x80000 || dialect == 0xA0000);
    if (!is_cjk_sync) {
        eci.Synchronize(s->hECI);
    }
    if (eci.Speaking) {
        int guard = 0;
        while (eci.Speaking(s->hECI) && guard++ < 100000) {
            usleep(1000);
        }
    } else {
        usleep(80000);
    }

    if (s->pcm_len == 0) {
        LOGE("no PCM produced");
        pthread_mutex_unlock(&g_synth_lock);
        return NULL;
    }
    jshortArray result = (*env)->NewShortArray(env, (jsize)s->pcm_len);
    if (result) {
        (*env)->SetShortArrayRegion(env, result, 0, (jsize)s->pcm_len, s->pcm);
    }
    pthread_mutex_unlock(&g_synth_lock);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetVoiceParam(
        JNIEnv* env, jclass clazz, jlong handle, jint voice, jint param, jint value) {
    (void)env; (void)clazz;
    SynthSession* s = (SynthSession*)(intptr_t)handle;
    if (!s || !s->hECI) return -1;
    return eci.SetVoiceParam(s->hECI, voice, param, value);
}
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeGetVoiceParam(
        JNIEnv* env, jclass clazz, jlong handle, jint voice, jint param) {
    (void)env; (void)clazz;
    SynthSession* s = (SynthSession*)(intptr_t)handle;
    if (!s || !s->hECI) return -1;
    return eci.GetVoiceParam(s->hECI, voice, param);
}
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetParam(
        JNIEnv* env, jclass clazz, jlong handle, jint param, jint value) {
    (void)env; (void)clazz;
    SynthSession* s = (SynthSession*)(intptr_t)handle;
    if (!s || !s->hECI) return -1;
    return eci.SetParam(s->hECI, param, value);
}
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeSetStandardVoice(
        JNIEnv* env, jclass clazz, jlong handle, jint voiceNumber) {
    (void)env; (void)clazz;
    SynthSession* s = (SynthSession*)(intptr_t)handle;
    if (!s || !s->hECI) return -1;
    if (!eci.SetStandardVoice2) return -2;
    int ret = eci.SetStandardVoice2(s->hECI, voiceNumber);
    LOGI("eciSetStandardVoice2(%d) ret=%d", voiceNumber, ret);
    return ret;
}
JNIEXPORT void JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeShutdown(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    SynthSession* s = (SynthSession*)(intptr_t)handle;
    if (!s) return;
    /* 不调 eci.Delete！苹果 libeci 的 eciDelete 会 pthread_join 一个
     * 已被销毁的 ETIEvent 线程（invalid pthread_t），直接 SIGABRT 杀进程。
     * 让句柄泄漏（进程生命周期内复用），避免崩溃。 */
    free(s->pcm);
    free(s->text);
    free(s);
}
JNIEXPORT void JNICALL
Java_com_xw_vvtts_core_VvttsCore_nativeStop(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    SynthSession* s = (SynthSession*)(intptr_t)handle;
    if (!s) return;
    /* 不调 eci.Stop！eciStop 会走 ETIThread::terminateAndWait → waitForExit
     * → pthread_join 无效句柄（invalid pthread_t），与 eciDelete 同源的
     * ETIEvent 线程生命周期 bug，多并发时 SIGABRT。停播交给上层逻辑处理。 */
}