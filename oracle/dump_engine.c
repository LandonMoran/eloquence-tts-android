/*
 * dump_engine.c -- oracle data dumper for the converted Apple Eloquence engine.
 *
 * Runs the REAL Chinese/Taiwanese/Korean engine over arbitrary UTF-8 text and
 * captures three things per utterance, printed as tab-separated rows:
 *
 *   1. Pinyin bytes (eciGeneratePinyins, Chinese dialects only,( raw hex+ascii)
 *   2. Phoneme bytes (eciGeneratePhonemes( raw hex+ascii
 *   3. Phoneme-buffer callback messages (eciPhonemeBuffer, indicative `wsz`
 *      entries as hex( plus the PCM sample count (eciWaveformBuffer
 *
 * These dumps ARE the training data for authoring the standalone module tables:
 * hanzi -> pinyin (rom table( and pinyin/text -> phoneme sequences (rules(.
 *
 * ABI mirrors android/jni/eloquence_jni.c (MIT(, sd_eloquence/src/eci
 * (GPL-2.0-or-later( and eci.h (IBM SDK semantics. The engine needs
 * glibc >= 2.34 and an eci.ini discoverable from cwd (absolute Path= entries
 * patched to the directory holding lib/eci.so (see the CI workflow(.
 *
 * Build:  cc -O2 -o dump_engine dump_engine.c -ldl
 * Usage:   ./dump_engine <dialect-hex> < pinyin|phonemes|speak-corpus.txt
 */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef void *ECIHand;
typedef const void *ECIInputText;
typedef int (*ECICallback)(ECIHand, int msg, long lParam, void *pData);
typedef long long ll;

enum { MSG_WAVEFORM = 0, MSG_PHONEME = 1 };
enum { PARAM_SYNTHMODE = 0, PARAM_INPUTTYPE = 1, PARAM_SAMPLERATE = 5,
       PARAM_WANT_PHONEME =  7, PARAM_DIALECT =  9 };
enum { PHONEME_LEN = 4 };

/* function pointer types */
typedef ECIHand (*fn_NewEx)(int);
typedef int     (*fn_SetParam)(ECIHand, int, int);
typedef int     (*fn_AddText)(ECIHand, ECIInputText);
typedef int     (*fn_Synthesize)(ECIHand);
typedef int     (*fn_Synchronize)(ECIHand);
typedef int     (*fn_InsertIndex)(ECIHand, int);
typedef int     (*fn_Speaking)(ECIHand);
typedef int     (*fn_Stop)(ECIHand);
typedef void    (*fn_RegisterCallback)(ECIHand, ECICallback, void *);
typedef int     (*fn_SetOutputBuffer)(ECIHand, int, short *);
typedef int     (*fn_GeneratePinyins)(ECIHand, int, void *);
typedef int     (*fn_GeneratePhonemes)(ECIHand, int, void *);
typedef void    (*fn_Version)(char *);

static fn_NewEx            NewEx;
static fn_SetParam        SetParam;
static fn_AddText         AddText;
static fn_Synthesize      Synthesize;
static fn_Synchronize     Synchronize;
static fn_InsertIndex     InsertIndex;
static fn_Speaking        Speaking;
static fn_Stop            Stop;
static fn_RegisterCallback RegisterCallback;
static fn_SetOutputBuffer SetOutputBuffer;
static fn_GeneratePinyins GeneratePinyins;
static fn_GeneratePhonemes GeneratePhonemes;
static fn_Version         Version;

static void *sym(void *lib, const char *name) {
    void *p = dlsym(lib, name);
    if (!p) fprintf(stderr, "missing symbol: %s\n", name);
    return p;
}

static void hexout(const unsigned char *b, long n) {
    long i;
    for (i =  0; i < n; i++) printf("%02x", b[i]);
    printf("|");
    for (i =  0; i < n; i++) printf("%c", (b[i] >= 0x20 && b[i] < 0x7f) ? b[i] : '.');
}

static void ph_reply(ECIHand h, int size) {
    static unsigned char buf[8192];
    if (!GeneratePhonemes) return;
    if (size > (int)sizeof(buf)) size = (int)sizeof(buf);
    memset(buf, 0, sizeof(buf));
    GeneratePhonemes(h, size, buf);
    printf("genphon\t%d\t", size);
    hexout(buf, (size < 64) ? size : 64);
    printf("\n");
}

/* Callback capture: phoneme messages give per-frame 5-phoneme indices
 * (wsz, order as written by the engine; sz = the ASCII 4-letter names(.
 * We print both as hex so the offline fitter can test either interpretation. */
static long g_pcm_samples;
static int cb(ECIHand h, int msg, long lParam, void *pData) {
    (void)h;
    if (msg == MSG_WAVEFORM && lParam > 0) {
        g_pcm_samples += lParam;
        return 1;
    }
    if (msg == MSG_PHONEME && lParam > 0) {
        const unsigned char *d = (const unsigned char *)pData;
        long n = lParam * (PHONEME_LEN + 2); /* sz[5] + wsz[5] per frame */
        printf("phbuf\t%ld\t", lParam);
        hexout(d, (n < 160) ? n : 160);
                printf("\n");
                fflush(stdout);
                return 1;
    }
    return 1;
}

/* Interpose the Klatt dispatchers: the crashing "self" lives in an
       uninitialized object inside chs.so. Forcing the NULL path (return
       untouched params) avoids trailing a garbage hook pointer. */
    __attribute__((visibility("default")))
    void *_ZN11SynthThread20staticKlattConstHookEP16KlattConstParamsPv(void *p, void *self){
        (void)self;
        return p;
    }
    __attribute__((visibility("default")))
    void *_ZN11SynthThread22staticKlattDynamicHookEPfPv(void *f, void *self){
        (void)self;
        return f;
    }

static void run_text(ECIHand e, const char *text) {
    g_pcm_samples = 0;
    int add_r = -1;
    if (AddText) {
        add_r = AddText(e, (ECIInputText)text);
        if (getenv("DUMP_TRACE")) fprintf(stderr, "addtext=%d len=%zu\n", add_r, strlen(text));
    }
    if (getenv("DUMP_PINYIN") && GeneratePinyins) {
        static unsigned char pbuf[4096];
        memset(pbuf, 0, sizeof(pbuf));
        int r = GeneratePinyins(e, (int)strlen(text), pbuf);
        printf("pinyins\t%d\t", r);
        hexout(pbuf, 128);
        printf("\t%.20s\n", text);
    }
    if (add_r && !getenv("DUMP_NOSYNTH") && Synthesize) {
        int ii_r = InsertIndex ? InsertIndex(e, 0xFFFF) : -1; /* END_STRING_MARK */
        int sy_r = Synthesize(e);
        int so_r = Synchronize ? Synchronize(e) : -1;
        int sp_r = Speaking ? Speaking(e) : -1;
        if (getenv("DUMP_TRACE")) fprintf(stderr, "ii=%d synth=%d sync=%d speak=%d\n", ii_r, sy_r, so_r, sp_r);
        /* Bounded spin: engine status can stall without a device; never let a
         * stuck Speaking hold a row hostage (cap ~1s total). */
        if (sp_r) { int g = 0; while (Speaking(e) && g++ < 5000) usleep(200); }
        printf("pcm\t%ld\n", g_pcm_samples);
    } else if (getenv("DUMP_NOSYNTH")) {
        printf("pcm\t0\n");
    }
    if (GeneratePhonemes) ph_reply(e, 512);
    fflush(stdout);
}

static void klatt_const_noop(void *c, void *u);
static void klatt_frame_noop(void *f, void *u);

static void setup_klatt_hooks(void) {
    void *ch = dlopen("lib/chs.so", RTLD_NOW | RTLD_NOLOAD);
    if (!ch) ch = dlopen("lib/chs.so", RTLD_NOW);
    if (!ch) { fprintf(stderr, "chs.so reopen failed: %s\n", dlerror()); return; }
    void (*sc)(void (*)(void *, void *), void *) = (void(*)(void (*)(void*,void*),void*))dlsym(ch, "engsynSetKlattConstHook");
    void (*sd)(void (*)(void *, void *), void *) = (void(*)(void (*)(void*,void*),void*))dlsym(ch, "engsynSetKlattDynamicHook");
    if (sc && sd) {
        sc(klatt_const_noop, NULL);
        sd(klatt_frame_noop, NULL);
        fprintf(stderr, "chs klatt hook slots set\n");
    } else {
        fprintf(stderr, "chs klatt hooks missing: const=%p dyn=%p\n", (void *)sc, (void *)sd);
    }
}

typedef int (*fn_RegKlattHooks2)(void *, void (*)(void *, void *), void (*)(void *, void *), void *);
static void klatt_const_noop(void *c, void *u) { (void)c; (void)u; }
static void klatt_frame_noop(void *f, void *u) { (void)f; (void)u; }

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <dialect-hex> < in.txt\n", argv[0]);
        return 2;
    }
    long dialect = strtol(argv[1], NULL, 16);
    void *lib = dlopen("./lib/eci.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 1; }
    NewEx            = (fn_NewEx)sym(lib, "eciNewEx");
    SetParam        = (fn_SetParam)sym(lib, "eciSetParam");
    AddText         = (fn_AddText)sym(lib, "eciAddText");
    Synthesize      = (fn_Synthesize)sym(lib, "eciSynthesize");
    Synchronize     = (fn_Synchronize)sym(lib, "eciSynchronize");
    InsertIndex     = (fn_InsertIndex)sym(lib, "eciInsertIndex");
    Speaking        = (fn_Speaking)sym(lib, "eciSpeaking");
    Stop            = (fn_Stop)sym(lib, "eciStop");
    RegisterCallback= (fn_RegisterCallback)sym(lib, "eciRegisterCallback");
    SetOutputBuffer = (fn_SetOutputBuffer)sym(lib, "eciSetOutputBuffer");
    GeneratePinyins = (fn_GeneratePinyins)sym(lib, "eciGeneratePinyins");
    GeneratePhonemes= (fn_GeneratePhonemes)sym(lib, "eciGeneratePhonemes");
    Version          = (fn_Version)sym(lib, "eciVersion");
    if (!NewEx || !SetParam || !AddText || !Synthesize || !RegisterCallback || !SetOutputBuffer) {
        fprintf(stderr, "eci.so lacks required exports\n");
        return 1;
    }
    char *eci = NewEx((int)dialect);
    if (!eci) { fprintf(stderr, "eciNewEx(0x%lx) failed\n", dialect); return 1; }
    if (Version) { char ver[64] = {0}; Version(ver); fprintf(stderr, "engine %s dialect 0x%lx\n", ver, dialect); }
    if (getenv("DUMP_KLATT_HOOKS")) {
        setup_klatt_hooks();
    } else {
        fprintf(stderr, "klatt hooks skipped (phoneme-extract mode)\n");
    }
    SetParam(eci, PARAM_SAMPLERATE, 1); /* 11025 Hz, exactly the JNI bridge path */
    SetParam(eci, PARAM_SYNTHMODE,  1); /* NVDA-driver equivalent: synch to callback buffer */
    SetParam(eci, PARAM_INPUTTYPE,  1); /* text input (per IBM SDK eci.h( */
    if (getenv("DUMP_WANT_PHONEME")) SetParam(eci, PARAM_WANT_PHONEME, 1);
    short chunk[4096];
    RegisterCallback(eci, cb, NULL);
    SetOutputBuffer(eci, 4096, chunk);
    char line[65536];
    while (fgets(line, sizeof(line), stdin)) {
        line[strcspn(line, "\r\n")] = '\0';
        if (!line[0]) continue;
        run_text(eci, line);
    }
    return 0;
}