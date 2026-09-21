package com.xw.vvtts.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.xw.vvtts.R
import com.xw.vvtts.engine.EloquenceEngine
import com.xw.vvtts.utils.LanguageDetector
import com.xw.vvtts.utils.VoiceConfig
import com.xw.vvtts.utils.VoiceProfile
import java.util.HashSet

class SettingsActivity : Activity() {
    private var voiceConfig: VoiceConfig? = null
    private var voiceProfile: VoiceProfile? = null
    private var engine: EloquenceEngine? = null
    private var rateVal: TextView? = null
    private var pitchVal: TextView? = null
    private var volumeVal: TextView? = null
    private var dspBtn: Button? = null
    private var langBtn: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceConfig = VoiceConfig(this)
        voiceProfile = VoiceProfile(this)
        engine = EloquenceEngine(this)
        engine!!.setVoiceProfile(voiceProfile) // let synthesis read custom voice overrides
        engine!!.initialize()
        // Preload the Lingua detector (background thread; avoids first-synthesis jank)
        LanguageDetector.preloadLingua()
        // Restore language-detection settings from SharedPreferences
        restoreLanguageSettings()
        // Auto-test hook: am start --es autotest chinese
        val autotest = intent?.getStringExtra("autotest")
        if ("chinese" == autotest) {
            Handler(Looper.getMainLooper()).postDelayed({ testSpeech() }, 3000)
        } else if ("german" == autotest) {
            // Legacy hook: compare three English voices (validates voice params)
            Handler(Looper.getMainLooper()).postDelayed({ testGerman() }, 3000)
        }
        // Apply the current role config automatically
        engine!!.applyVoiceProfile(EloquenceEngine.DIALECT_ZH_CN, voiceProfile)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Eloquence TTS"
        title.textSize = 22f
        root.addView(title)

        // Language (single button; label shows current selection)
        val langBtnLocal = Button(this)
        langBtn = langBtnLocal
        langBtnLocal.contentDescription = getString(R.string.lang_header) // accessibility description
        langBtnLocal.setOnClickListener { showLanguageDialog() }
        root.addView(langBtnLocal)
        refreshLangButton(langBtnLocal)

        // Voice profile entry (tap to open)
        val voiceProfileBtn = Button(this)
        voiceProfileBtn.text = getString(R.string.voice_profile)
        val vpLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        vpLp.setMargins(0, dp(12), 0, 0)
        voiceProfileBtn.layoutParams = vpLp
        voiceProfileBtn.setOnClickListener {
            startActivity(Intent(this, VoiceProfileActivity::class.java))
        }
        root.addView(voiceProfileBtn)

        // Language-detection settings
        val langDetectBtn = Button(this)
        langDetectBtn.text = "Language detection settings"
        val ldLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ldLp.setMargins(0, dp(4), 0, 0)
        langDetectBtn.layoutParams = ldLp
        langDetectBtn.setOnClickListener { showDetectionSettingsDialog() }
        root.addView(langDetectBtn)

        // Rate
        rateVal = addSeekBar(root, getString(R.string.rate), voiceConfig!!.rate, 1,  300) { v ->
            voiceConfig!!.setRate(v)
            rateVal!!.text = getString(R.string.rate_fmt, v)
        }

        // Pitch
        pitchVal = addSeekBar(root, getString(R.string.pitch), voiceConfig!!.pitch, 0,  100) { v ->
            voiceConfig!!.setPitch(v)
            pitchVal!!.text = getString(R.string.pitch_fmt, v)
        }

        // Volume
        volumeVal = addSeekBar(root, getString(R.string.volume), voiceConfig!!.volume, 0,  100) { v ->
            voiceConfig!!.setVolume(v)
                        volumeVal!!.text = getString(R.string.volume_fmt, v)
        }


        // Audio quality mode: 0=standard (raw tone), 1=enhanced (de-hiss + limiter); default standard
        val dspBtnLocal = Button(this)
        dspBtn = dspBtnLocal
        dspBtnLocal.setOnClickListener { showDspDialog() }
        val dspLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dspLp.setMargins(0, dp(4), 0, 0)
        dspBtnLocal.layoutParams = dspLp
        root.addView(dspBtnLocal)
        refreshDspButton(dspBtnLocal)
        val testBtn = Button(this)
        testBtn.text = getString(R.string.test)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(20), 0, 0)
        testBtn.layoutParams = lp
        testBtn.setOnClickListener { testSpeech() }
        root.addView(testBtn)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addSeekBar(root: LinearLayout, label: String, initial: Int, min: Int, max: Int, cb: (Int) -> Unit): TextView {
        val tv = TextView(this)
        // label is already a resource string; just append %d%%
        tv.text = "$label: $initial%"
        tv.textSize = 14f
        tv.setPadding(0, dp(12), 0, dp(4))
        root.addView(tv)

        val bar = SeekBar(this)
        bar.max = max - min
        bar.progress = initial - min
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, f: Boolean) {
                cb(p + min)
            }

            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        root.addView(bar)
        return tv
    }

    private fun testSpeech() {
        if (engine == null || !engine!!.isInitialized()) {
            Toast.makeText(this, "Engine not initialized", Toast.LENGTH_SHORT).show()
            return
        }
        val preset = voiceProfile?.preset ?: 1
        // Pick a sample + dialect matching the current language setting
        val text: String
        val dialect: Int
        if (voiceConfig!!.isAutoDetect) {
            text = "Hello, this is a speech synthesis test."
                        dialect = EloquenceEngine.DIALECT_EN_US
        } else {
            val code = voiceConfig!!.voice
            val lang = VoiceConfig.findLang(code)
            text = sampleTextFor(lang.code)
            dialect = bcpToDialect(lang.code)
        }
        // If this build lacks the language module (e.g. zh-CN), synthesis is
                // impossible: fall back to English and say so.Hard rule: never feed
                // the engine an unlinked dialect (eciNewEx walks an invalid voice table
                // without the module — the test button once crashed).
        if (!EloquenceEngine.isShippedDialect(dialect)) {
            Toast.makeText(this, "Language not included in this build; previewing in English", Toast.LENGTH_LONG).show()
            val pcmEn = engine!!.synthesizeCore("Hello, this is a speech synthesis test.",
                EloquenceEngine.DIALECT_EN_US, voiceConfig!!.volume, preset,
                voiceConfig!!.pitch, voiceConfig!!.rate)
            if (pcmEn != null && pcmEn.size > 0) {
                playPcm(pcmEn, engine!!.getCoreSampleRate())
                Toast.makeText(this, "Spoken (English)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Synthesis failed", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val pcm = engine!!.synthesizeCore(text, dialect,
            voiceConfig!!.volume, preset,
            voiceConfig!!.pitch, voiceConfig!!.rate)
        if (pcm != null && pcm.size > 0) {
            playPcm(pcm, engine!!.getCoreSampleRate())
            Toast.makeText(this, "Spoken", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Synthesis failed", Toast.LENGTH_SHORT).show()
        }
    }

    /** Per-language sample texts */
    private fun sampleTextFor(code: String): String {
        if (code == null) return "Hello, this is a speech test."
        if (code.startsWith("zh")) return "Hello, this is a speech synthesis test."
        if (code.startsWith("en")) return "Hello, this is a speech synthesis test."
        if (code.startsWith("de")) return "Hallo, das ist ein Sprachsynthesetest."
        if (code.startsWith("fr")) return "Bonjour, ceci est un test de synthèse vocale."
        if (code.startsWith("es")) return "Hola, esta es una prueba de síntesis de voz."
        if (code.startsWith("it")) return "Ciao, questo è un test di sintesi vocale."
        if (code.startsWith("pt")) return "Olá, este é um teste de síntese de voz."
        if (code.startsWith("fi")) return "Hei, tämä on puhesynteesitesti."
        if (code.startsWith("ja")) return "これは音声合成のテストです。"
        if (code.startsWith("ko")) return "이것은 음성 합성 테스트입니다."
        return "Hello, this is a speech synthesis test."
    }

    /** bcp47 → ECI dialect (see VoiceConfig.LANGS; unlinked languages fall back to en-US) */
    private fun bcpToDialect(code: String): Int {
        val d = VoiceConfig.findLang(code).eciDialect
        return if (d != 0L) d.toInt() else EloquenceEngine.DIALECT_EN_US
    }

    private fun testGerman() {
        // English-voice comparison test: Reed(1) → Sandy(2) → Grandpa(8)
        Thread {
            val t0 = System.currentTimeMillis()
            val pcm1 = engine!!.synthesizeCore("Hello, this is a voice test.",
                EloquenceEngine.DIALECT_EN_US, voiceConfig!!.volume, 1)
            val pcm2 = engine!!.synthesizeCore("Hello, this is a voice test.",
                EloquenceEngine.DIALECT_EN_US, voiceConfig!!.volume, 2)
            val pcm3 = engine!!.synthesizeCore("Hello, this is a voice test.",
                EloquenceEngine.DIALECT_EN_US, voiceConfig!!.volume, 8)
            val ms = System.currentTimeMillis() - t0
            val total = (pcm1?.size ?: 0) + (pcm2?.size ?: 0) + (pcm3?.size ?: 0)
            Log.e("RoleTest", "three roles total=$total shorts in $ms ms")
            runOnUiThread {
                if (total > 0) {
                    Toast.makeText(this, "3 roles OK total=$total", Toast.LENGTH_SHORT).show()
                    // Play sequentially (one finishes before the next starts; avoids overlap)
                    val pcms = arrayOf(pcm1, pcm2, pcm3)
                    playSequential(pcms, 0, EloquenceEngine.SAMPLE_RATE)
                } else {
                    Toast.makeText(this, "Voice test failed (see log)", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun playPcm(pcm: ShortArray, sampleRate: Int) {
        val bytes = shortsToBytes(pcm)
        val track = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bytes.size, AudioTrack.MODE_STATIC)
        track.write(bytes, 0, bytes.size)
        track.play()
    }

    /** Play several PCM chunks sequentially (background thread; sleeps for each chunk's duration) */
    private fun playSequential(pcms: Array<ShortArray?>, idx: Int, sampleRate: Int) {
        Thread {
            for (i in idx until pcms.size) {
                val p = pcms[i]
                if (p == null || p.size == 0) continue
                playPcmAndWait(p, sampleRate)
            }
        }.start()
    }

    /** Play and block until done (duration = samples / rate) */
    private fun playPcmAndWait(pcm: ShortArray, sampleRate: Int) {
        val bytes = shortsToBytes(pcm)
        val track = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            bytes.size, AudioTrack.MODE_STATIC)
        track.write(bytes, 0, bytes.size)
        track.play()
        try {
            Thread.sleep((pcm.size * 1000.0 / sampleRate).toLong() + 120)
        } catch (ignore: InterruptedException) {
        }
        track.stop()
        track.release()
    }

    private fun shortsToBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = pcm[i]
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density).toInt()

    private fun refreshLangButton(btn: Button) {
        if (LanguageDetector.isDetectionEnabled()) {
            btn.text = "Auto detect"
        } else {
            val d = LanguageDetector.getFixedDialect()
            btn.text = dialectName(d)
        }
    }

    private fun dialectName(dialect: Int): String {
        if (dialect == LanguageDetector.DIALECT_EN_US) return "English (US)"
        if (dialect == LanguageDetector.DIALECT_EN_GB) return "English (UK)"
        if (dialect == LanguageDetector.DIALECT_DE_DE) return "German"
        if (dialect == LanguageDetector.DIALECT_FR_FR) return "French (France)"
        if (dialect == LanguageDetector.DIALECT_FR_CA) return "French (Canada)"
        if (dialect == LanguageDetector.DIALECT_ES_ES) return "Spanish (Spain)"
        if (dialect == LanguageDetector.DIALECT_ES_US) return "Spanish (US)"
        if (dialect == LanguageDetector.DIALECT_ES_MX) return "Spanish (Mexico)"
        if (dialect == LanguageDetector.DIALECT_IT_IT) return "Italian"
        if (dialect == LanguageDetector.DIALECT_JA_JP) return "Japanese"
        if (dialect == LanguageDetector.DIALECT_PL_PL) return "Polish"
        if (dialect == LanguageDetector.DIALECT_PT_BR) return "Portuguese (Brazil)"
        if (dialect == LanguageDetector.DIALECT_FI_FI) return "Finnish"
        return "English (US)"
    }

    private fun showLanguageDialog() {
        val items = arrayOf(
            "🔄 Auto detect",
            "English (US)",
            "English (UK)",
            "German",
            "French (France)",
            "French (Canada)",
            "Spanish (Spain)",
            "Spanish (US)",
            "Spanish (Mexico)",
            "Italian",
            "Japanese",
            "Polish",
            "Portuguese (Brazil)",
            "Finnish",
        )
        val dialects = intArrayOf(
            -1, // auto
            LanguageDetector.DIALECT_EN_US,
            LanguageDetector.DIALECT_EN_GB,
            LanguageDetector.DIALECT_DE_DE,
            LanguageDetector.DIALECT_FR_FR,
            LanguageDetector.DIALECT_FR_CA,
            LanguageDetector.DIALECT_ES_ES,
            LanguageDetector.DIALECT_ES_US,
            LanguageDetector.DIALECT_ES_MX,
            LanguageDetector.DIALECT_IT_IT,
            LanguageDetector.DIALECT_JA_JP,
            LanguageDetector.DIALECT_PL_PL,
            LanguageDetector.DIALECT_PT_BR,
            LanguageDetector.DIALECT_FI_FI,
        )
        AlertDialog.Builder(this)
            .setTitle("Language")
            .setItems(items) { _, which ->
                if (which == 0) {
                    LanguageDetector.setDetectionEnabled(true)
                } else {
                    LanguageDetector.setDetectionEnabled(false)
                    LanguageDetector.setFixedDialect(dialects[which])
                }
                saveLanguageSettings()
                refreshLangButton(langBtn!!)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Language-detection settings dialog */
    private fun showDetectionSettingsDialog() {
        val items = arrayOf(
            "Default language",
            "Detect languages",
            "English accent",
            "Spanish accent",
            "French accent",
        )
        AlertDialog.Builder(this)
            .setTitle("Language detection settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDefaultLanguageDialog()
                    1 -> showDetectionLanguagesDialog()
                    2 -> showEnglishAccentDialog()
                    3 -> showSpanishDialectDialog()
                    4 -> showFrenchDialectDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Default language (single choice; closes immediately). Only the
     * language family can be picked here; accents (US/UK, Spain/Mexico, etc.) are set by the accent dialogs. */
    private fun showDefaultLanguageDialog() {
        val items = arrayOf(
            "Unspecified",
            "English",
            "German",
            "French",
            "Spanish",
            "Italian",
            "Japanese",
            "Polish",
            "Portuguese",
            "Finnish",
        )
        // values[i] is the dialect for items[i] (English/French/Spanish use the current accent)
        val values = intArrayOf(
            LanguageDetector.DEFAULT_UNSPECIFIED,
            LanguageDetector.getEnglishDialect(),     // English (accent setting picks US/UK)
            LanguageDetector.DIALECT_DE_DE,
            LanguageDetector.getFrenchDialect(),      // French (accent setting picks France/Canada)
            LanguageDetector.getSpanishDialect(),     // Spanish (accent setting picks Spain/US/Mexico)
            LanguageDetector.DIALECT_IT_IT,
            LanguageDetector.DIALECT_JA_JP,
            LanguageDetector.DIALECT_PL_PL,
            LanguageDetector.DIALECT_PT_BR,
            LanguageDetector.DIALECT_FI_FI,
        )

        val cur = LanguageDetector.getDefaultLanguage()
        var checked = 0
        for (i in values.indices) {
            if (cur == values[i]) {
                checked = i
                break
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Default language")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setDefaultLanguage(values[which])
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Detect languages (multi-select) */
    private fun showDetectionLanguagesDialog() {
        // Current whitelist; default is English + Japanese
        val enabled = LanguageDetector.getEnabledLanguages()
        val checked = BooleanArray(LanguageDetector.ALL_LANG_CODES.size)
        for (i in LanguageDetector.ALL_LANG_CODES.indices) {
            val code = LanguageDetector.ALL_LANG_CODES[i]
            checked[i] = enabled != null && enabled.contains(code)
        }
        val names = LanguageDetector.ALL_LANG_NAMES
        // Mirror checkbox state into a temp array as the user toggles
        val finalChecked = checked.clone()
        AlertDialog.Builder(this)
            .setTitle("Detect languages")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                finalChecked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val newEnabled = HashSet<String>()
                for (i in finalChecked.indices) {
                    if (finalChecked[i]) {
                        newEnabled.add(LanguageDetector.ALL_LANG_CODES[i])
                    }
                }
                // Apply immediately (setEnabledLanguages rebuilds Lingua internally)
                LanguageDetector.setEnabledLanguages(newEnabled)
                saveLanguageSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** English accent */
    private fun showEnglishAccentDialog() {
        val items = arrayOf("American English (en-US)", "British English (en-GB)")
        val cur = LanguageDetector.getEnglishDialect()
        val checked = if (cur == LanguageDetector.DIALECT_EN_GB) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("English accent")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setEnglishDialect(
                    if (which == 1) LanguageDetector.DIALECT_EN_GB else LanguageDetector.DIALECT_EN_US)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Spanish accent */
        private fun showSpanishDialectDialog() {
            val items = arrayOf("Spanish (Spain) es-ES)", "Spanish (US) es-US)", "Spanish (Mexico) es-MX)")
            val cur = LanguageDetector.getSpanishDialect()
            val checked = when (cur) {
                LanguageDetector.DIALECT_ES_US -> 1
                LanguageDetector.DIALECT_ES_MX -> 2
                else -> 0
            }
            AlertDialog.Builder(this)
                .setTitle("Spanish accent")
                .setSingleChoiceItems(items, checked) { d, which ->
                    LanguageDetector.setSpanishDialect(
                        when (which) {
                            1 -> LanguageDetector.DIALECT_ES_US
                            2 -> LanguageDetector.DIALECT_ES_MX
                            else -> LanguageDetector.DIALECT_ES_ES
                        })
                    saveLanguageSettings()
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

    /** French accent */
        private fun showFrenchDialectDialog() {
            val items = arrayOf("French (France) fr-FR)", "French (Canada] fr-CA)")
            val cur = LanguageDetector.getFrenchDialect()
            val checked = if (cur == LanguageDetector.DIALECT_FR_CA) 1 else 0
            AlertDialog.Builder(this)
                .setTitle("French accent")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setFrenchDialect(
                    if (which == 1) LanguageDetector.DIALECT_FR_CA else LanguageDetector.DIALECT_FR_FR)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Audio quality: standard (original tone) or enhanced (de-hiss + limiter) */
    private fun showDspDialog() {
        val items = arrayOf("Standard (original tone)", "Enhanced (de-hiss)")
        val cur = voiceConfig!!.dspMode
        AlertDialog.Builder(this)
            .setTitle("Audio quality mode")
            .setSingleChoiceItems(items, cur) { d, which ->
                voiceConfig!!.setDspMode(which)
                dspBtn!!.let { refreshDspButton(it) }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshDspButton(btn: Button) {
        val name = if (voiceConfig!!.dspMode == 1) "Enhanced (de-hiss)" else "Standard (original tone)"
        btn.text = "Audio quality: " + name
    }

    // SharedPreferences persistence
    fun saveLanguageSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val e = prefs.edit()
        e.putBoolean("detection_enabled", LanguageDetector.isDetectionEnabled())
        e.putInt("fixed_dialect", LanguageDetector.getFixedDialect())
        e.putInt("chinese_dialect", LanguageDetector.getChineseDialect())
        e.putInt("english_dialect", LanguageDetector.getEnglishDialect())
        e.putInt("spanish_dialect", LanguageDetector.getSpanishDialect())
        e.putInt("french_dialect", LanguageDetector.getFrenchDialect())
        e.putInt("default_language", LanguageDetector.getDefaultLanguage())
        val enabled = LanguageDetector.getEnabledLanguages()
        if (enabled != null) {
            e.putStringSet("enabled_langs", enabled)
        } else {
            e.remove("enabled_langs")
        }
        e.commit()
    }

    fun restoreLanguageSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        LanguageDetector.setDetectionEnabled(prefs.getBoolean("detection_enabled", true))
        LanguageDetector.setFixedDialect(prefs.getInt("fixed_dialect", LanguageDetector.DIALECT_EN_US))
        LanguageDetector.setChineseDialect(prefs.getInt("chinese_dialect", LanguageDetector.DIALECT_ZH_CN))
        LanguageDetector.setEnglishDialect(prefs.getInt("english_dialect", LanguageDetector.DIALECT_EN_US))
        LanguageDetector.setSpanishDialect(prefs.getInt("spanish_dialect", LanguageDetector.DIALECT_ES_ES))
        LanguageDetector.setFrenchDialect(prefs.getInt("french_dialect", LanguageDetector.DIALECT_FR_FR))
        LanguageDetector.setDefaultLanguage(prefs.getInt("default_language", LanguageDetector.DEFAULT_UNSPECIFIED))
        // Detection whitelist: default English + Japanese (fresh install or never set)
        val enabled: Set<String>
        if (prefs.contains("enabled_langs")) {
            enabled = prefs.getStringSet("enabled_langs", null)!!
        } else {
            enabled = HashSet(listOf("en", "ja"))
        }
        LanguageDetector.setEnabledLanguages(enabled)
    }

    companion object {
        private const val PREFS_NAME = "vvtts_lang_settings"
    }
}