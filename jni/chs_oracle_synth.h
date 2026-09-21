#ifndef CHS_ORACLE_SYNTH_H
#define CHS_ORACLE_SYNTH_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Build PCM (16-bit mono 11.025 kHz) for a GB18030 byte string from the
 * oracle bank.  Returns the sample count (0 on empty / no matches); *out
 * is a malloc'd buffer the caller frees (NULL when 0).  Unmapped or
 * malformed characters are skipped silently. */
size_t chs_build_pcm(const unsigned char *src, size_t n, short **out);

#ifdef __cplusplus
}
#endif

#endif  /* CHS_ORACLE_SYNTH_H */