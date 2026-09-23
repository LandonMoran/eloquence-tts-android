/*
 * chs_smoke.c -- CI smoke for the Chinese oracle path:
 *   1. the engine still instantiates 0x60000 (the bridge whitelist and
 *      eciNewEx stay alive now chs is linked),
 *   2. the GB18030 walk + oracle lookup produce real, non-silent PCM
 *      (the same code the JNI bridge runs on-device).
 * Built host-side on ubuntu-24.04-arm against the same openevv archive
 * (with lang/chs) and the same jni/chs_oracle_synth.c the APK ships.
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>

#include "eci.h"
#include "chs_oracle_synth.h"

int main(void) {
    /* "ni hao"  (the two Han chars in GB18030 */
    static const unsigned char text[] = { 0xC4, 0xE3, 0xBA, 0xC3 };

    ECIHand h = eciNewEx(0x60000);
    if (!h) {
        fprintf(stderr, "CHS SMOKE FAIL: eciNewEx(0x60000) returned NULL\n");
        return 1;
    }

    short *pcm = NULL;
    size_t n = chs_build_pcm(text, sizeof text, &pcm);
    if (n == 0 || !pcm) {
        fprintf(stderr, "CHS SMOKE FAIL: no oracle PCM for 你好\n");
        return 1;
    }
    size_t voiced = 0, i;
    for (i = 0; i < n; i++) {
        if (pcm[i] != 0) { voiced = 1; break; }
    }
    if (!voiced) {
        fprintf(stderr, "CHS SMOKE FAIL: oracle PCM all-zero\n");
        return 1;
    }
    printf("CHS SMOKE OK: engine 0x60000 instantiated; 你好 = %zu samples, non-silent\n", n);
    free(pcm);
    return 0;
}