/* probe.c -- A/B harness: does eciSetVoiceParam(speed/pitch) change PCM?
 * Mirrors the JNI bridge in eloquence-android/jni/vvtts_core.c exactly:
 * eciNewEx(0x10000); eciRegisterCallback; eciSetOutputBuffer(h,4096,chunk);
 * eciSetParam(eciSampleRate,1); then per utterance: eciClearInput;
 * et_insertIndex(h,4242); et_addText(h,text); et_synthesize; poll eciSpeaking.
 * Writes raw s16le PCM per run to /tmp/abtest/ and prints stats.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "eci.h"

extern int et_insertIndex(void *h, long index);
extern int et_addText(void *h, const char *text);
extern int et_synthesize(void *h);

#define APP_SAMPLES 4096

typedef struct {
    ECIHand hECI;
    short   chunk[APP_SAMPLES];
    short  *pcm;
    size_t  pcmLen, pcmCap;
} Session;

static int cb(ECIHand h, ECIMessage message, int param, void *data) {
    Session *s = (Session *)data;
    if (message != eciWaveformBuffer || param < 0)
        return eciDataProcessed;
    {
        size_t n = (size_t)param;
        if (n > APP_SAMPLES) n = APP_SAMPLES;
        if (s->pcmLen + n > s->pcmCap) {
            size_t cap = s->pcmCap ? s->pcmCap : 16384;
            while (cap < s->pcmLen + n) cap *= 2;
            short *p = (short *)realloc(s->pcm, cap * sizeof(short));
            if (!p) return eciDataProcessed;
            s->pcm = p;
            s->pcmCap = cap;
        }
        memcpy(s->pcm + s->pcmLen, data ? data : s->chunk, n * sizeof(short));
        s->pcmLen += n;
    }
    return eciDataProcessed;
}

static void wait_done(Session *s) {
    for (int i = 0; i < 8000 && s->hECI && eciSpeaking(s->hECI); i++) {
        struct timespec ts = {0, 2000000L};
        nanosleep(&ts, NULL);
    }
}

static void synth(Session *s, const char *text) {
    s->pcmLen = 0;
    eciClearInput(s->hECI);
    et_insertIndex(s->hECI, 4242);
    et_addText(s->hECI, text);
    et_synthesize(s->hECI);
    wait_done(s);
}

static void write_raw(const char *path, short *pcm, size_t n) {
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); exit(1); }
    fwrite(pcm, sizeof(short), n, f);
    fclose(f);
}

int main(void) {
    static const struct { const char *name; int speed; int pitch; } RUNS[] = {
        { "A_s50_p40",   50,  40 },
        { "B_s250_p40", 250,  40 },
        { "C_s50_p100",  50, 100 },
        { "D_s250_p100",250, 100 },
        { "R_s50_p40",   50,  40 },   /* repeat: determinism check */
    };
    const char *TEXT = "This is a speed and pitch test.";
    Session s;
    int i, peak;
    long sum;

    memset(&s, 0, sizeof s);
    s.hECI = eciNewEx(0x10000);
    if (!s.hECI) { fprintf(stderr, "eciNewEx failed\n"); return 1; }
    eciRegisterCallback(s.hECI, cb, &s);
    eciSetOutputBuffer(s.hECI, APP_SAMPLES, s.chunk);
    eciSetParam(s.hECI, eciSampleRate, 1);

    for (i = 0; i < (int)(sizeof RUNS / sizeof RUNS[0]); i++) {
        synth(&s, TEXT);   /* warm-up utterance at default voice first? no:
                              first run doubles as default=(50,40) */
        /* reset voice 0 to presets, then apply the run's params */
        eciCopyVoice(s.hECI, 1, 0);
        eciSetVoiceParam(s.hECI, 0, eciSpeed, RUNS[i].speed);
        eciSetVoiceParam(s.hECI, 0, eciPitchBaseline, RUNS[i].pitch);

        synth(&s, TEXT);   /* discard: params set while engine had just spoken;
                              utterance immediately after should carry them */

        synth(&s, TEXT);   /* the measured run */
        peak = 0; sum = 0;
        for (size_t j = 0; j < s.pcmLen; j++) {
            int a = s.pcm[j] < 0 ? -s.pcm[j] : s.pcm[j];
            if (a > peak) peak = a;
            sum += s.pcm[j];
        }
        {
            char path[256];
            snprintf(path, sizeof path, "/tmp/abtest/%s.raw", RUNS[i].name);
            write_raw(path, s.pcm, s.pcmLen);
        }
        printf("run %s speed=%d pitch=%d samples=%zu dur_ms=%.1f peak=%d rms=%ld\n",
               RUNS[i].name, RUNS[i].speed, RUNS[i].pitch, s.pcmLen,
               s.pcmLen * 1000.0 / 11025.0, peak,
               (long)(s.pcmLen ? (long long)sum / (long long)s.pcmLen : 0));
    }
    eciDelete(s.hECI);
    free(s.pcm);
    return 0;
}