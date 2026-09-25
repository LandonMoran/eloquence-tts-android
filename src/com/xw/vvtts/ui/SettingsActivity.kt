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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private var pitchVal: TextView? = null
    private var volumeVal: TextView? = null
    private val charParamIds = intArrayOf(VoiceProfile.PARAM_HEAD_SIZE, VoiceProfile.PARAM_ROUGHNESS, VoiceProfile.PARAM_BREATHINESS, VoiceProfile.PARAM_PITCH_FLUC)
    private val charLabelRes = intArrayOf(R.string.voice_head, R.string.voice_roughness, R.string.voice_breathiness, R.string.voice_inflection)
    private val charBars = arrayOfNulls<SeekBar>(charParamIds.size)
    private val charVals = arrayOfNulls<TextView>(charParamIds.size)
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
        engine!!.initialize()
        engine!!.setVoiceProfile(voiceProfile)
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
                } else if ("crash" == autotest) {
                    val hkText = intent?.getStringExtra("crashtext")
                        ?: "Hello, this is a speech synthesis test."
                    Handler(Looper.getMainLooper()).postDelayed({ testCrash(hkText) }, 3000)
                }
        // Apply the current role config automatically
        // ---- Tab bar (RadioGroup so TalkBack announces "radio button, checked") ----
                val outer = LinearLayout(this)
                outer.orientation = LinearLayout.VERTICAL
                outer.setPadding(dp(16), dp(16), dp(16), dp(16))
                val title = TextView(this)
                title.text = getString(R.string.title_main)
                title.textSize =  24f
                title.setTextColor(getColor(R.color.m3_on_surface))
                val tlLp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tlLp.setMargins(dp(4), dp(4),0,dp(8))
                title.layoutParams = tlLp
                outer.addView(title)
                val tabGroup = RadioGroup(this)
                tabGroup.orientation = RadioGroup.HORIZONTAL
                val tabIds = intArrayOf(1,2,3,4,5)
                val tabLabels = arrayOf(
                    getString(R.string.tab_voice),
                    getString(R.string.tab_speech),
                    getString(R.string.tab_reading),
                    getString(R.string.tab_detection),
                    getString(R.string.tab_tools),
                )
                for (i in tabIds.indices) {
                    val rb = RadioButton(this)
                    rb.id = tabIds[i]
                    rb.text = if (i == 3) getString(R.string.tab_detection_short) else tabLabels[i]
                    rb.contentDescription = tabLabels[i]
                    rb.textSize =  14f
                    rb.minHeight = dp(48)
                    rb.setPadding(dp(8), dp(4), dp(8), dp(4))
                    tabGroup.addView(rb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                outer.addView(tabGroup)
                // ---- Scroll + per-tab panels ----
                val scroll = ScrollView(this)
                val panels = LinearLayout(this)
                panels.orientation = LinearLayout.VERTICAL
                val panelVoice = LinearLayout(this)
                panelVoice.orientation = LinearLayout.VERTICAL
                panelVoice.setPadding(0, dp(8),0,0)
                val panelSpeech = LinearLayout(this)
                panelSpeech.orientation = LinearLayout.VERTICAL
                panelSpeech.setPadding(0, dp(8),0,0)
                val panelReading = LinearLayout(this)
                panelReading.orientation = LinearLayout.VERTICAL
                panelReading.setPadding(0, dp(8),0,0)
                val panelDetection = LinearLayout(this)
                panelDetection.orientation = LinearLayout.VERTICAL
                panelDetection.setPadding(0, dp(8),0,0)
                val panelTools = LinearLayout(this)
                panelTools.orientation = LinearLayout.VERTICAL
                panelTools.setPadding(0, dp(8),0,0)
                val allPanels = arrayOf(panelVoice, panelSpeech, panelReading, panelDetection, panelTools)
                tabGroup.setOnCheckedChangeListener { _, checkedId ->
                    for (i in allPanels.indices) {
                        allPanels[i].visibility = if (tabIds[i] == checkedId) View.VISIBLE else View.GONE
                val activeIndex = allPanels.indices.firstOrNull { tabIds[it] == checkedId }
                if (activeIndex != null) {
                    val names = arrayOf("Voice settings", "Speech settings", "Reading settings", "Detection settings", "Tools")
                    allPanels[activeIndex].announceForAccessibility(names[activeIndex])
                }
                    }
                }
                // ---- Voice tab: preset voice + spoken voice + test ----
                addSectionHeader(panelVoice, R.string.sec_voice)
                val presetBtnLocal = addRow(this, panelVoice)
                presetBtn = presetBtnLocal
                presetBtnLocal.setOnClickListener {
                    startActivityForResult(Intent(this, VoiceProfileActivity::class.java), REQ_PROFILE)
                }
                refreshPresetButton(presetBtnLocal)
                val voiceBtnLocal = addRow(this, panelVoice)
                voiceBtn = voiceBtnLocal
                voiceBtnLocal.setOnClickListener { showVoiceDialog() };
                refreshVoiceButton(voiceBtnLocal)
                val langBtnLocal = addRow(this, panelVoice)
                langBtn = langBtnLocal
                langBtnLocal.setOnClickListener { showLanguageDialog() }
                refreshLangButton(langBtnLocal)
                // ---- Voice character: head size/roughness/breathiness/inflection (IBMTTS-style) ----
                addSectionHeader(panelVoice, R.string.sec_voice_char)
                addVoiceCharSliders(panelVoice)
                addFilledButton(panelVoice, R.string.test, dp(16)).setOnClickListener { testSpeech() };
                // ---- Speech tab: pitch/volume ----
                addSectionHeader(panelSpeech, R.string.sec_speech)
                pitchVal = addSeekBar(panelSpeech, getString(R.string.pitch), voiceConfig!!.pitch,0,100) { v ->
                    voiceConfig!!.setPitch(v)
                    pitchVal!!.text = getString(R.string.pitch_fmt, v)
                };
                volumeVal = addSeekBar(panelSpeech, getString(R.string.volume), voiceConfig!!.volume,0,100) { v ->
                    voiceConfig!!.setVolume(v)
                    volumeVal!!.text = getString(R.string.volume_fmt, v)
                };
                // ---- Reading tab: punctuation ----
                addSectionHeader(panelReading, R.string.sec_reading)
                val punctBtnLocal = addRow(this, panelReading)
                punctBtn = punctBtnLocal
                punctBtnLocal.setOnClickListener { showPunctuationDialog() };
                refreshPunctButton(punctBtnLocal)
                // ---- Detection tab: language picker + detection settings ----
                val langDetectBtn = addRow(this, panelDetection)
                langDetectBtn.text = getString(R.string.lang_detect_button)
                langDetectBtn.setOnClickListener { showDetectionSettingsDialog() };
                // ---- Tools tab: dictionary, reset, about ----
                addSectionHeader(panelTools, R.string.sec_dictionary)
                val dictBtnLocal = addRow(this, panelTools)
                dictBtnLocal.text = getString(R.string.user_dict_button)
                dictBtnLocal.setOnClickListener { showDictMenuDialog() };
                addSectionHeader(panelTools, R.string.sec_maintenance)
                val resetBtnLocal = addRow(this, panelTools)
                resetBtnLocal.text = getString(R.string.reset_button)
                resetBtnLocal.setOnClickListener { confirmResetDefaults() };
                val aboutBtnLocal = addRow(this, panelTools)
                aboutBtnLocal.text = getString(R.string.about_row)
                aboutBtnLocal.setOnClickListener { showAboutDialog() };
                panels.addView(panelVoice)
                panels.addView(panelSpeech)
                panels.addView(panelReading)
                panels.addView(panelDetection)
                panels.addView(panelTools)
                scroll.addView(panels)
                outer.addView(scroll)
                setContentView(outer)
                tabGroup.check(tabIds[0])
            }

    private fun addSeekBar(root: LinearLayout, label: String, initial: Int, min: Int, max: Int, barOut: ((SeekBar) -> Unit)? = null, cb:(Int) -> Unit): TextView {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = getDrawable(R.drawable.bg_slider)
        card.setPadding(dp(16), dp(12), dp(16), dp(8))
        val cLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cLp.setMargins(0, 0, 0, dp(8))
        card.layoutParams = cLp
        root.addView(card)

        val tv = TextView(this)
        // label is already a resource string; just append %d%%
        tv.text = "$label: $initial%"
        tv.textSize = 14f
        tv.setTextColor(getColor(R.color.m3_on_surface_variant))
        card.addView(tv)

        val bar = SeekBar(this)
        bar.id = View.generateViewId()
        tv.setLabelFor(bar.id)
        bar.max = max - min
        bar.progress = initial - min
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, f: Boolean) {
                cb(p + min)
            }

            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        card.addView(bar)
        barOut?.invoke(bar)
        return tv
    }

    private fun addVoiceCharSliders(root: LinearLayout) {
        val vp = voiceProfile ?: return
        val preset = vp.preset
        val min = 0
        val max = 100
        for (i in charLabelRes.indices) {
            val p = charParamIds[i]
            val label = getString(charLabelRes[i])
            val cb: (Int) -> Unit = { v ->
                vp.setParam(preset, p, v)
                charVals[i]?.text = getString(R.string.voice_param_fmt, label, v)
            }
            charVals[i] = addSeekBar(root, label, vp.getParam(preset, p), min, max, { charBars[i] = it }, cb)
        }
    }

    private fun refreshVoiceCharSliders() {
        val vp = voiceProfile ?: return
        val preset = vp.preset
        for (i in charParamIds.indices) {
            val b = charBars[i] ?: continue
            b.progress = vp.getParam(preset, charParamIds[i])
            charVals[i]?.text = getString(R.string.voice_param_fmt, getString(charLabelRes[i]), b.progress)
        }
    }

    private fun testSpeech() {
        if (engine == null || !engine!!.isInitialized()) {
            Toast.makeText(this, getString(R.string.engine_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        val preset = voiceProfile?.preset ?: 1
        // Pick a sample + dialect matching the current language setting
        val text: String
        val dialect: Int
        if (LanguageDetector.isDetectionEnabled()) {
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
            Toast.makeText(this, getString(R.string.preview_in_english), Toast.LENGTH_LONG).show()
            val pcmEn = engine!!.synthesizeCore("Hello, this is a speech synthesis test.",
                EloquenceEngine.DIALECT_EN_US, voiceConfig!!.volume, preset,
                voiceConfig!!.pitch, 100)
            if (pcmEn != null && pcmEn.size > 0) {
                playPcm(pcmEn, engine!!.getCoreSampleRate())
                Toast.makeText(this, getString(R.string.spoken_en), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.synth_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val pcm = engine!!.synthesizeCore(text, dialect,
            voiceConfig!!.volume, preset,
            voiceConfig!!.pitch, 100)
        if (pcm != null && pcm.size > 0) {
            playPcm(pcm, engine!!.getCoreSampleRate())
            Toast.makeText(this, getString(R.string.synth_ok), Toast.LENGTH_SHORT).show()
        } else {
                    Toast.makeText(this, getString(R.string.synth_failed), Toast.LENGTH_SHORT).show()
                }
            }

            /** Crash-repro hook: synthesize one exact string through the real path */
            private fun testCrash(text: String) {
                if (engine == null || !engine!!.isInitialized()) return
                Log.i("CRASHHOOK", "start:" + text)
                val pcm2 = try {
                    engine!!.synthesizeCore(text, EloquenceEngine.DIALECT_EN_US,
                        voiceConfig!!.volume, voiceProfile?.preset ?: 1, voiceConfig!!.pitch, 100)
                } catch (t: Throwable) {
                    Log.e("CRASHHOOK", "exception", t)
                    null
                }
                Log.i("CRASHHOOK", if (pcm2 != null && pcm2.size > 0) "done:" + pcm2.size else "fail")
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
            btn.text = getString(R.string.lang_auto)
        } else {
            val d = LanguageDetector.getFixedDialect()
            btn.text = dialectName(d)
        }
    }

    private fun dialectName(dialect: Int): String {
        if (dialect == LanguageDetector.DIALECT_EN_US) return getString(R.string.dialect_en_us)
        if (dialect == LanguageDetector.DIALECT_EN_GB) return getString(R.string.dialect_en_gb)
        if (dialect == LanguageDetector.DIALECT_DE_DE) return getString(R.string.dialect_de_de)
        if (dialect == LanguageDetector.DIALECT_FR_FR) return getString(R.string.dialect_fr_fr)
        if (dialect == LanguageDetector.DIALECT_FR_CA) return getString(R.string.dialect_fr_ca)
        if (dialect == LanguageDetector.DIALECT_ES_ES) return getString(R.string.dialect_es_es)
        if (dialect == LanguageDetector.DIALECT_ES_US) return getString(R.string.dialect_es_us)
        if (dialect == LanguageDetector.DIALECT_ES_MX) return getString(R.string.dialect_es_mx)
        if (dialect == LanguageDetector.DIALECT_IT_IT) return getString(R.string.dialect_it_it)
        if (dialect == LanguageDetector.DIALECT_JA_JP) return getString(R.string.dialect_ja_jp)
        if (dialect == LanguageDetector.DIALECT_PL_PL) return getString(R.string.dialect_pl_pl)
        if (dialect == LanguageDetector.DIALECT_PT_BR) return getString(R.string.dialect_pt_br)
        if (dialect == LanguageDetector.DIALECT_FI_FI) return getString(R.string.dialect_fi_fi)
        if (dialect == LanguageDetector.DIALECT_ZH_CN) return getString(R.string.dialect_zh_cn)
        return getString(R.string.dialect_en_us)
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
            .setTitle(getString(R.string.voice_dlg_title))
            .setSingleChoiceItems(labels, checked) { _, which ->
                voiceConfig!!.setVoice(codes[which])
                refreshVoiceButton(voiceBtn!!)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun refreshVoiceButton(btn: Button) {
        val lang = VoiceConfig.findLang(voiceConfig!!.voice)
        btn.text = getString(R.string.voice_fmt, lang.name)
    }

    /** Preset voice button: shows the active character voice name. */
    private fun refreshPresetButton(btn: Button) {
        val p = voiceProfile?.preset ?: 1
        val name = VoiceProfile.PRESET_NAMES.getOrNull(p - 1) ?: "Reed"
        btn.text = getString(R.string.preset_voice_fmt, name)
    }

    private fun showPunctuationDialog() {
        val cur = if (voiceConfig!!.punctEnabled) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.punct_title))
            .setSingleChoiceItems(
                arrayOf(getString(R.string.punct_pauses_only), getString(R.string.punct_speak_marks)),
                cur,
            ) { d, which ->
                voiceConfig!!.setPunctEnabled(which == 1)
                d.dismiss()
                punctBtn?.let { refreshPunctButton(it) }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun refreshPunctButton(btn: Button) {
        val on = voiceConfig!!.punctEnabled
        btn.text = getString(
            R.string.punctuation_fmt,
            if (on) getString(R.string.punct_speak_marks) else getString(R.string.punct_pauses_only)
        )
    }
    private fun showDictMenuDialog() {
        val items = arrayOf(
            getString(R.string.dict_add_word),
            getString(R.string.dict_word_list),
            getString(R.string.dict_import_file),
            getString(R.string.dict_export_file),
            getString(R.string.dict_clear)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dict_menu_title))
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
                    else -> showDictClearConfirm()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDictAddDialog() {
        val wrapper = LinearLayout(this)
        wrapper.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        wrapper.setPadding(pad, pad, pad, pad)
        val wordInput = EditText(this)
        wordInput.hint = getString(R.string.dict_word_hint)
        val speakInput = EditText(this)
        speakInput.hint = getString(R.string.dict_speak_hint)
        wrapper.addView(wordInput)
        wrapper.addView(speakInput)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dict_add_title))
            .setView(wrapper)
            .setPositiveButton(getString(R.string.dict_add)) { _, _ ->
                val w = wordInput.text.toString().trim()
                val s = speakInput.text.toString().trim()
                if (w.isNotEmpty() && s.isNotEmpty()) {
                    voiceConfig!!.addDictEntry(w, s)
                    Toast.makeText(this, getString(R.string.dict_added_fmt, w), Toast.LENGTH_SHORT).show()
                } else {
                Toast.makeText(this, getString(R.string.dict_both_required), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    private fun showDictListDialog() {
        val entries = voiceConfig!!.dictEntries()
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.dict_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { it.first + " -> " + it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dict_list_title, entries.size))
            .setItems(labels) { _, which ->
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.dict_delete_msg, entries[which].first))
                    .setPositiveButton(getString(R.string.dict_delete)) { _, _ ->
                        voiceConfig!!.removeDictEntry(entries[which].first)
                        showDictListDialog()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setPositiveButton(getString(R.string.dict_done), null)
                        .setNegativeButton(getString(R.string.dict_clear_all)) { _, _ ->
                            showDictClearConfirm()
                        }
                        .show()
                }

                /** Confirm before wiping the whole dictionary (menu "Clear dictionary", list "Clear all"). */
                private fun showDictClearConfirm() {
                    val builder = AlertDialog.Builder(this)
                    builder.setTitle(getString(R.string.dict_clear))
                    builder.setMessage(getString(R.string.dict_clear_confirm))
                    builder.setPositiveButton(getString(R.string.dict_clear)) { _, _ ->
                        voiceConfig!!.clearDict()
                        Toast.makeText(this, getString(R.string.dict_cleared), Toast.LENGTH_SHORT).show()
                    }
                    builder.setNegativeButton(getString(R.string.cancel), null)
                    builder.show()
                }

                /** Read a SAF file with BOM sniffing (UTF-8 / UTF-16LE / UTF-16BE, like the ETI importer. */
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
            Toast.makeText(this, getString(R.string.dict_imported, added), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.dict_exported), Toast.LENGTH_SHORT).show()
    }
    private fun confirmResetDefaults() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_title))
            .setMessage(getString(R.string.reset_msg))
            .setPositiveButton(getString(R.string.reset_confirm)) { _, _ -> doResetDefaults() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun doResetDefaults() {
        for (name in arrayOf("vvtts_prefs", "vvtts_voice_profile", "vvtts_lang_settings")) {
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
        }
        voiceConfig = VoiceConfig(this)
        voiceProfile = VoiceProfile(this)
        restoreLanguageSettings()
        langBtn?.let { refreshLangButton(it) }
        voiceBtn?.let { refreshVoiceButton(it) }
        presetBtn?.let { refreshPresetButton(it) }
        punctBtn?.let { refreshPunctButton(it) }
        pitchVal?.text = getString(R.string.pitch_fmt, voiceConfig!!.pitch)
        volumeVal?.text = getString(R.string.volume_fmt, voiceConfig!!.volume)
        refreshVoiceCharSliders()
        Toast.makeText(this, getString(R.string.reset_done), Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PROFILE -> {
                presetBtn?.let { refreshPresetButton(it) }
                refreshVoiceCharSliders()
                presetBtn?.let { refreshPresetButton(it) }
            }
            REQ_DICT_OPEN -> if (data?.data != null) importDictFromUri(data.data!!)
            REQ_DICT_CREATE -> if (data?.data != null) exportDictToUri(data.data!!)
        }
    }

    private fun showLanguageDialog() {
        val items = arrayOf(
            getString(R.string.lang_auto_detect),
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
                    .setTitle(getString(R.string.language_dlg_title))
                    .setItems(items) { _,which ->
                    if (which == 0) {
                        LanguageDetector.setDetectionEnabled(true)
                        // Auto: the spoken-voice locale follows the detected text;
                        // clear a stale zh voice pin (else a previous "Voice: Chinese" pick would force every utterance to Chinese),
                        // but only when the user's own pick is zh — never silently discard a non-zh voice.
                        if (voiceConfig!!.voice.lowercase().startsWith("zh")) voiceConfig!!.setVoice("en-US")
                        voiceConfig!!.setAutoDetect(true)
                    } else {
                        LanguageDetector.setDetectionEnabled(false)
                        LanguageDetector.setFixedDialect(dialects[which])
                        voiceConfig!!.setAutoDetect(false)
                    }
                    saveLanguageSettings()
                    refreshLangButton(langBtn!!)
                    voiceBtn?.let { refreshVoiceButton(it) }
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }

    /** Language-detection settings dialog */
    private fun showDetectionSettingsDialog() {
        val items = arrayOf(
            getString(R.string.lang_default_item),
            getString(R.string.lang_detect_langs_item),
            getString(R.string.lang_accent_en_item),
            getString(R.string.lang_accent_es_item),
            getString(R.string.lang_accent_fr_item),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_detect_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDefaultLanguageDialog()
                    1 -> showDetectionLanguagesDialog()
                    2 -> showEnglishAccentDialog()
                    3 -> showSpanishDialectDialog()
                    4 -> showFrenchDialectDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Default language (single choice; closes immediately). Only the
     * language family can be picked here; accents (US/UK, Spain/Mexico, etc.) are set by the accent dialogs. */
    private fun showDefaultLanguageDialog() {
        val items = arrayOf(
            getString(R.string.lang_unspecified),
            getString(R.string.lang_english),
            getString(R.string.lang_german),
            getString(R.string.lang_french),
            getString(R.string.lang_spanish),
            getString(R.string.lang_italian),
            getString(R.string.lang_japanese),
            getString(R.string.lang_polish),
            getString(R.string.lang_portuguese),
            getString(R.string.lang_finnish),
            getString(R.string.lang_chinese),
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
            .setTitle(getString(R.string.lang_dlg_default_title))
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setDefaultLanguage(values[which])
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
            .setTitle(getString(R.string.lang_dlg_detect_title))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                finalChecked[which] = isChecked
            }
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
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
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** English accent */
    private fun showEnglishAccentDialog() {
        val items = arrayOf(getString(R.string.accent_en_us), getString(R.string.accent_en_gb))
        val cur = LanguageDetector.getEnglishDialect()
        val checked = if (cur == LanguageDetector.DIALECT_EN_GB) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_accent_en_item))
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setEnglishDialect(
                    if (which == 1) LanguageDetector.DIALECT_EN_GB else LanguageDetector.DIALECT_EN_US)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Spanish accent */
        private fun showSpanishDialectDialog() {
        val items = arrayOf(getString(R.string.accent_es_es), getString(R.string.accent_es_us), getString(R.string.accent_es_mx))
            val cur = LanguageDetector.getSpanishDialect()
            val checked = when (cur) {
                LanguageDetector.DIALECT_ES_US -> 1
                LanguageDetector.DIALECT_ES_MX -> 2
                else -> 0
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.lang_accent_es_item))
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
            .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

    /** French accent */
        private fun showFrenchDialectDialog() {
        val items = arrayOf(getString(R.string.accent_fr_fr), getString(R.string.accent_fr_ca))
            val cur = LanguageDetector.getFrenchDialect()
            val checked = if (cur == LanguageDetector.DIALECT_FR_CA) 1 else 0
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.lang_accent_fr_item))
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setFrenchDialect(
                    if (which == 1) LanguageDetector.DIALECT_FR_CA else LanguageDetector.DIALECT_FR_FR)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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


    /** M3 section header (small caps, primary color) */
    private fun addSectionHeader(root: LinearLayout, labelRes: Int) {
        val tv = TextView(this)
        tv.text = getString(labelRes)
        tv.setAccessibilityHeading(true)
        tv.setAllCaps(true)
        tv.textSize = 12f
        tv.setLetterSpacing(0.08f)
        tv.setTextColor(getColor(R.color.m3_primary))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(8), dp(20), 0, dp(8))
        tv.layoutParams = lp
        root.addView(tv)
    }

    /** M3 list row: rounded surface, 48dp+ touch target. */
    private fun addRow(owner: Activity, root: LinearLayout): Button {
        val btn = Button(owner)
        btn.background = owner.getDrawable(R.drawable.bg_row)
        btn.setTextColor(owner.getColor(R.color.m3_on_surface))
        btn.textSize = 16f
        btn.minHeight = dp(48)
        btn.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        btn.setPadding(dp(16), 0, dp(16), 0)
        btn.stateListAnimator = null
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(8))
        btn.layoutParams = lp
        root.addView(btn)
        return btn
    }

    /** M3 filled button (primary surface, 52dp target). */
    private fun addFilledButton(root: LinearLayout, labelRes: Int, topMargin: Int): Button {
        val btn = Button(this)
        btn.text = getString(labelRes)
        btn.background = getDrawable(R.drawable.bg_btn)
        btn.setTextColor(getColor(R.color.m3_on_primary))
        btn.textSize = 16f
        btn.minHeight = dp(52)
        btn.gravity = android.view.Gravity.CENTER
        btn.stateListAnimator = null
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, topMargin, 0, dp(8))
        btn.layoutParams = lp
        root.addView(btn)
        return btn
    }

    /** About dialog (ETI parity: version + credits). */
    private fun showAboutDialog() {
        var version = "?"
        try {
            version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (ignore: Exception) {
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_body_fmt, version))
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
    companion object {
        private const val PREFS_NAME = "vvtts_lang_settings"
    }
}