package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/** One user-dictionary rule: written word -> spoken form. Case-sensitive rules only
 *  match the exact written casing; others match any casing ( mirrors the factory's per-entry flag.
 */
data class DictEntry(
    val word: String,
    val spoken: String,
    val caseSensitive: Boolean = false,
)

/** Voice settings (UI rate/pitch/volume + language selection.)) */
class VoiceConfig(private val context: Context) {
    /** Supported language definitions (Apple Kona full table).)*/
    class Lang(
        /** BCP-47 */
        val code: String,
        /** Display name */
        val name: String,
        /** Apple Kona dialect number */
        val konaDialect: Int,
        /** ECI dialect (partly derived; confirmed against the linked modules) */
        val eciDialect: Long,
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    // Direct-boot mirror: settings written here must also land in device-protected
    // storage so a locked start (before first unlock( reads the same values (the service's
    // boot-time init reads that copy; without write-through the mirror goes stale until the
    // next unlocked service start, which is what made the voice revert at lock-screen).
    private val devicePrefs: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var cachedMap: Map<String, String>? = null
    @Volatile private var cachedMtime: Long = -1L
    /**
     * Reads voice preferences from XML, reusing the cached map while the file timestamp is unchanged.
     *
     * Falls back to device-protected storage if the app preference file is absent.
     * Closes the input stream after parsing and preserves an existing cache if reading fails.
     */
    private fun readMap(): Map<String, String> {
        val appCtx = context.applicationContext ?: context
        val f = if (File(appCtx.getDataDir(), "shared_prefs/$PREFS.xml").exists()) File(appCtx.getDataDir(), "shared_prefs/$PREFS.xml")
                  else File(context.createDeviceProtectedStorageContext().getDataDir(), "shared_prefs/$PREFS.xml")
        val mt = if (f.exists()) f.lastModified() else -1L
        if (cachedMap != null && mt == cachedMtime) return cachedMap!!
        val map = HashMap<String, String>()
        if (f.exists()) {
            try {
                val parser = Xml.newPullParser()
                FileInputStream(f).use { fis ->
                    parser.setInput(fis, null)
                    var t = parser.eventType
                    var curKey: String? = null
                    while (t != XmlPullParser.END_DOCUMENT) {
                        if (t == XmlPullParser.START_TAG) {
                            val n = parser.getAttributeValue(null, "name")
                            val v = parser.getAttributeValue(null, "value")
                            if (parser.name == "string") {
                                curKey = n
                            } else if (n != null && v != null) {
                                map[n] = v
                            }
                        } else if (t == XmlPullParser.TEXT) {
                            val k = curKey
                            if (k != null) {
                                map[k] = parser.text
                                curKey = null
                            }
                        }
                        t = parser.next()
                    }
                }
            } catch (ignore: Throwable) {
                // A transient read failure must not wipe a good cache; retry on the next access.

                val prev = cachedMap
                if (prev != null) return prev
            }
        }
        cachedMap = map
        cachedMtime = mt
        return map
    }


    private fun writeBoth(block: (SharedPreferences.Editor) -> Unit) {
        val a: SharedPreferences.Editor = prefs.edit(); block(a); a.commit()
        val b = devicePrefs.edit(); block(b); b.commit()
    }

    val voice: String
        get() = readMap()[KEY_VOICE] ?: "en-US"
    val rate: Int
        get() = readMap()[KEY_RATE]?.toIntOrNull() ?: 100
    val pitch: Int
        get() = readMap()[KEY_PITCH]?.toIntOrNull() ?: 50
    val volume: Int
        get() = readMap()[KEY_VOLUME]?.toIntOrNull() ?: 100
    /** DSP mode: 0 = standard (raw engine output), 1 = enhanced (de-hiss + limiter). Default: standard. */
    val dspMode: Int
        get() = readMap()[KEY_DSP_MODE]?.toIntOrNull() ?: 0
    val isAutoDetect: Boolean
        get() = readMap()[KEY_AUTO_DETECT]?.toBoolean() ?: true

    fun setVoice(v: String) { writeBoth { it.putString(KEY_VOICE, v) } }
    fun setRate(r: Int) { writeBoth { it.putInt(KEY_RATE, r) } }
    fun setPitch(p: Int) { writeBoth { it.putInt(KEY_PITCH, p) } }
    fun setVolume(v: Int) { writeBoth { it.putInt(KEY_VOLUME, v) } }
    fun setDspMode(m: Int) { writeBoth { it.putInt(KEY_DSP_MODE, m) } }
    fun setAutoDetect(b: Boolean) { writeBoth { it.putBoolean(KEY_AUTO_DETECT, b) } }

    fun setPunctEnabled(b: Boolean) { writeBoth { it.putBoolean(KEY_PUNCT,  b) } }
        val punctEnabled: Boolean
            get() = readMap()[KEY_PUNCT]?.toBoolean() ?: false

        fun setNumberEnabled(b: Boolean) { writeBoth { it.putBoolean(KEY_NUMBER_ENABLED,  b) } }
        val numberEnabled: Boolean
            get() = readMap()[KEY_NUMBER_ENABLED]?.toBoolean() ?: false
        fun setNumberModePref(v: Int) { writeBoth { it.putInt(KEY_NUMBER_MODE,  v) } }
        val numberModePref: Int
            get() = readMap()[KEY_NUMBER_MODE]?.toIntOrNull() ?: 0

    /** Dictionary: newline-separated "word|spoken" lines in creation order. */
    fun dictEntries(): List<DictEntry> {
            val raw = readMap()[KEY_DICT] ?: ""
            val out = ArrayList<DictEntry>()
            for (line in raw.split("\n")) {
                parseDictLine(line)?.let { out.add(it) }
            }
            return out
        }

        /** Parse one stored "word|spoken[|cs]" line; null when malformed( no word/no spoken(.*/
        private fun parseDictLine(line: String): DictEntry? {
            val idx1 = line.indexOf('|')
            if (idx1 <= 0 || idx1 >= line.length - 1) return null
            val word = line.substring(0,  idx1).trim()
            if (word.isEmpty()) return null
            val idx2 = line.indexOf('|',  idx1 + 1)
            if (idx2 <= 0) {
                val spoken = line.substring(idx1 + 1).trim()
                return if (spoken.isEmpty()) null else DictEntry(word,  spoken)
            }
            val spoken = line.substring(idx1 + 1,  idx2).trim()
            if (spoken.isEmpty()) return null
            val tail = line.substring(idx2 + 1).trim()
            return DictEntry(word,  spoken,  tail.equals("cs",  ignoreCase = true))
        }

    fun addDictEntry(word: String,  spoken: String,  caseSensitive: Boolean = false) {
            val w = word.trim().replace('\n', ' ').replace('|', ' ')
            val s = spoken.trim().replace('\n', ' ').replace('|', ' ')
            if (w.isEmpty() || s.isEmpty()) return
            val cur = readMap()[KEY_DICT] ?: ""
            val kept = ArrayList<String>()
            for (l in cur.split("\n")) {
                if (l.isBlank()) continue
                val existing = parseDictLine(l)
                if (existing != null && existing.word.equals(w,  ignoreCase = true)) continue
                kept.add(l)
            }
            kept.add(w + "|" + s + if (caseSensitive) "|cs" else "")
            writeBoth { it.putString(KEY_DICT,  kept.joinToString("\n")) }
        }

    fun removeDictEntry(word: String) {
            val cur = readMap()[KEY_DICT] ?: ""
            val kept = ArrayList<String>()
            for (l in cur.split("\n")) {
                if (l.isBlank()) continue
                val existing = parseDictLine(l)
                if (existing != null && existing.word.equals(word,  ignoreCase = true)) continue
                kept.add(l)
            }
            writeBoth { it.putString(KEY_DICT,  kept.joinToString("\n")) }
        }

    fun clearDict() { writeBoth { it.remove(KEY_DICT) } }
    companion object {
        private const val PREFS = "vvtts_prefs"
        const val KEY_VOICE = "voice"
        const val KEY_RATE = "rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
        const val KEY_DSP_MODE = "dsp_mode"
        const val KEY_AUTO_DETECT = "auto_detect"
        const val KEY_PUNCT = "speak_punctuation"
        const val KEY_DICT = "user_dict"
        const val KEY_NUMBER_ENABLED = "number_processing_enabled"
        const val KEY_NUMBER_MODE = "number_processing_mode"

        // eciDialect values: measured from each language module's registered constant
        // (lang/*/eci_ini_*.c *_eci_library_lang); one-to-one with build_native.sh LANGS
        // 0 = language not linked here (ko/zh-TW have no module; zh-CN IS linked via oracle)
        val LANGS = arrayOf(
            Lang("en-US", "English (US)", 0, 0x10000L),
            Lang("en-GB", "English (UK)", 1, 0x10001L),
            Lang("es-ES", "Spanish (Spain)", 2, 0x20000L),
            Lang("es-US", "Spanish (US)", -1, 0x20001L), // esus module
            Lang("es-MX", "Spanish (Mexico)", 3, 0x20002L), // esmx module
            Lang("fr-FR", "French (France)", 4, 0x30000L),
            Lang("fr-CA", "French (Canada)", 5, 0x30001L),
            Lang("de-DE", "German", 6, 0x40000L),
            Lang("it-IT", "Italian", 7, 0x50000L),
            Lang("pt-BR", "Portuguese (Brazil)", 8, 0x70000L),
            Lang("fi-FI", "Finnish", 9, 0x90000L),
            Lang("ja-JP", "Japanese", 10, 0x80000L),
            Lang("pl-PL", "Polish", -1, 0x110000L), // plpl module
            Lang("ko-KR", "Korean", 11, 0L), // not linked
            Lang("zh-CN", "Chinese (Mandarin)", 12, 0x60000L), // linked: oracle synth path
            Lang("zh-TW", "Chinese (Taiwan)", 13, 0x60001L), // not linked
        )

        /** CF library code (Code Factory 10 languages) mapped from BCP-47 */
        fun cfCodeFor(bcp47: String?): String? {
            if (bcp47 == null) return null
            return when (bcp47) {
                "de-DE" -> "deu"
                "en-US" -> "enu"
                "en-GB" -> "eng"
                "es-ES" -> "esn"  // Castilian Spanish
                "es-MX" -> "esm"  // Latin American / Mexican Spanish
                "fr-FR" -> "fra"
                "fr-CA" -> "frc"
                "it-IT" -> "ita"
                "pt-BR" -> "ptb"
                "fi-FI" -> "fin"
                else -> null      // zh/ja/ko have no CF library → the openevv chain handles them
            }
        }

        fun findLang(code: String?): Lang {
            if (code == null) return LANGS[0]// en-US
            for (l in LANGS) if (l.code == code) return l
            return LANGS[0] // en-US
        }
    }
}
