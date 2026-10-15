#include <assert.h>
#include <stdio.h>
#include "../../jni/eci_compat.c"
static int stop_result, callback_refused;
/** Return the configured stop result for compatibility-wrapper tests. */
int eo_stop(OldInst *h) { return stop_result; }
/** Upstream es_delete always releases; the wrapper returns NULL (deleted) after it. */
int es_delete(OldInst *h) { (void)h; return 0; }
/** Store callback fields unless the fixture is configured to refuse registration. */
void eo_registerCallback(OldInst *h,void *cb,void *data) { if (!callback_refused) { h->callback=cb; h->cbdata=data; } }
/** Encode voice and parameter IDs into a result to verify accessor delegation. */
int vc_getVoiceParam(OldInst *h,int voice,int param) { return voice*10+param; }
/** Encode source and destination slots into a result to verify voice-copy delegation. */
int vc_copyVoice(OldInst *h,int from,int to) { return from+to; }
/** Provide a callback identity for registration checks; ignore callback arguments. */
static int callback(ECIHand h,ECIMessage m,int p,void *d) { return 0; }
/** Assert compatibility return values, registration refusal, and canonical voice delegation. */
int main(void) {
    OldInst h={0};
    stop_result=0; assert(eciStop(&h)==0); stop_result=1; assert(eciStop(&h)==1);
    assert(eciDelete(&h)==NULL); /* upstream always deletes: handle is gone */
    callback_refused=1; assert(!vv_register_callback(&h,callback,&h));
    callback_refused=0; assert(vv_register_callback(&h,callback,&h));
    assert(eciGetVoiceParam(&h,16,2)==162);
    assert(eciCopyVoice(&h,1,16)==17);
    assert(eciCopyVoice(&h,1,8)==0);
    puts("PASS compatibility: stop/delete results, callback registration, canonical voice delegation");
}
