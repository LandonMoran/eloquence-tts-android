/* The static initialisers Microsoft's runtime used to run on the way in.
 *
 * Its compiler left a pointer to each in a .CRT$XCU section and its linker
 * walked them at startup. Nothing here walks that, and the sections cannot
 * simply be walked either: every one of the initialisers is a local symbol
 * called $E1 living in a COMDAT called .text$yc, so the GNU linker sees
 * several copies of the same thing and keeps one.
 *
 * They are all trivial, though, and every one of them is written out here.
 * Without them no output format registers itself, the voice table stays
 * empty, and the mutexes guarding the filter list, the SSML lexer, the
 * synthesis thread and the engine's own first-time flag are never
 * constructed -- so the first worker thread reaches a null format and dies.
 */

#include <stdint.h>
#include "evv_abi.h"

/* Asked for by the names the original's compiler gave them, because the
   reference binary is built from its objects alone and has no others.
   Natively those names fall away and the plain ones are ours. */
extern THIS void *sy_mutexCtor(void *m, int32_t recursive)
    MANGLED("??0Mutex@@QAE@H@Z");
extern THIS void *isv_ctor(void *self)
    MANGLED("??0InitializeStandardVoices@@QAE@XZ");
extern int32_t initializeSoundFormats(void);

extern int32_t fm_protectFilterLoad[]
    MANGLED("?m_protectFilterLoad@FilterManager@@0VMutex@@A");
extern int32_t ssml_lexerMutex[]
    MANGLED("?lexerMutex@SSMLFilter@@1VMutex@@A");
extern uint8_t st_protectInitialization[]
    MANGLED("?m_protectInitialization@SynthThread@@0VMutex@@A");

/* These two the original spells as ordinary C, so they need no help. */
extern char protectFirstTime[];
extern char initializeStandardVoices[];

/* A mutex that may not be taken twice by the same thread. */
#define MUTEX_PLAIN 0

/* The Mandarin ROM module registers on a constructor attribute the GNU
   linker does not run until the module is in the link, and archive
   selection will not pull the module in by itself (nothing outside it
   names anything in it except the constructor).  Call its register
   function here, the way the original's runtime ran these on the way
   in.  It is safe to call once: the registration only fills a slot. */
extern void chs_register(void);

void evvRunStaticInitialisers(void)
{
    initializeSoundFormats();
    sy_mutexCtor(fm_protectFilterLoad, MUTEX_PLAIN);
    sy_mutexCtor(ssml_lexerMutex, MUTEX_PLAIN);
    sy_mutexCtor(st_protectInitialization, MUTEX_PLAIN);
    sy_mutexCtor(protectFirstTime, MUTEX_PLAIN);
    isv_ctor(initializeStandardVoices);
    chs_register();
}
