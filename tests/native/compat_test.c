#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../../jni/eci_compat.c"
static int stop_result, callback_refused, add_result, synth_result, added;
/** Return the configured stop result for compatibility-wrapper tests. */
int eo_stop(OldInst *h) { return stop_result; }
/** Count text handed to the engine and return the configured result. */
int et_addText(OldInst *h, const char *text) { assert(strcmp(text, "hello") == 0); added++; return add_result; }
/** Return the configured synthesis result. */
int et_synthesize(OldInst *h) { return synth_result; }
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
    /* Text in and say it: the published contract is nonzero for success, and the
     * text has to reach the engine (a zero length reaches nothing at all). */
    add_result=1; synth_result=1;
    assert(eciAddText(&h,"hello")==1 && added==1);
    assert(eciSynthesize(&h)==1);
    add_result=0; synth_result=0;
    assert(eciAddText(&h,"hello")==0 && added==2);
    assert(eciSynthesize(&h)==0);
    assert(eciAddText(NULL,"hello")==0 && eciAddText(&h,NULL)==0 && added==2);
    puts("PASS compatibility: stop/delete results, callback registration, canonical voice delegation, text-in contracts");
}
