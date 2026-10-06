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

    private val store = MirroredPreferences(context, PREFS)
    private fun readMap() = store.strings()
    private fun writeBoth(block: (SharedPreferences.Editor) -> Unit) { store.edit(block) }

    val extraLogging: Boolean get() = store.getBoolean("extra_logging", false)
    fun setExtraLogging(enabled: Boolean) { writeBoth { it.putBoolean("extra_logging", enabled) } }

    val voice: String
        get() = readMap()[KEY_VOICE] ?: "en-US"
    val rate: Int
        get() = readMap()[KEY_RATE]?.toIntOrNull() ?: 100
    val pitch: Int
        get() = readMap()[KEY_PITCH]?.toIntOrNull() ?: 50
    val volume: Int
        get() = readMap()[KEY_VOLUME]?.toIntOrNull() ?: 100
    val isAutoDetect: Boolean
        get() = readMap()[KEY_AUTO_DETECT]?.toBoolean() ?: true

    fun setVoice(v: String) { writeBoth { it.putString(KEY_VOICE, v) } }
    fun setRate(r: Int) { writeBoth { it.putInt(KEY_RATE, r) } }
    fun setPitch(p: Int) { writeBoth { it.putInt(KEY_PITCH, p) } }
    fun setVolume(v: Int) { writeBoth { it.putInt(KEY_VOLUME, v) } }
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

    private var dictionaryRaw: String? = null
    private var dictionaryEntries: List<DictEntry> = emptyList()
    private var dictionaryRules: List<Pair<Regex, String>> = emptyList()

    @Synchronized
    fun dictEntries(): List<DictEntry> {
        refreshDictionary()
        return dictionaryEntries
    }

    @Synchronized
    fun compiledDictionary(): List<Pair<Regex, String>> {
        refreshDictionary()
        return dictionaryRules
    }

    private fun refreshDictionary() {
        val raw = store.read()[KEY_DICT] as? String ?: ""
        if (raw == dictionaryRaw) return
        val entries = parseDictionary(raw)
        dictionaryEntries = entries
        dictionaryRules = entries.map { entry ->
            Regex((if (entry.caseSensitive) "" else "(?i)") + "\\b" + Regex.escape(entry.word) + "\\b") to entry.spoken
        }
        dictionaryRaw = raw
    }

    private fun parseDictionary(raw: String): List<DictEntry> {
        if (raw.length > MAX_DICT_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_DICT_BYTES) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size < 2) null else {
                val word = parts[0].trim()
                val spoken = parts[1].trim()
                if (word.isEmpty() || spoken.isEmpty() || word.length > MAX_WORD_CHARS || spoken.length > MAX_SPOKEN_CHARS) null
                else DictEntry(word, spoken, parts.getOrNull(2).equals("cs", true))
            }
        }.take(MAX_DICT_ENTRIES).toList()
    }

    /** Validate the whole batch before committing; imports never leave half a dictionary. */
    fun addDictEntries(entries: List<DictEntry>, replacingWord: String? = null): Boolean = store.update { values ->
        val current = parseDictionary(values[KEY_DICT] as? String ?: "").toMutableList()
        if (replacingWord != null) current.removeAll { it.word.equals(replacingWord, true) }
        for (entry in entries) {
            val word = entry.word.trim().replace('\n', ' ').replace('|', ' ')
            val spoken = entry.spoken.trim().replace('\n', ' ').replace('|', ' ')
            require(word.isNotEmpty() && word.length <= MAX_WORD_CHARS) { "Dictionary word exceeds $MAX_WORD_CHARS characters" }
            require(spoken.isNotEmpty() && spoken.length <= MAX_SPOKEN_CHARS) { "Dictionary replacement exceeds $MAX_SPOKEN_CHARS characters" }
            current.removeAll { it.word.equals(word, true) }
            current += DictEntry(word, spoken, entry.caseSensitive)
            require(current.size <= MAX_DICT_ENTRIES) { "Dictionary exceeds $MAX_DICT_ENTRIES entries" }
        }
        val raw = current.joinToString("\n") { it.word + "|" + it.spoken + if (it.caseSensitive) "|cs" else "" }
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_DICT_BYTES) { "Dictionary exceeds $MAX_DICT_BYTES bytes" }
        values[KEY_DICT] = raw
    }

    fun addDictEntry(word: String, spoken: String, caseSensitive: Boolean = false): Boolean =
        addDictEntries(listOf(DictEntry(word, spoken, caseSensitive)))

    fun removeDictEntry(word: String) {
        store.update { values ->
            values[KEY_DICT] = parseDictionary(values[KEY_DICT] as? String ?: "")
                .filterNot { it.word.equals(word, true) }
                .joinToString("\n") { it.word + "|" + it.spoken + if (it.caseSensitive) "|cs" else "" }
        }
    }

    fun clearDict() { writeBoth { it.remove(KEY_DICT) } }
    companion object {
        const val MAX_DICT_BYTES = 256 * 1024
        const val MAX_DICT_ENTRIES = 1000
        const val MAX_WORD_CHARS = 128
        const val MAX_SPOKEN_CHARS = 512
        const val MAX_IMPORT_BYTES = 1024 * 1024
        private const val PREFS = "vvtts_prefs"
        const val KEY_VOICE = "voice"
        const val KEY_RATE = "rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
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
