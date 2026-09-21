/* Mandarin Chinese global cells, hand-stubbed until the Apple module's
   variable list is lifted (see oracle/lpta-remake.md).  Order decides
   where each variable lands in the tail of delta_state. */

#include <stdint.h>

#include "delta.h"

const int8_t fin_chs_placeholder_globals_unused = 0;

const int8_t chs_delta_globals[] = {
    DG_WORD,
    DG_WORD,
    DG_LONG,
    DG_SHORT,
    DG_COMPOUND,
    DG_WORD, /* padding: tiles the declared cells to runtime state bytes */
};

const int32_t chs_delta_globals_n = 6;

const delta_compound_decl chs_delta_compounds[] = {
    { 0, 0, 0 },
};

const int32_t chs_delta_compounds_n = 0;

/* One machine of this language: the named fields plus the six global
   cells above (W,W,L,S,C,W).  Laid out as in delta_new, the cells end at
   0xd8; 0xb0 was the leaf size for a machine with no cells at all. */
const int32_t chs_delta_state_bytes = 0xd8;