/* Mandarin Chinese settings data, hand-stubbed: the active dictionary
   is empty until the ROM lift's lex tables are bound here (see
   oracle/lpta-remake.md).  The fence/link setup mirrors the other
   languages; the data tables will grow from the Apple module's. */

#include <stdlib.h>

#include "delta.h"
#include "delta_lang.h"
#include "delta_rules_c.h"
#include "evv_arena.h"

static const unsigned char actent_store[1] = { 0 };
static const unsigned char actent_all[1] = { 0 };

/* The standard logical file names every language's machine carries.  The
   last one doubles as the dictionary file name, as in the enus module. */
static const char *const lfnames[] = {
    "pgmin",
    "pgmout",
    "cmdin",
    "cmdout",
    "prompt",
    "prmout",
    "wordsin",
    "cmdfile",
    "sprout",
    "consprout",
    "execfile",
    "errorout",
    "eciChs.ddl",
};

static const char dictfile[] = "eciChs.ddl";

void chs_set_dict_new(delta_state *d)
{
    if (d != 0)
        d->set_store = EVV_REF(0);
}

void chs_set_dict_delete(delta_state *d)
{
    if (d != 0)
        d->set_store = EVV_REF(0);
}

void chs_act_dict_new(delta_state *d)
{
    delta_low_region(actent_store, sizeof actent_store);
    d->act_store = EVV_REF(delta_low_copy(actent_all, sizeof actent_all));
}

void chs_act_dict_delete(delta_state *d)
{
    if (d != 0)
        d->act_store = EVV_REF(0);
}

void chs_link_new(delta_state *d)
{
    d->fence_room = 25;

    d->fence_chars = EVV_REF(malloc(10));
    d->fence_chars_base = d->fence_chars;
    if (d->fence_chars == 0) { delta_delete(d); return; }
    d->fence_index = EVV_REF(malloc(10));
    d->fence_index_base = d->fence_index;
    if (d->fence_index == 0) { delta_delete(d); return; }
    d->fence_marks = EVV_REF(malloc(11));
    d->fence_marks_base = d->fence_marks;
    if (d->fence_marks == 0) { delta_delete(d); return; }

    d->nstmts = 10;
    d->lang_a = 1;
    d->lang_b = 2;
    d->lfnames = EVV_REF(delta_low_copy(lfnames, sizeof lfnames));
    d->nlfnames = 13;
    d->nsets = 0;
    d->dictfile = EVV_REF(delta_low_copy(dictfile, sizeof dictfile));
    d->nactions = 0;

    d->sets = EVV_REF(malloc(1));
    if (EVV_AT(uint8_t *, d->sets) == 0) { delta_delete(d); return; }
    d->act_table = EVV_REF(malloc(1));
    if (EVV_AT(uint8_t *, d->act_table) == 0) { delta_delete(d); return; }
}

void chs_link_delete(delta_state *d)
{
    if (d == 0)
        return;
    free(EVV_AT(uint8_t *, d->fence_chars_base));
    free(EVV_AT(uint8_t *, d->fence_index_base));
    free(EVV_AT(uint8_t *, d->fence_marks_base));
}