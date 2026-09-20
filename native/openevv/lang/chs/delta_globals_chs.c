/* Mandarin Chinese global cells, hand-stubbed until the Apple module's
   variable list is lifted (see oracle/lpta-remake.md).  Order decides
   where each variable lands in the tail of delta_state. */

#include <stdint.h>

#include "delta.h"

const int8_t fin_chs_placeholder_globals_unused = 0;

const int8_t chs_delta_globals[] = {
    0,
};

const int32_t chs_delta_globals_n = 1;

const delta_compound_decl chs_delta_compounds[] = {
    { 0, 0, 0 },
};

const int32_t chs_delta_compounds_n = 0;

/* One machine of this language: the named fields plus the single
   global cell above.  The rules' cells end at 176 as lifted. */
const int32_t chs_delta_state_bytes = 0xb0;