#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#include "../lang/chs/oracle_chs.c"

int main(int argc, char **argv) {
    for (int i = 1; i < argc; i++) {
        unsigned long cp = strtoul(argv[i], NULL, 16);
        const chs_oracle_row *row = chs_oracle_find((uint32_t)cp);
        if (!row) {
            printf("U+%04lx: not in oracle table\n", cp);
            continue;
        }
        printf("U+%04lx: pcm=%u gpoff=%u len=%u pxoff=%u len=%u\n",
               cp, row->pcm, row->gpoff, row->gplen,
               row->pxoff, row->pxlen);
        if (row->gplen >  0) {
            printf("  genphon[0..7]:");
            for (int k =  0; k < row->gplen && k < 8; k++)
                printf(" %02x", chs_oracle_genphon_data[row->gpoff + k]);
            printf("\n");
        }
    }
    return 0;
}