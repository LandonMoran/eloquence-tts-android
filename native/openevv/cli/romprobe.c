#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "eci_rom.h"

extern void chs_register(void);

int main(int argc, char **argv) {
    const char *s = argc > 1 ? argv[1] : "\xd6\xd0\xce\xc4";
    chs_register();
    EvvRomMaker mk = evv_rom_maker(6, 0);
    printf("maker: %p\n", (void *)mk);
    if (!mk) return 1;
    EvvRom *r = mk(NULL);
    if (!r) { printf("make failed\n"); return 1; }
    printf("ops: %p (mbcs2Rom %p, processSentence %p)\n",
           (void *)r->ops, (void *)r->ops->mbcs2Rom, (void *)r->ops->processSentence);
    /* Drive the text pipeline exactly as the engine would. */
    char *out = NULL;
    r->ops->addText(r, s, (int32_t)strlen(s),  0);
    int32_t ans =r->ops->processSentence(r, &out, 1);
    printf("processSentence -> %d out=<%s> len=%zu\n", ans, out ? out : "(null)", out ? strlen(out) : 0);
    /* And the raw mbcs2Rom entry on its own. */
    out = NULL;
    r->ops->mbcs2Rom(r, s, &out);
    printf("mbcs2Rom out=<%s> len=%zu\n", out ? out : "(null)", out ? strlen(out) : 0);
    /* Dump what the bytes look like, in case the string is non-ASCII. */
    if (out) {
        size_t i;
        for (i =  0; i < strlen(out); i++) printf("%02x ", (unsigned char)out[i]);
        printf("\n");
    }
    return  0;
}