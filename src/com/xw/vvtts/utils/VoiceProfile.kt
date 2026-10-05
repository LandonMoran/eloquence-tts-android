package com.xw.vvtts.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/**
 * Voice profile configuration.
 * 8 preset Kona voices; per-role custom overrides of the 8 ECI voice params.
 * Defaults come from KonaVoice (KonaVoicePresets.csv is the source of truth),
 * user custom values live in SharedPreferences; reset removes the overrides.
 */
class VoiceProfile(private val context: Context) {
    // Only for XML file probing in readMap() (its credential accesses are
    // try/catch-guarded). NEVER build prefs from it: on a device-protected
    // context, getApplicationContext() resolves to the credential-encrypted
    // Application, whose getSharedPreferences() throws IllegalStateException
    // before the first user unlock, killing service onCreate on the lock screen.
    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Disk-backed XML reads (mtime-guarded): SharedPreferences caches are
        // per-process, so the settings UI's edits never reach this long-lived service
    private val devicePrefs: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var cachedMap: Map<String, String>? = null
    @Volatile private var cachedMtime: Long = -1L
    /**
     * Reads profile preferences from XML, reusing the cached map while the file timestamp is unchanged.
     *
     * Falls back to device-protected storage if the app preference file is absent.
     * Closes the input stream after parsing and preserves an existing cache if reading fails.
     */
    private fun readMap(): Map<String, String> {
            // Direct-boot access must not assume credential-encrypted storage is
            // reachable; any failure falls back to the device-protected copy.

            val f = try {
                if (File(appContext.getDataDir(), "shared_prefs/$PREFS.xml").exists())
                    File(appContext.getDataDir(), "shared_prefs/$PREFS.xml")
                else File(appContext.createDeviceProtectedStorageContext().getDataDir(), "shared_prefs/$PREFS.xml")
            } catch (ignore: Throwable) {

                File(appContext.createDeviceProtectedStorageContext().getDataDir(), "shared_prefs/$PREFS.xml")
            }
            val mt = if (f.exists()) f.lastModified() else -1L
            if (cachedMap != null && mt == cachedMtime) return cachedMap!!
            val map = HashMap<String, String>()
            var parsedOk = false
            if (f.exists()) {
            try {
                val parser = Xml.newPullParser()
                FileInputStream(f).use { fis ->
                    parser.setInput(fis, null)
                    var t = parser.eventType
                    var curKey: String? = null
                    val curVal = StringBuilder()
                    while (t != XmlPullParser.END_DOCUMENT) {
                        when (t) {
                            XmlPullParser.START_TAG -> {
                                val n = parser.getAttributeValue(null, "name")
                                val v = parser.getAttributeValue(null, "value")
                                if (parser.name == "string") {
                                    curKey = n?.takeIf { it.isNotEmpty() }
                                    curVal.setLength(0)
                                } else if (n != null && v != null) {
                                    map[n] = v
                                }
                            }
                            XmlPullParser.TEXT -> if (curKey != null) curVal.append(parser.text ?: "")
                            XmlPullParser.END_TAG -> if (parser.name == "string" && curKey != null) {
                                map[curKey!!] = curVal.toString()  // tolerate empty values (empty states)
                                curKey = null
                            }
                        }
                        t = parser.next()
                    }
                    parsedOk = true
                }
            } catch (ignore: Throwable) {
                // A transient read failure must not wipe a good cache; retry on the next access.

                val prev = cachedMap
                if (prev != null) return prev
            }
        } else {
            parsedOk = true  // No file yet: an empty state is authoritative
        }
        // Only cache a clean, complete parse; an in-progress XML write that
        // happened to parse must not pin a partial map, so a later read retries.


        if (parsedOk && (!f.exists() || map.isNotEmpty())) {

            cachedMap = map
            cachedMtime = mt
        }
        return map
    }
    private fun writeBoth(block: (SharedPreferences.Editor) -> Unit) {
            // Synchronous, failure-checked commits; apply() is fire-and-forget, so a
            // rejected/killed write silently reported success end left the pair split.


            val snapshot = prefs.getAll()
            val okCred: Boolean
            try {
                val ea = prefs.edit()
                block(ea)
                okCred = ea.commit()
            } catch (ignore: Throwable) { return }  // nothing written: keep cache
            if (!okCred) { invalidateCache(); return }
            val okDevice: Boolean
            try {
                val eb = devicePrefs.edit()
                block(eb)
                okDevice = eb.commit()
            } catch (ignore: Throwable) {
                restorePrefs(prefs, snapshot)  // rolled the first leg back: pair stays consistent
                invalidateCache()
                return
            }
            if (!okDevice) {
                restorePrefs(prefs, snapshot)
                invalidateCache()
                return
            }
            invalidateCache()
        }
        /** Rewrite a store from a snapshot (( used to roll back a two-store write that only half-committed(. */
        private fun restorePrefs(p: SharedPreferences, snapshot: Map<String, *>?) {
            if (snapshot == null) return
            val ed = p.edit(); ed.clear()
            for ((k,v) in snapshot) when (v) {
                is String -> ed.putString(k, v)
                is Boolean -> ed.putBoolean(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v)
                is Set<*> -> ed.putStringSet(k, v.map { it.toString() }.toSet())
            }
            ed.commit()
        }
        private fun invalidateCache() {
            cachedMap = null
            cachedMtime = -1L
        }

    val preset: Int
        get() = readMap()[KEY_PRESET]?.toIntOrNull() ?: 1

    fun setPreset(n: Int) {
        var v = n
        if (v < 1) v = 1
        if (v > 8) v = 8
        writeBoth { it.putInt(KEY_PRESET, v) }
    }

    /** Whether the given param has a custom override for this preset */
    fun hasOverride(preset: Int, param: Int): Boolean {
        return readMap().containsKey(overrideKey(preset, param))
    }

    /** Get a preset's param value: custom override first, else KonaVoice default */
    fun getParam(preset: Int, param: Int): Int {
        val key = overrideKey(preset, param)
        val v = readMap()[key]
        if (v != null) return v.toIntOrNull() ?: 0
        val customVoice = KonaVoice.byPreset(preset)
        return customVoice.param(param)
    }

    /** Set a custom override for a preset param */
    fun setParam(preset: Int, param: Int, value: Int) {
        writeBoth { it.putInt(overrideKey(preset, param), value) }
    }

    /** Restore a preset's defaults (delete all its custom overrides) */
    fun resetPreset(preset: Int) {
        writeBoth { e ->
            for (p in 0..7) {
                e.remove(overrideKey(preset, p))
            }
    }
    }

    companion object {
        private const val PREFS = "vvtts_voice_profile"
        private const val KEY_PRESET = "preset"   // Currently selected preset role 1-8

        // ECI voice param canonical ids (eci.h)
        const val PARAM_GENDER = 0        // Gender:  0=male  1=female (pick one)
        const val PARAM_HEAD_SIZE = 1     // Head size  0-100
        const val PARAM_PITCH_BASE = 2    // Pitch baseline  40-120
        const val PARAM_PITCH_FLUC =  3    // Pitch flutter  0-100
        const val PARAM_ROUGHNESS = 4     // Roughness  0-100
        const val PARAM_BREATHINESS =  5   // Breathiness  0-100
        const val PARAM_SPEED =  6         // Speed  0-250
        const val PARAM_VOLUME =  7        // Volume  0-100

        // Sliders shown in the edit dialog (gender is a separate radio - official voice switch;
        // pitchBase/speed/volume are controlled from the main screen, not duplicated here)
        val EDITABLE_PARAMS = intArrayOf(
            PARAM_HEAD_SIZE, PARAM_PITCH_FLUC, PARAM_ROUGHNESS, PARAM_BREATHINESS,
        )

        // Preset voice names (Apple Kona 8 roles)
        val PRESET_NAMES = arrayOf(
            "Reed", "Shelley", "Sandy", "Rocko", "Flo", "Grandma", "Grandpa", "Eddy",
        )

        private fun overrideKey(preset: Int, param: Int): String {
            return "override_$preset" + "_" + param
    }

        /** Parameter display name */
        fun paramName(param: Int): String {
            return when (param) {
                PARAM_GENDER -> "Gender"
                PARAM_HEAD_SIZE -> "Head size"
                PARAM_PITCH_BASE -> "Pitch"
                PARAM_PITCH_FLUC -> "Pitch flutter"
                PARAM_ROUGHNESS -> "Roughness"
                PARAM_BREATHINESS -> "Breathiness"
                PARAM_SPEED -> "Speed"
                PARAM_VOLUME -> "Volume"
                else -> "Parameter $param"
            }
    }
    }
}
