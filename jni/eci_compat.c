/* eci_compat.c
 *
 * Thin ABI shim: the old eci.lib entry-point names
 * over the openevv engine native API surface.
 *
 * The engine does not publish the old plain names in native builds.
 * Everything below is a forwarding layer to the engine real entry
 * points which live in the static library libevv.a.
 *
 * Return values follow the eci.lib contract documentedin eci.h.
 */

#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include "eci.h"
#include "eci/api/eci_old.h"

/* ECIVoice is a private per-file typedef in the engine. */
typedef struct ECIVoice { int32_t w[0x14]; } ECIVoice;

/* ---- engine entry points this layer calls. ---- */

extern void *eo_newEx(int32_t language);
extern void eo_registerCallback(OldInst *h, void *cb, void *data);
extern int eo_speaking(OldInst *h);
extern int eo_stop(OldInst *h);
extern int eo_clearInput(OldInst *h);

extern int32_t ev_setParam(OldInst *h, int32_t which, int32_t value);
extern int ev_setOutputBuffer(OldInst *h, int32_t samples, void *buffer);

extern int32_t api_new(void **out, int32_t lang);
extern int32_t api_delete(void *self);
extern int32_t api_add_text(void *self, void *text, int32_t b, int32_t c, int32_t d, int32_t f);
extern int32_t api_synthesize(void *self);

extern void cpp_delete(void *p);

extern int setRealWorldVoiceParam(char *voice, int which, int value);
extern int getRealWorldVoiceParam(ECIVoice v, int which);
extern void setRealWorldParamsFromECIParams(char *voice, int which);

extern int vc_setVoiceParam(OldInst *h, int voiceno, int which, int value);

extern void evv_port_start(void);
extern void evvRunStaticInitialisers(void);

/* ---- the 13 eci names the JNI bridge references. ---- */

static pthread_once_t port_boot_once = PTHREAD_ONCE_INIT;

static void port_boot(void)
{
    /* Upstream entry points (cli/probe.c( call these before any engine
     * instance: they bring up the port's threading/audio resources, without
     * which every engine mutex fails and synthesis stays silent. The JNI
     * bridge creates instances through eciNewEx, so boot here -- once. */
    evv_port_start();
    evvRunStaticInitialisers();
}

ECIAPI ECIHand ECICALL eciNewEx(int language)
{
    pthread_once(&port_boot_once, port_boot);
    return (ECIHand)eo_newEx(language);
}

ECIAPI ECIHand ECICALL eciDelete(ECIHand handle)
{
    OldInst *h = (OldInst *)handle;
    if (!h)
        return (ECIHand)0;
    api_delete(OI_NEW((OldInst *)handle));
    cpp_delete(OI_CONCAT((OldInst *)handle));
    /* The engine instance lives in its own arena (evv_arena, mmap'd), not
     * malloc; free() here is an invalid free that corrupts the heap
     * ("double free or corruption (out)" + SIGABRT) on glibc and bionic.
     * api_delete/cpp_delete dispose the engine side. */
    return (ECIHand)0;
}

ECIAPI int ECICALL eciSetOutputBuffer(ECIHand handle, int samples, short *buffer)
{
    return ev_setOutputBuffer((OldInst *)handle, samples, (void *)buffer);
}

ECIAPI int ECICALL eciSetParam(ECIHand handle, int parameter, int value)
{
    return ev_setParam((OldInst *)handle, parameter, value);
}

ECIAPI void ECICALL eciRegisterCallback(ECIHand handle, ECICallback callback, void *data)
{
    eo_registerCallback((OldInst *)handle, (void *)callback, data);
}

ECIAPI int ECICALL eciAddText(ECIHand handle, const void *text)
{
    return (int)api_add_text(OI_NEW((OldInst *)handle), (void *)text, 0, 0, 0, 0);
}

ECIAPI int ECICALL eciSynthesize(ECIHand handle)
{
    return (int)api_synthesize(OI_NEW((OldInst *)handle));
}

ECIAPI int ECICALL eciSpeaking(ECIHand handle)
{
    return eo_speaking((OldInst *)handle);
}

ECIAPI int ECICALL eciStop(ECIHand handle)
{
    eo_stop((OldInst *)handle);
    return 0;
}

ECIAPI int ECICALL eciClearInput(ECIHand handle)
{
    return eo_clearInput((OldInst *)handle);
}

/* Voice editing. Voice 0 is the active voice. */

static char *voice_slot(OldInst *h, int voice)
{
    if (voice == 0)
        return OI_VOICE(h);
    if (voice < 1 ||voice >  16)
        return NULL;
    return OI_VOICES(h) + (voice -  1) * VOICE_BYTES;
}

ECIAPI int ECICALL eciSetVoiceParam(ECIHand handle, int voice, int param, int value)
{
    /* Delegate to the engine's canonical setter, which writes BOTH the
     * engine-unit voice words the param-diff actually sends (+0x20..0x3c()
     * and the human-unit shadow fields. The standalone shim below only
     * touched the shadow fields, so ev_sendChangedActiveVoice saw no
     * change and every slider value was silently dropped. */
    return vc_setVoiceParam((OldInst *)handle, voice, param, value);
}

ECIAPI int ECICALL eciGetVoiceParam(ECIHand handle, int voice, int param)
{
    char *slot = voice_slot((OldInst *)handle, voice);
    if (!slot)
        return -1;
    return getRealWorldVoiceParam(*(ECIVoice *)slot, param);
}

ECIAPI int ECICALL eciCopyVoice(ECIHand handle, int from, int to)
{
    OldInst *h = (OldInst *)handle;
    char *src, *dst;

    /* eciCopyVoice writes to voice 0 or  9 through 16. */
    if (to != 0 && (to < 9 ||to >  16))
        return 0;
    src = voice_slot(h, from);
    dst = voice_slot(h, to);
    if (!src ||!dst)
        return 0;
    if (src == dst)
        return 1;
    memcpy(dst, src, VOICE_BYTES);
    setRealWorldParamsFromECIParams(dst, -1);
    return 1;
}

/* Japanese is a real member of the engine now: the romanizer lives in
 * rom/jajp ( 38 tracked files( and lands in the archive whenever jajp
 * is in the build. The Makefile pins it through -DEVV_ROM_JAJP when jajp
 * is in TAGS, so eci_romedll.c links jp_rom_new out of the archive -- no
 * stub, no weak symbol, no dlopen hazard. Host probes link cli/evv.c
 * against the archive directly and never see this file. */
