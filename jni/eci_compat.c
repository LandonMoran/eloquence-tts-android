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
#include "eci_compat.h"
#include "eci/api/eci_old.h"

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

extern int es_delete_checked(OldInst *h);
extern int vc_getVoiceParam(OldInst *h, int voiceno, int which);
extern int vc_copyVoice(OldInst *h, int from, int to);

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

/** Destroy an engine, returning NULL on success or the still-owned handle on refusal. */
ECIAPI ECIHand ECICALL eciDelete(ECIHand handle)
{
    if (!handle) return NULL;
    // Destruction runs inside the engine so its arena allocator and owned
    // queues/converted strings are all released by their actual owner.
    return es_delete_checked((OldInst *)handle) ? NULL : handle;
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

/* Keep the public void ABI, but let the JNI constructor verify registration.
 * eo_registerCallback only assigns these two fields and may refuse reentry. */
/** Register a callback and return whether the engine accepted both callback and user data. */
int vv_register_callback(ECIHand handle, ECICallback callback, void *data)
{
    OldInst *h = (OldInst *)handle;
    if (!h || !callback) return 0;
    eo_registerCallback(h, (void *)callback, data);
    return OI_CALLBACK(h) == (void *)callback && OI_CBDATA(h) == data;
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

/** Forward stop to the engine and preserve its success/failure result. */
ECIAPI int ECICALL eciStop(ECIHand handle)
{
    return eo_stop((OldInst *)handle);
}

ECIAPI int ECICALL eciClearInput(ECIHand handle)
{
    return eo_clearInput((OldInst *)handle);
}

/* Voice editing. Voice 0 is the active voice. */

ECIAPI int ECICALL eciSetVoiceParam(ECIHand handle, int voice, int param, int value)
{
    /* Delegate to the engine's canonical setter, which writes BOTH the
     * engine-unit voice words the param-diff actually sends (+0x20..0x3c()
     * and the human-unit shadow fields. The standalone shim below only
     * touched the shadow fields, so ev_sendChangedActiveVoice saw no
     * change and every slider value was silently dropped. */
    return vc_setVoiceParam((OldInst *)handle, voice, param, value);
}

/** Read a voice parameter through the engine's canonical accessor. */
ECIAPI int ECICALL eciGetVoiceParam(ECIHand handle, int voice, int param)
{
    return vc_getVoiceParam((OldInst *)handle, voice, param);
}

/** Copy a voice into the active or user-defined slots; return zero for invalid destinations. */
ECIAPI int ECICALL eciCopyVoice(ECIHand handle, int from, int to)
{
    if (!handle || from < 0 || from > ECI_LAST_VOICE ||
        (to != 0 && (to < 9 || to > ECI_LAST_VOICE))) return 0;
    return vc_copyVoice((OldInst *)handle, from, to);
}

/* Japanese is a real member of the engine now: the romanizer lives in
 * rom/jajp ( 38 tracked files( and lands in the archive whenever jajp
 * is in the build. The Makefile pins it through -DEVV_ROM_JAJP when jajp
 * is in TAGS, so eci_romedll.c links jp_rom_new out of the archive -- no
 * stub, no weak symbol, no dlopen hazard. Host probes link cli/evv.c
 * against the archive directly and never see this file. */
