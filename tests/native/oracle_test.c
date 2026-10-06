#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "../../jni/chs_oracle_synth.c"
static unsigned char bytes[4]={1,0,2,0};
static uint32_t clip_length=4;
int chs_oracle_pcm_for_gbk(uint32_t key,const uint8_t **data,uint32_t *len) {
    *data=bytes; *len=clip_length; return key==0x8140;
}
int main(void) {
    unsigned char text[]={0x81,0x40,0x81,0x40}; short *pcm=(void *)1;
    assert(chs_build_pcm(text,sizeof(text),&pcm)==4); assert(pcm[0]==1 && pcm[3]==2); free(pcm);
    // Report a clip beyond the allocation cap. The data is only four bytes:
    // ASan would catch any attempt to allocate/copy it instead of rejecting pass 1.
    clip_length=VV_MAX_PCM_SAMPLES*sizeof(short)+2;
    assert(chs_build_pcm(text,sizeof(text),&pcm)==0 && pcm==NULL);
    assert(chs_build_pcm(text,VV_MAX_TEXT_BYTES+1,&pcm)==0 && pcm==NULL);
    puts("PASS oracle: PCM and input bounds before allocation");
}
