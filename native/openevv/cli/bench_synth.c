/* bench_synth.c -- host timing harness: does openevv et_synthesize pace to
 * real time?  Mirrors vvtts_core.c's exact call sequence and measures
 * wall-clock per utterance vs. audio duration produced. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "eci.h"

/* The JNI bridge drives the engine through the plain engine names
 * (jni/vvtts_core.c declares them extern): keep this harness on the same
 * call path. */
extern int et_insertIndex(void *h, long index);
extern int et_addText(void *h, const char *text);
extern int et_synthesize(void *h);

#define APP_SAMPLES 4096

static short chunk[APP_SAMPLES];
static long total_samples = 0;

/* Message handler mirrors jni/vvtts_core.c vv_cb: the PCM samples arrive in
 * the message payload. */
static int ECICALL on_chunk(ECIHand h, ECIMessage message, int n, void *data)
{
    (void)h; (void)data;
    if (message == eciWaveformBuffer && n > 0)
        total_samples += n;
    return eciDataProcessed; /* MUST signal processed or the engine won't let go */
}

static double now_sec(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(void)
{
    ECIHand h = eciNewEx(0x10000); /* enus -- same constant the bridge ships */
    if (!h) { fprintf(stderr, "eciNewEx failed\n"); return 1; }

    eciRegisterCallback(h, on_chunk, NULL);
    eciSetOutputBuffer(h, APP_SAMPLES, chunk);
    eciSetParam(h, eciSampleRate, 1); /* 11025 Hz */

    const char *utterances[] = {
        "Settings.",
        "Open in new tab.",
        "The weather today is sunny with a high of seventy two degrees.",
        "Notification from Messages, you have three unread messages.",
    };
    int n_utt = sizeof(utterances) / sizeof(utterances[0]);

    /* Three passes: back-to-back like TalkBack swipes. */
    for (int pass = 0; pass < 3; pass++) {
        for (int i = 0; i < n_utt; i++) {
            total_samples = 0;
            double t0 = now_sec();

            eciClearInput(h);
                        et_insertIndex(h, 4242);
                        et_addText(h, utterances[i]);
                        et_synthesize(h);
                        int waited = 0;
                        while (eciSpeaking(h) && waited < 60000) {
                            struct timespec ts = {0, 2000000}; /* 2ms, same as vv_wait_till_done */
                            nanosleep(&ts, NULL);
                            waited += 2;
                        }
                        if (waited >= 60000) printf("  (SPEAKING NEVER CLEARED in 60s!)\n");
                        fflush(stdout);

            double wall = now_sec() - t0;
            double audio_sec = (double)total_samples / 11025.0;
            printf("pass %d utt %d: wall=%7.3fs  audio=%.3fs  ratio=%.2f  samples=%ld\n",
                   pass, i, wall, audio_sec, wall / (audio_sec > 0 ? audio_sec : 1e-9),
                   total_samples);
        }
    }

    /* Long continuous read like TalkBack reading a page. */
    char *longtext = malloc(4096);
    if (!longtext) return 1;
    strcpy(longtext, "This is a long paragraph that TalkBack would read continuously. ");
    for (int i = 0; i < 14; i++)
        strcat(longtext, "Swiping through the screen reads every element in a row. ");
    total_samples = 0;
    double t0 = now_sec();
    eciClearInput(h);
    et_insertIndex(h, 4242);
    et_addText(h, longtext);
    et_synthesize(h);
    int waited = 0;
    while (eciSpeaking(h) && waited < 60000) {
        struct timespec ts = {0, 2000000};
        nanosleep(&ts, NULL);
        waited += 2;
    }
    if (waited >= 60000) printf("  (SPEAKING NEVER CLEARED in 60s!)\n");
    fflush(stdout);
    double wall = now_sec() - t0;
    double audio_sec = (double)total_samples / 11025.0;
    printf("LONG:   wall=%7.3fs  audio=%.3fs  ratio=%.2f  samples=%ld\n",
           wall, audio_sec, wall / (audio_sec > 0 ? audio_sec : 1e-9), total_samples);

    /* Stress: 40 back-to-back short utterances -- does the engine stay healthy?. */
    int broke = 0;
    double t_last = now_sec();
    for (int i = 0; i < 40; i++) {
        total_samples = 0;
        double t0 = now_sec();
        eciClearInput(h);
        et_insertIndex(h, 4242);
        et_addText(h, "Settings.");
        et_synthesize(h);
        int waited = 0;
        while (eciSpeaking(h) && waited < 30000) {
            struct timespec ts;
            ts.tv_sec = 0; ts.tv_nsec = 2000000L; /* 2ms */
            nanosleep(&ts, NULL);
            waited += 2;
        }
        if (waited >= 30000 || !eciSpeaking(h) && total_samples == 0) {
            printf("STRESS broke at %d: waited=%d samples=%ld\n", i, waited, total_samples);
            broke = 1; break;
        }
    }
    if (!broke) printf("STRESS OK: 40 utterances, last wall=%.3fs audio=%.3fs ratio=%.2f\n",
                              now_sec() - t_last, (double)total_samples / 11025.0,
                              (now_sec() - t_last) / ((double)total_samples / 11025.0 > 0 ? (double)total_samples / 11025.0 : 1e-9));

    free(longtext);
    eciDelete(h);
    return 0;
}