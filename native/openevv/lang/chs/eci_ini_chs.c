/* The engine's settings for the Chinese machine, hand-stubbed until
   the Apple module's ini is lifted byte for byte (the reader's
   arithmetic depends on the exact separators). */

#include <stdint.h>

#include "eci_synththread.h"

const char chs_eciIni[2] = { 0xff, 0x00 };

const int32_t chs_eciIniSize = 2;

const int32_t chs_eci_library_lang = 0x90000;
const char chs_eci_library_name[] = "Static Engine CHS";