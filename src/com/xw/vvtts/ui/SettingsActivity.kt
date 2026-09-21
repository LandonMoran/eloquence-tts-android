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
import android.widget.EditText
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
    private var voiceBtn: Button? = null
    private var presetBtn: Button? = null
    private var punctBtn: Button? = null
    private val REQ_PROFILE = 701
    private val REQ_DICT_OPEN = 702
    private val REQ_DICT_CREATE = 703
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

        // Voice (spoken dialect: used by the test player, and as the fallback
        // dialect when the system TTS caller sends no voice name)
        val voiceBtnLocal = Button(this)
        voiceBtn = voiceBtnLocal
        val vvLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        vvLp.setMargins(0, dp(4), 0, 0)
        voiceBtnLocal.layoutParams = vvLp
        voiceBtnLocal.setOnClickListener { showVoiceDialog() }
        root.addView(voiceBtnLocal)
        refreshVoiceButton(voiceBtnLocal)

    // Preset voice picker (ETI: the 8 character voices). Live label so the
        // "change the actual voice" control is discoverable.
        val presetBtnLocal = Button(this)
        presetBtn = presetBtnLocal
        presetBtnLocal.setOnClickListener {
            startActivityForResult(Intent(this, VoiceProfileActivity::class.java), REQ_PROFILE)
        }
        val pvLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pvLp.setMargins(0, dp(4), 0, 0)
        presetBtnLocal.layoutParams = pvLp
        root.addView(presetBtnLocal)
        refreshPresetButton(presetBtnLocal)

        // Language-detection settings
        val langDetectBtn = Button(this)
        langDetectBtn.text = "Language detection settings"
        val ldLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ldLp.setMargins(0, dp(4), 0, 0)
        langDetectBtn.layoutParams = ldLp
        langDetectBtn.setOnClickListener { showDetectionSettingsDialog() }
        root.addView(langDetectBtn)
    // Punctuation: read marks aloud (ETI: eloquence_tts_punctuation_enable)
        val punctBtnLocal = Button(this)
        punctBtn = punctBtnLocal
        punctBtnLocal.setOnClickListener { showPunctuationDialog() }
        val puLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        puLp.setMargins(0, dp(4), 0, 0)
        punctBtnLocal.layoutParams = puLp
        root.addView(punctBtnLocal)
        refreshPunctButton(punctBtnLocal)

        // User dictionary: word -> spoken replacement (add/list/import/export)
        val dictBtnLocal = Button(this)
        dictBtnLocal.text = "User dictionary"
        dictBtnLocal.setOnClickListener { showDictMenuDialog() }
        val dlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlp.setMargins(0, dp(4), 0, 0)
        dictBtnLocal.layoutParams = dlp
        root.addView(dictBtnLocal)
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
    // Reset defaults (ETI parity): wipes voice/profile/language prefs
        val resetBtnLocal = Button(this)
        resetBtnLocal.text = "Reset defaults"
        resetBtnLocal.setOnClickListener { confirmResetDefaults() }
        val rlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.setMargins(0, dp(4), 0, 0)
        resetBtnLocal.layoutParams = rlp
        root.addView(resetBtnLocal)

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
        // If this build lacks the language module (e.g. zh-TW), synthesis is
                // impossible: fall back to English and say so. Hard rule: never feed
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
        if (code.startsWith("zh")) return "\u4f60\u597d\u3002"
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
        if (dialect == LanguageDetector.DIALECT_ZH_CN) return "Chinese (Mandarin)"
        return "English (US)"
    }

    /** Voice picker: single choice over dialects actually included in this build */
    private fun showVoiceDialog() {
        val voices = VoiceConfig.LANGS.filter { it.eciDialect != 0L }
        val codes = voices.map { it.code }.toTypedArray()
        val labels = voices.map { it.name }.toTypedArray()
        val cur = voiceConfig!!.voice
        var checked = codes.indexOfFirst { it.equals(cur, ignoreCase = true) }
        if (checked < 0) checked = 0
        AlertDialog.Builder(this)
            .setTitle("Voice")
            .setSingleChoiceItems(labels, checked) { _, which ->
                voiceConfig!!.setVoice(codes[which])
                refreshVoiceButton(voiceBtn!!)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshVoiceButton(btn: Button) {
        val lang = VoiceConfig.findLang(voiceConfig!!.voice)
        btn.text = "Voice: " + lang.name
    }

    /** Preset voice button: shows the active character voice name. */
    private fun refreshPresetButton(btn: Button) {
        val p = voiceProfile?.preset ?: 1
        val name = VoiceProfile.PRESET_NAMES.getOrNull(p - 1) ?: "Reed"
        btn.text = "Preset voice: " + name
    }

    private fun showPunctuationDialog() {
        val cur = if (voiceConfig!!.punctEnabled) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Punctuation")
            .setSingleChoiceItems(arrayOf("Pauses only", "Speak marks aloud"), cur) { d, which ->
                voiceConfig!!.setPunctEnabled(which == 1)
                d.dismiss()
                punctBtn?.let { refreshPunctButton(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshPunctButton(btn: Button) {
        val on = voiceConfig!!.punctEnabled
        btn.text = "Punctuation: " + (if (on) "speak marks" else "pauses only")
    }
    private fun showDictMenuDialog() {
        val items = arrayOf("Add word...", "Word list...", "Import file...", "Export file...", "Clear dictionary")
        AlertDialog.Builder(this)
            .setTitle("User dictionary")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDictAddDialog()
                    1 -> showDictListDialog()
                    2 -> {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "*/*"
                        startActivityForResult(i, REQ_DICT_OPEN)
                    }
                    3 -> {
                        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "text/plain"
                        i.putExtra(Intent.EXTRA_TITLE, "eloquence_dictionary.txt")
                        startActivityForResult(i, REQ_DICT_CREATE)
                    }
                    else -> {
                        voiceConfig!!.clearDict()
                        Toast.makeText(this, "Dictionary cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDictAddDialog() {
        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        wrapper.setPadding(pad, pad, pad, pad)
        val wordInput = EditText(this)
        wordInput.hint = "Word (written form)"
        val speakInput = EditText(this)
        speakInput.hint = "How it should be spoken"
        wrapper.addView(wordInput)
        wrapper.addView(speakInput)
        AlertDialog.Builder(this)
            .setTitle("Add dictionary word")
            .setView(wrapper)
            .setPositiveButton("Add") { _, _ ->
                val w = wordInput.text.toString().trim()
                val s = speakInput.text.toString().trim()
                if (w.isNotEmpty() && s.isNotEmpty()) {
                    voiceConfig!!.addDictEntry(w, s)
                    Toast.makeText(this, "Added: " + w, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Both fields are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun showDictListDialog() {
        val entries = voiceConfig!!.dictEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, "Dictionary is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { it.first + " -> " + it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Dictionary (" + entries.size + ")")
            .setItems(labels) { _, which ->
                AlertDialog.Builder(this)
                    .setMessage("Delete '" + entries[which].first + "'?")
                    .setPositiveButton("Delete") { _, _ ->
                        voiceConfig!!.removeDictEntry(entries[which].first)
                        showDictListDialog()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setPositiveButton("Done", null)
            .setNegativeButton("Clear all") { _, _ ->
                voiceConfig!!.clearDict()
                Toast.makeText(this, "Dictionary cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Read a SAF file with BOM sniffing (UTF-8 / UTF-16LE / UTF-16BE, like the ETI importer). */
    private fun readTextFromUri(uri: android.net.Uri): String {
        val inp = contentResolver.openInputStream(uri) ?: return ""
        val raw = inp.readBytes()
        inp.close()
        return when {
            raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte() ->
                String(raw, 3, raw.size - 3, Charsets.UTF_8)
            raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte() ->
                String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
            raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte() ->
                String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
            else -> String(raw, Charsets.UTF_8)
        }
    }

    private fun importDictFromUri(uri: android.net.Uri) {
        var added = 0
        for (line in readTextFromUri(uri).split("\n")) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val sep = when {
                s.contains('|') -> '|'
                s.contains('\t') -> '\t'
                else -> ','
            }
            val idx = s.indexOf(sep)
            if (idx <= 0 || idx >= s.length - 1) continue
            val w = s.substring(0, idx).trim()
            val sp = s.substring(idx + 1).trim()
            if (w.isEmpty() || sp.isEmpty()) continue
            if (w.equals("word", ignoreCase = true) && sp.equals("replacement", ignoreCase = true)) continue
            voiceConfig!!.addDictEntry(w, sp)
            added++
        }
        Toast.makeText(this, "Imported: " + added + " words", Toast.LENGTH_SHORT).show()
    }

    private fun exportDictToUri(uri: android.net.Uri) {
        val out = contentResolver.openOutputStream(uri) ?: return
        val sb = StringBuilder()
        sb.append('\uFEFF')
        for ((w, s) in voiceConfig!!.dictEntries()) {
            sb.append(w).append('|').append(s).append('\n')
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.close()
        Toast.makeText(this, "Dictionary exported", Toast.LENGTH_SHORT).show()
    }
    private fun confirmResetDefaults() {
        AlertDialog.Builder(this)
            .setTitle("Reset defaults")
            .setMessage("Reset voice, speed, pitch, volume, language detection, dictionary and punctuation to defaults?")
            .setPositiveButton("Reset") { _, _ -> doResetDefaults() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doResetDefaults() {
        for (name in arrayOf("vvtts_prefs", "vvtts_voice_profile", "vvtts_lang_settings")) {
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
        }
        voiceConfig = VoiceConfig(this)
        voiceProfile = VoiceProfile(this)
        engine!!.setVoiceProfile(voiceProfile)
        engine!!.applyVoiceProfile(EloquenceEngine.DIALECT_ZH_CN, voiceProfile)
        restoreLanguageSettings()
        langBtn?.let { refreshLangButton(it) }
        voiceBtn?.let { refreshVoiceButton(it) }
        presetBtn?.let { refreshPresetButton(it) }
        punctBtn?.let { refreshPunctButton(it) }
        dspBtn?.let { refreshDspButton(it) }
        rateVal?.text = getString(R.string.rate_fmt, voiceConfig!!.rate)
        pitchVal?.text = getString(R.string.pitch_fmt, voiceConfig!!.pitch)
        volumeVal?.text = getString(R.string.volume_fmt, voiceConfig!!.volume)
        Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PROFILE -> {
                presetBtn?.let { refreshPresetButton(it) }
                engine!!.setVoiceProfile(voiceProfile)
                engine!!.applyVoiceProfile(EloquenceEngine.DIALECT_ZH_CN, voiceProfile)
            }
            REQ_DICT_OPEN -> if (data?.data != null) importDictFromUri(data.data!!)
            REQ_DICT_CREATE -> if (data?.data != null) exportDictToUri(data.data!!)
        }
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
            "Chinese (Mandarin)",
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
            LanguageDetector.DIALECT_ZH_CN,
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
            "Chinese",
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
            LanguageDetector.DIALECT_ZH_CN,
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