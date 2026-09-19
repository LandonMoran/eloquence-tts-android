/*
 * probe2.c - Apple Eloquence ELF render gate.
 *
 * Two lanes, copied byte-for-byte from the CI-PROVEN recipe:
 *
 *   lane "new"    : exactly render_resampler_previews.c / examples/speak.c:
 *                     chdir(work_dir) -> dlopen("./eci.so") -> eciNew()
 *                     -> RegisterCallback FIRST -> SetOutputBuffer
 *                     -> AddText -> Synthesize -> Synchronize (count PCM).
 *                     eci.ini (one-line, Path relative to work_dir) picks the language.
 *
 *   lane "newEx"  : GGbond-era bridge recipe (informational:):
 *                     eciNewEx(dialect) -> eciRegisterKlattHooks2(h,0,0,0)
 *                     -> RegisterCallback -> SetOutputBuffer -> AddText
 *                     -> Synthesize -> Synchronize. The Apple avenue was marked
 *                     dead on eciNewEx zero-PCM;this lane tests whether NewEx
 *                     dialect path is alive (cleaner language switching than
 *                     ini-rewrite + eciNew()).
 *
 * usage: probe2 <work-dir> <new|newEx> [dialect-hex]
 *        text on stdin; prints `pcm\t<count>` - PCM samples delivered to cb.
 *
 * Build:  cc -O2 -o probe2 probe2.c -ldl     (include path: none; self-contained,
 *          ABI mirrors dump_engine.c / android/jni/eloquence_jni.c / sd_eloquence/src).
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef void *ECIHand;
typedef const void *ECIInputText;
typedef int (*ECICallback)(ECIHand, int msg, long lParam, void *pData);

enum { MSG_WAVEFORM = 0, MSG_PHONEME = 1 };

static long g_pcm_samples;
static long g_events;
static int cb(ECIHand h, int msg, long lParam, void *pData) {
    (void)h; (void)pData;
    if (msg == MSG_WAVEFORM && lParam > 0) { g_events++; g_pcm_samples += lParam; }
    return (int)sizeof(char);
}

int main(int argc, char **argv) {
    int rc = 1;
    if (argc < 3) { fprintf(stderr, "usage: %s <work-dir> <new|newEx> [dialect-hex]\n", argv[0]); rc = 2; goto done; }
    const char *work_dir = argv[1];
    int    use_ex    = !strcmp(argv[2], "newEx");
    long   dialect     = use_ex ? strtol(argc >  3 ? argv[3] : "0x60000", NULL, 16) : 0;

    if (chdir(work_dir) != 0) { perror("chdir"); rc =1; goto done;; }
    void *lib = dlopen("./eci.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib) { fprintf(stderr, "dlopen ./eci.so: %s\n", dlerror()); rc =1; goto done;; }

    typedef void *(*fn_eciNew)(void);
    typedef void *(*fn_eciNewEx)(int dialect);
    typedef void   (*fn_KlattHooks2)(void *, void *, void *, void *);
    typedef void   (*fn_RegisterCallback)(void *, ECICallback, void *);
    typedef int    (*fn_SetOutputBuffer)(void *, int, void *);
    typedef int    (*fn_AddText)(void *, ECIInputText);
    typedef int    (*fn_Synthesize)(void *);
    typedef int    (*fn_Synchronize)(void *);
    typedef void   (*fn_Version)(char *);

    fn_eciNew           eciNew           = (fn_eciNew)dlsym(lib, "eciNew");
    fn_eciNewEx         eciNewEx         = (fn_eciNewEx)dlsym(lib, "eciNewEx");
    fn_KlattHooks2     KlattHooks2     = (fn_KlattHooks2)dlsym(lib, "eciRegisterKlattHooks2");
    fn_RegisterCallback RegisterCallback = (fn_RegisterCallback)dlsym(lib, "eciRegisterCallback");
    fn_SetOutputBuffer   SetOutputBuffer   = (fn_SetOutputBuffer)dlsym(lib, "eciSetOutputBuffer");
    fn_AddText           AddText           = (fn_AddText)dlsym(lib, "eciAddText");
    fn_Synthesize        Synthesize        = (fn_Synthesize)dlsym(lib, "eciSynthesize");
    fn_Synchronize       Synchronize       = (fn_Synchronize)dlsym(lib, "eciSynchronize");
    fn_Version           Version           = (fn_Version)dlsym(lib, "eciVersion");
    if (!eciNew || !eciNewEx || !RegisterCallback || !SetOutputBuffer || !AddText || !Synthesize || !Synchronize) {
        fprintf(stderr, "eci.so lacks required exports\n"); rc =1; goto done;;;
    }

    void *eng = use_ex ? eciNewEx((int)dialect) : eciNew();
    if (!eng) { fprintf(stderr, "engine init failed (%s lane%s)\n", use_ex ? "eciNewEx" : "eciNew", use_ex ? "" : " (check eci.ini)"); rc =1; goto done;;; }
    if (use_ex && KlattHooks2) KlattHooks2(eng, NULL, NULL, NULL);
    if (Version) { char ver[64] = {0}; Version(ver); fprintf(stderr, "engine %s lane=%s dialect=0x%lx\n", ver, argv[2], dialect); }

    char    text[1<<20]; size_t n;
    if (use_ex) {
        /* keep console clear: read all of stdin */
    }
    n  = fread(text, 1, sizeof(text) - 1, stdin);
    text[n] = '\0';

    RegisterCallback(eng, cb, NULL);
    short chunk[4096];
    SetOutputBuffer(eng, 4096, chunk);

    int add = AddText(eng, text);
     int sy  = Synthesize(eng);
    int so  = Synchronize(eng);
    printf("pcm\t%ld\t%ld\n", g_events, g_pcm_samples);
    fprintf(stderr, "add=%d synth=%d sync=%d\n", add, sy, so);
    rc =0;
done:
    return rc;
}
