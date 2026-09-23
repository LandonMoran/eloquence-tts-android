/*
 * vvtts_bridge.c - Eloquence self-developed bridge layer
 *
 * Our own calling layer:the voice library reuses Guangrong(libeloquence_jni + libeci),
 * with the bridge/params/voices/language dispatch all implemented by us.
 * Apple Kona's official 14-language x 8-voice param table is baked in at compile time.
 *
 * Central hub for reverse-engineering findings(Guangrong black-box knowledge):
 *  - grHandle+16     : zh-CN ECI handle
 *  - grHandle+16456  : en-US ECI handle
 *  - grHandle+0x8088 : internal ECI param function pointer fn(hECI, voice, param, value
 *    (same family as nativeSetProsody;confirmed to act on the synth engine)
 *  - param:2=pitch (via mapPitch),6=volume,,7=speed;engine outputs  11025Hz
 *  - Chinese text GB18030;English windows-1252
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

/* ---------------- voice-param bus(cross-JNI live state) ---------------- */
typedef struct {
    int   roleId;        // 1-8 (0=none
    int   konaDialect;   // current language
    float pitchScale;    // pitchBase/65
    float speedScale;    // speed/50
    float volume;        // /100
    float roughness;     // /100 (reserved:takes effect as soon as the Klatt-hook channel is restored
    float breathiness;
    float headSize;
    float pitchFluctuation;
    int   eciVoiceNumber;
} BridgeRole;

static BridgeRole g_role = {0, 0, 1.0f, 1.0f, 1.0f, 0, 0, 50, 30, 0};

/* ---------------- Guangrong engine adaptation ---------------- */
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

/* Setting params through the internal channel(confirmed to affect synthesis) */
static int eciSetParamInternal(jlong grHandle, int dialect, int param, int value) {
    void* fn = get_prosody_fn(grHandle);
    void* eci = get_eci(grHandle, dialect);
    if (!fn || !eci) return -1;
    typedef int (*fn4)(void*, int, int, int);
    return ((fn4)fn)(eci, 0, param, value);
}

/* ---------------- JNI interface ---------------- */

/*
 * Apply voice:roleId 1-8 + konaDialect(0-13).
 * Table is ordered by(konaDialect, eciVoiceNumber 1,2,3,4,6,7,8,9),
 * row index ＝ konaDialect*8 + orderIndex with orderIndex looked up from roleId.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeApplyRole(JNIEnv* env, jclass clazz,
                                                     jlong grHandle, jint dialect,
                                                     jint roleId, jint konaDialect) {
    if (roleId < 1 || roleId > 8 || konaDialect < 0 || konaDialect > 13) return NULL;
     /* roleId(1-8 UI order)-> in-table voice position(ascending eciVoiceNumber 1,2,3,4,6,7,8,9) */
     /* UI 1..8 map to:Reed(1)Sandy(3)Shelley(2)Rocko(4)Eddy(9)Flo(6)Grandpa(8)Grandma(7) */
    static const int roleOrder[9] = {-1, 0, 2, 1, 3, 7, 5, 6, 4};
     /* Note:the above is in UI order Reed/Sandy/Shelley/Rocko/Eddy/Flo/Grandpa/Grandma
         mapping to in-table positions:Reed=0 Sandy=2 Shelley=1 Rocko=3 Eddy=7 Flo=4 Grandpa=6 Grandma=5 */
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

    /* Pass-through:pitch(the factor is multiplied by lastNativePitch from Java-side setProsody;we only relay the factor) */
    /* Apply speed:baseline  130(Guangrong's nativeSetProsody rate field) */
    if (g_role.speedScale != 1.0f) {
        int spd = (int)(130 * g_role.speedScale);
        eciSetParamInternal(grHandle, dialect, 7, spd);
    }
    /* Apply volume level(ECI param  6) */
    if (g_role.volume != 1.0f) {
        int vol = (int)(100 * g_role.volume);
        eciSetParamInternal(grHandle, dialect, 6, vol);
    }
    LOGI("applyRole id=%d kona=%d pitch=%.2f speed=%.2f vol=%.2f",
         roleId, konaDialect, g_role.pitchScale, g_role.speedScale, g_role.volume);

    /* returns {pitchScale, speedScale, volume, roughness, breathiness, headSize, pitchFluctuation, eciVoiceNumber} */
    jfloatArray out = (*env)->NewFloatArray(env, 8);
    float vals[8] = { g_role.pitchScale, g_role.speedScale, g_role.volume,
                      g_role.roughness, g_role.breathiness, g_role.headSize,
                      g_role.pitchFluctuation, (float)g_role.eciVoiceNumber };
    (*env)->SetFloatArrayRegion(env, out, 0, 8, vals);
    return out;
}

/* Apply voice pitch(native pitch after Java's mapPitch;the bridge multiplies the factor) */
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

/* Language switch:dialect preset mapping(kona -> Guangrong-usable dialect) */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeResolveDialect(JNIEnv* env, jclass clazz,
                                                          jint konaDialect) {
    switch (konaDialect) {
        case 12: return DIALECT_ZH;   // zh-CN (wired in
        case 0:  return DIALECT_EN;   // en-US (wired in
        case 1:  return DIALECT_EN;   // en-GB -> temporarily using the en-US bank(accent params baked into the voice table
        /* remaining languages map one-by-one once their voice banks are wired in(dialect values fixed by testing) */
        default: return -1;           // not wired in
    }
}

/* Check support status */
JNIEXPORT jint JNICALL
Java_com_xw_vvtts_bridge_VvttsBridge_bridgeIsLanguageReady(JNIEnv* env, jclass clazz,
                                                           jint konaDialect) {
    switch (konaDialect) {
        case 12: case 0: case 1: return 1;
        default: return 0;
    }
}

/* Klatt hook registration(the working channel;takes effect immediately after a bank upgrade) */
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
    int ret = ((reg_fn)fn)(eci, NULL, NULL, NULL);  /* placeholder:hook body yet to be migrated */
    LOGI("bridge registerKlatt ret=%d", ret);
    return ret;
}