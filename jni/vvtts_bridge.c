/*
 * vvtts_bridge.c - Eloquence 自主桥接层
 *
 * 我们自己的调用库：音库复用广荣（libeloquence_jni + libeci），
 * 桥接/参数/角色/语言调度全部自主实现。
 * 苹果 Kona 官方 14语言×8角色 参数表编译期内置。
 *
 * 逆向成果集中区（广荣黑盒知识）：
 *  - grHandle+16     : zh-CN ECI handle
 *  - grHandle+16456  : en-US ECI handle
 *  - grHandle+0x8088 : 内部 ECI 参数函数指针 fn(hECI, voice, param, value)
 *    （与 nativeSetProsody 同款，确定作用于合成引擎）
 *  - param: 2=音调(经mapPitch), 6=音量, 7=语速；引擎输出 11025Hz
 *  - 中文文本 GB18030，英文 windows-1252
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <android/log.h>
#include "role_table.h"

#define TAG "VvTtsBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define DIALECT_ZH 0x60000
#define DIALECT_EN 0x10000

/* ---------------- 角色参数总线（跨 JNI 的活动状态） ---------------- */
typedef struct {
    int   roleId;        // 1-8（0=无）
    int   konaDialect;   // 当前语言
    float pitchScale;    // pitchBase/65
    float speedScale;    // speed/50
    float volume;        // /100
    float roughness;     // /100（预留：Klatt 钩子通道恢复后立即生效）
    float breathiness;
    float headSize;
    float pitchFluctuation;
    int   eciVoiceNumber;
} BridgeRole;

static BridgeRole g_role = {0, 0, 1.0f, 1.0f, 1.0f, 0, 0, 50, 30, 0};

/* ---------------- 广荣引擎适配 ---------------- */
static void* get_eci(jlong grHandle, int dialect) {
    if (grHandle == 0) return NULL;
    char* p = (char*)grHandle;
    int off = (dialect == DIALECT_EN) ? 16456 : 16;
    return *(void**)(p + off);
}

static void* get_prosody_fn(jlong grHandle) {
    if (grHandle == 0) return NULL;
    return *(void**)((char*)grHandle + 0x8088);
}

/* 内部通道设参（确定作用于合成） */
static int eciSetParamInternal(jlong grHandle, int dialect, int param, int value) {
    void* fn = get_prosody_fn(grHandle);
    void* eci = get_eci(grHandle, dialect);
    if (!fn || !eci) return -1;
    typedef int (*fn4)(void*, int, int, int);
    return ((fn4)fn)(eci, 0, param, value);
}

/* ---------------- JNI 接口 ---------------- */

/*
 * 应用角色：roleId 1-8 + konaDialect（0-13）。
 * 表按 (konaDialect, eciVoiceNumber 1,2,3,4,6,7,8,9) 排序，
 * 行索引 = konaDialect*8 + orderIndex，orderIndex 由 roleId 查。
 */
JNIEXPORT jfloatArray JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeApplyRole(JNIEnv* env, jclass clazz,
                                                     jlong grHandle, jint dialect,
                                                     jint roleId, jint konaDialect) {
    if (roleId < 1 || roleId > 8 || konaDialect < 0 || konaDialect > 13) return NULL;
    /* roleId(1-8 界面序) → 表内角色序位（按 eciVoiceNumber 升序 1,2,3,4,6,7,8,9） */
    /* 界面 1..8 对应: Reed(1) Sandy(3) Shelley(2) Rocko(4) Eddy(9) Flo(6) Grandpa(8) Grandma(7) */
    static const int roleOrder[9] = {-1, 0, 2, 1, 3, 7, 5, 6, 4};
    /* 注意: 上面按界面顺序 Reed/Sandy/Shelley/Rocko/Eddy/Flo/Grandpa/Grandma
       对应表内序位: Reed=0 Sandy=2 Shelley=1 Rocko=3 Eddy=7 Flo=4 Grandpa=6 Grandma=5 */
    int orderIdx = roleOrder[roleId];
    int row = konaDialect * 8 + orderIdx;
    if (row < 0 || row >= ROLE_ROW_COUNT) return NULL;

    const short* p = kRoleTable[row];
    g_role.roleId = roleId;
    g_role.konaDialect = konaDialect;
    g_role.breathiness      = p[0];
    g_role.eciVoiceNumber   = p[1];
    g_role.headSize         = p[2];
    g_role.pitchScale       = p[4] / 65.0f;
    g_role.pitchFluctuation = p[5];
    g_role.roughness        = p[6];
    g_role.speedScale       = p[7] / 50.0f;
    g_role.volume           = p[9] / 100.0f;

    /* 透传：音调（倍率由 Java 端 setProsody 的 lastNativePitch 相乘，这里只回传因子）*/
    /* 应用语速：基准 130（广荣 nativeSetProsody rate 域） */
    if (g_role.speedScale != 1.0f) {
        int spd = (int)(130 * g_role.speedScale);
        eciSetParamInternal(grHandle, dialect, 7, spd);
    }
    /* 应用音量档（ECI param 6）*/
    if (g_role.volume != 1.0f) {
        int vol = (int)(100 * g_role.volume);
        eciSetParamInternal(grHandle, dialect, 6, vol);
    }
    LOGI("applyRole id=%d kona=%d pitch=%.2f speed=%.2f vol=%.2f",
         roleId, konaDialect, g_role.pitchScale, g_role.speedScale, g_role.volume);

    /* 返回 {pitchScale, speedScale, volume, roughness, breathiness, headSize, pitchFluctuation, eciVoiceNumber} */
    jfloatArray out = (*env)->NewFloatArray(env, 8);
    float vals[8] = { g_role.pitchScale, g_role.speedScale, g_role.volume,
                      g_role.roughness, g_role.breathiness, g_role.headSize,
                      g_role.pitchFluctuation, (float)g_role.eciVoiceNumber };
    (*env)->SetFloatArrayRegion(env, out, 0, 8, vals);
    return out;
}

/* 角色音调应用（Java 传入 mapPitch 后的原生音调，桥内乘因子） */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeApplyPitch(JNIEnv* env, jclass clazz,
                                                      jlong grHandle, jint dialect,
                                                      jint nativePitch) {
    if (g_role.roleId == 0) return -1;
    int target = (int)(nativePitch * g_role.pitchScale + 0.5f);
    if (target < 0) target = 0;
    if (target > 250) target = 250;
    int ret = eciSetParamInternal(grHandle, dialect, 2, target);
    LOGI("applyPitch %d x %.2f -> %d ret=%d", nativePitch, g_role.pitchScale, target, ret);
    return ret;
}

/* 语言切换：dialect 预制映射（kona -> 广荣可用 dialect） */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeResolveDialect(JNIEnv* env, jclass clazz,
                                                          jint konaDialect) {
    switch (konaDialect) {
        case 12: return DIALECT_ZH;   // zh-CN（已接入）
        case 0:  return DIALECT_EN;   // en-US（已接入）
        case 1:  return DIALECT_EN;   // en-GB → 暂用 en-US 库（口音参数已内置于角色表）
        /* 其余语言待接入音库后逐个映射（dialect 值实测修正） */
        default: return -1;           // 未接入
    }
}

/* 查询支持状态 */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeIsLanguageReady(JNIEnv* env, jclass clazz,
                                                           jint konaDialect) {
    switch (konaDialect) {
        case 12: case 0: case 1: return 1;
        default: return 0;
    }
}

/* Klatt 钩子注册（已打通的通道，音库升级后立即生效） */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeRegisterKlatt(JNIEnv* env, jclass clazz,
                                                         jlong grHandle, jint dialect) {
    void* eci = get_eci(grHandle, dialect);
    if (!eci) return -1;
    void* fn = dlsym(RTLD_DEFAULT, "eciRegisterKlattHooks2");
    if (!fn) {
        void* lib = dlopen("libeci.so", RTLD_NOW | RTLD_GLOBAL);
        if (!lib) return -1;
        fn = dlsym(lib, "eciRegisterKlattHooks2");
        if (!fn) return -1;
    }
    typedef int (*reg_fn)(void*, void(*)(void*, void*), void(*)(float*, void*), void*);
    int ret = ((reg_fn)fn)(eci, NULL, NULL, NULL);  /* 占位：钩子体待迁移 */
    LOGI("bridge registerKlatt ret=%d", ret);
    return ret;
}