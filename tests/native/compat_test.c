#include <assert.h>
#include <stdio.h>
#include "../../jni/eci_compat.c"
static int stop_result, delete_result, callback_refused;
int eo_stop(OldInst *h) { return stop_result; }
int es_delete_checked(OldInst *h) { return delete_result; }
void eo_registerCallback(OldInst *h,void *cb,void *data) { if (!callback_refused) { h->callback=cb; h->cbdata=data; } }
int vc_getVoiceParam(OldInst *h,int voice,int param) { return voice*10+param; }
int vc_copyVoice(OldInst *h,int from,int to) { return from+to; }
static int callback(ECIHand h,ECIMessage m,int p,void *d) { return 0; }
int main(void) {
    OldInst h={0};
    stop_result=0; assert(eciStop(&h)==0); stop_result=1; assert(eciStop(&h)==1);
    delete_result=0; assert(eciDelete(&h)==&h); delete_result=1; assert(eciDelete(&h)==NULL);
    callback_refused=1; assert(!vv_register_callback(&h,callback,&h));
    callback_refused=0; assert(vv_register_callback(&h,callback,&h));
    assert(eciGetVoiceParam(&h,16,2)==162);
    assert(eciCopyVoice(&h,1,16)==17);
    assert(eciCopyVoice(&h,1,8)==0);
    puts("PASS compatibility: stop/delete results, callback registration, canonical voice delegation");
}
