/* chs DeltaProc entries and const store: no-op / minimal until the
   ROM lift binds the proc rules this stub language still lacks ( see
   oracle/lpta-remake.md ).  The engine treats a non-zero return from
   these as an engine error (e.g. es_engsynStart's
   `else if (DeltaProc_start(d)) setEngsynError(d, ERR_ENGINE)`),
   so the stubs answer success as nought. */

#include "delta_rules_chs.h"
#include "delta_lang.h"

int32_t chs_DeltaProc_start(int32_t d) { (void)d; return 0; }
int32_t chs_DeltaProc_end(int32_t d) { (void)d; return 0; }
int32_t chs_DeltaProc_flush(int32_t d) { (void)d; return 0; }
int32_t chs_DeltaProc_process_sentences(int32_t d) { (void)d; return 0; }
int32_t chs_DeltaProc_process_remaining(int32_t d) { (void)d; return 0; }
int32_t chs_DeltaProc_main(int32_t d) { (void)d; return 0; }

/* The byte blocks the rules name by address. chs_evv_data is the
   lift's only block so far;the lex tables grow here ( after
   oracle/lpta-remake.md ).  The array ends where delta_syms_bind
   stops: a store whose at pointer is null. */
extern uint8_t chs_evv_data[92];

const delta_store chs_delta_const_store[] ={
    { chs_evv_data, sizeof chs_evv_data },
    { 0,  0 }
};