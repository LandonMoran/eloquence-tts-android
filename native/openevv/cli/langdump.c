#include <stdio.h>
#include <stdint.h>
#include "delta_lang.h"
#include "eci.h"

int main(void) {
    int i;
    void *h0 = eciNewEx(0x10000);
    void *h1 = eciNewEx(0x60000);
    printf("handles: %p %p\n", h0, h1);
    for (i =  0; delta_languages[i] != 0; i++) {
        const delta_language *l = delta_languages[i];
        const char *r0 = (l->rules && l->rule_count > 0) ? (l->rules[0].name ? l->rules[0].name : "(anon)") : "(none)";
        printf("tag=%s id=0x%lx state=%d rules=%d code=%d imm=%d map=%d entry=%d nat=%d st=%d ini=%d first=[%s]\n",
               l->tag, (long)l->id, (int)l->state_bytes, (int)l->rule_count,
               !!l->rule_code, !!l->rule_imm, !!l->rule_map, !!l->rule_entry, !!l->rule_native,
               (int)l->rule_sym_count, ! !l->ini, r0);
    }
    return 0;
}