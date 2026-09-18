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
        engine!!.setVoiceProfile(voiceProfile) // 让合成读取角色自定义覆盖
        engine!!.initialize()
        // 预加载 Lingua 语言检测器（后台线程，避免首次合成卡顿）
        LanguageDetector.preloadLingua()
        // 从 SharedPreferences 恢复语言检测设置
        restoreLanguageSettings()
        // 自动测试钩子：am start --es autotest chinese
        val autotest = intent?.getStringExtra("autotest")
        if ("chinese" == autotest) {
            Handler(Looper.getMainLooper()).postDelayed({ testSpeech() }, 3000)
        } else if ("german" == autotest) {
            // 兼容旧钩子：英文三角色测试，用于验证角色参数
            Handler(Looper.getMainLooper()).postDelayed({ testGerman() }, 3000)
        }
        // 自动应用当前角色配置
        engine!!.applyVoiceProfile(EloquenceEngine.DIALECT_ZH_CN, voiceProfile)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Eloquence TTS"
        title.textSize = 22f
        root.addView(title)

        // 语言环境（融合成一个按钮，按钮文案即当前选择，不带独立标题）
        val langBtnLocal = Button(this)
        langBtn = langBtnLocal
        langBtnLocal.contentDescription = getString(R.string.lang_header) // 无障碍 description
        langBtnLocal.setOnClickListener { showLanguageDialog() }
        root.addView(langBtnLocal)
        refreshLangButton(langBtnLocal)

        // 发音角色入口（纯中文，点选即进）
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

        // 语言检测设置
        val langDetectBtn = Button(this)
        langDetectBtn.text = "语言检测设置"
        val ldLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ldLp.setMargins(0, dp(4), 0, 0)
        langDetectBtn.layoutParams = ldLp
        langDetectBtn.setOnClickListener { showDetectionSettingsDialog() }
        root.addView(langDetectBtn)

        // 语速
        rateVal = addSeekBar(root, getString(R.string.rate), voiceConfig!!.rate, 1, 300) { v ->
            voiceConfig!!.setRate(v)
            rateVal!!.text = getString(R.string.rate_fmt, v)
        }

        // 音调
        pitchVal = addSeekBar(root, getString(R.string.pitch), voiceConfig!!.pitch, 0, 100) { v ->
            voiceConfig!!.setPitch(v)
            pitchVal!!.text = getString(R.string.pitch_fmt, v)
        }

        // 音量
        volumeVal = addSeekBar(root, getString(R.string.volume), voiceConfig!!.volume, 0, 100) { v ->
            voiceConfig!!.setVolume(v)
            volumeVal!!.text = getString(R.string.volume_fmt, v)
        }

        // 音质模式：0=标准（原始音色），1=增强（去嘶声+限幅）；默认标准
        val dspBtnLocal = Button(this)
        dspBtn = dspBtnLocal
        dspBtnLocal.setOnClickListener { showDspDialog() }
        val dspLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dspLp.setMargins(0, dp(4), 0,  ​0)
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
        // label 已经是资源字符串，这里直接拼接 %d%%
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
            Toast.makeText(this, "引擎未初始化", Toast.LENGTH_SHORT).show()
            return
        }
        val preset = voiceProfile?.preset ?: 1
        // 根据当前语言环境选测试文本 + 对应 dialect
        val text: String
        val dialect: Int
        if (voiceConfig!!.isAutoDetect) {
            text = "这是一个语音合成测试。"
            dialect = EloquenceEngine.DIALECT_ZH_CN
        } else {
            val code = voiceConfig!!.voice
            val lang = VoiceConfig.findLang(code)
            text = sampleTextFor(lang.code)
            dialect = bcpToDialect(lang.code)
        }
        // 本构建未链接该语言模块（如 zh-CN）：不能合成，回退英语并说明。
        // 硬性要求：绝不把未链接语言的 dialect 送进引擎（eciNewEx 会因缺少
        // 语言模块走无效 voice 表崩溃，曾导致测试按钮一按即崩）。
        if (!EloquenceEngine.isShippedDialect(dialect)) {
            Toast.makeText(this, "当前语言未包含在本构建中，已用英语试听", Toast.LENGTH_LONG).show()
            val pcmEn = engine!!.synthesizeCore("Hello, this is a speech synthesis test.",
                EloquenceEngine.DIALECT_EN_US, voiceConfig!!.volume, preset,
                voiceConfig!!.pitch, voiceConfig!!.rate)
            if (pcmEn != null && pcmEn.size > 0) {
                playPcm(pcmEn, engine!!.getCoreSampleRate())
                Toast.makeText(this, "已发音（英语）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "合成失败", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val pcm = engine!!.synthesizeCore(text, dialect,
            voiceConfig!!.volume, preset,
            voiceConfig!!.pitch, voiceConfig!!.rate)
        if (pcm != null && pcm.size > 0) {
            playPcm(pcm, engine!!.getCoreSampleRate())
            Toast.makeText(this, "已发音", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "合成失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 各语言的测试文本 */
    private fun sampleTextFor(code: String): String {
        if (code == null) return "Hello, this is a speech test."
        if (code.startsWith("zh")) return "这是一个语音合成测试。"
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

    /** bcp47 → ECI dialect（实测表，见 VoiceConfig.LANGS；未链接语言回退 en-US） */
    private fun bcpToDialect(code: String): Int {
        val d = VoiceConfig.findLang(code).eciDialect
        return if (d != 0L) d.toInt() else EloquenceEngine.DIALECT_EN_US
    }

    private fun testGerman() {
        // 英文角色对比测试：Reed(1) → Sandy(2) → Grandpa(8)
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
                    // 顺序连播（一个播完再播下一个，避免重叠）
                    val pcms = arrayOf(pcm1, pcm2, pcm3)
                    playSequential(pcms, 0, EloquenceEngine.SAMPLE_RATE)
                } else {
                    Toast.makeText(this, "角色测试失败（看日志）", Toast.LENGTH_SHORT).show()
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

    /** 顺序播放多段 PCM（后台线程依次播，每段按时长 sleep 等待播完） */
    private fun playSequential(pcms: Array<ShortArray?>, idx: Int, sampleRate: Int) {
        Thread {
            for (i in idx until pcms.size) {
                val p = pcms[i]
                if (p == null || p.size == 0) continue
                playPcmAndWait(p, sampleRate)
            }
        }.start()
    }

    /** 播放并阻塞等待播完（时长 = samples / rate） */
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
            btn.text = "自动检测"
        } else {
            val d = LanguageDetector.getFixedDialect()
            btn.text = dialectName(d)
        }
    }

    private fun dialectName(dialect: Int): String {
        if (dialect == LanguageDetector.DIALECT_EN_US) return "English (US)"
        if (dialect == LanguageDetector.DIALECT_EN_GB) return "English (UK)"
        if (dialect == LanguageDetector.DIALECT_DE_DE) return "Deutsch"
        if (dialect == LanguageDetector.DIALECT_FR_FR) return "Français"
        if (dialect == LanguageDetector.DIALECT_ES_ES) return "Español"
        if (dialect == LanguageDetector.DIALECT_IT_IT) return "Italiano"
        if (dialect == LanguageDetector.DIALECT_PT_BR) return "Português"
        if (dialect == LanguageDetector.DIALECT_FI_FI) return "Suomi"
        if (dialect == LanguageDetector.DIALECT_ZH_CN) return "中文（简体）"
        if (dialect == LanguageDetector.DIALECT_ZH_TW) return "中文（台湾）"
        if (dialect == LanguageDetector.DIALECT_JA_JP) return "日本語"
        if (dialect == LanguageDetector.DIALECT_KO_KR) return "한국어"
        return "English (US)"
    }

    private fun showLanguageDialog() {
        val items = arrayOf(
            "🔄 自动检测",
            "English (US)",
            "English (UK)",
            "Deutsch",
            "Français",
            "Español",
            "Italiano",
            "Português",
            "Suomi",
            "中文（简体）",
            "中文（台湾）",
            "日本語",
            "한국어",
        )
        val dialects = intArrayOf(
            -1, // auto
            LanguageDetector.DIALECT_EN_US,
            LanguageDetector.DIALECT_EN_GB,
            LanguageDetector.DIALECT_DE_DE,
            LanguageDetector.DIALECT_FR_FR,
            LanguageDetector.DIALECT_ES_ES,
            LanguageDetector.DIALECT_IT_IT,
            LanguageDetector.DIALECT_PT_BR,
            LanguageDetector.DIALECT_FI_FI,
            LanguageDetector.DIALECT_ZH_CN,
            LanguageDetector.DIALECT_ZH_TW,
            LanguageDetector.DIALECT_JA_JP,
            LanguageDetector.DIALECT_KO_KR,
        )
        AlertDialog.Builder(this)
            .setTitle("语言环境")
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
            .setNegativeButton("取消", null)
            .show()
    }

    /** 语言检测设置对话框 */
    private fun showDetectionSettingsDialog() {
        val items = arrayOf(
            "默认语言",
            "检测的语言",
            "中文口音",
            "英文口音",
            "西班牙语口音",
            "法语口音",
        )
        AlertDialog.Builder(this)
            .setTitle("语言检测设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDefaultLanguageDialog()
                    1 -> showDetectionLanguagesDialog()
                    2 -> showChineseDialectDialog()
                    3 -> showEnglishAccentDialog()
                    4 -> showSpanishDialectDialog()
                    5 -> showFrenchDialectDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 默认语言（单选，选择后立即返回）。语言只到"英语/中文"级别，
     *  英美/简繁等口音由口音设置控制，此处不展开。 */
    private fun showDefaultLanguageDialog() {
        val items = arrayOf(
            "不指定",
            "英语",
            "德语",
            "法语",
            "西班牙语",
            "意大利语",
            "葡萄牙语",
            "芬兰语",
            "中文",
            "日语",
            "韩语",
        )
        // values[i] 对应 items[i] 的 dialect（英语/中文用当前口音方言）
        val values = intArrayOf(
            LanguageDetector.DEFAULT_UNSPECIFIED,
            LanguageDetector.getEnglishDialect(),     // 英语（口音设置决定英美）
            LanguageDetector.DIALECT_DE_DE,
            LanguageDetector.getFrenchDialect(),      // 法语（口音设置决定法加法）
            LanguageDetector.getSpanishDialect(),     // 西班牙语（口音设置决定西墨）
            LanguageDetector.DIALECT_IT_IT,
            LanguageDetector.DIALECT_PT_BR,
            LanguageDetector.DIALECT_FI_FI,
            LanguageDetector.getChineseDialect(),     // 中文（口音设置决定简繁）
            LanguageDetector.DIALECT_JA_JP,
            LanguageDetector.DIALECT_KO_KR,
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
            .setTitle("默认语言")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setDefaultLanguage(values[which])
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 检测的语言（多选） */
    private fun showDetectionLanguagesDialog() {
        // 当前白名单；默认只选中英文、中文
        val enabled = LanguageDetector.getEnabledLanguages()
        val checked = BooleanArray(LanguageDetector.ALL_LANG_CODES.size)
        for (i in LanguageDetector.ALL_LANG_CODES.indices) {
            val code = LanguageDetector.ALL_LANG_CODES[i]
            checked[i] = enabled != null && enabled.contains(code)
        }
        val names = LanguageDetector.ALL_LANG_NAMES
        // 实时同步勾选状态到临时数组
        val finalChecked = checked.clone()
        AlertDialog.Builder(this)
            .setTitle("检测的语言")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                finalChecked[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                val newEnabled = HashSet<String>()
                for (i in finalChecked.indices) {
                    if (finalChecked[i]) {
                        newEnabled.add(LanguageDetector.ALL_LANG_CODES[i])
                    }
                }
                // 立即生效（setEnabledLanguages 内部会重建 Lingua）
                LanguageDetector.setEnabledLanguages(newEnabled)
                saveLanguageSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 中文口音 */
    private fun showChineseDialectDialog() {
        val items = arrayOf("简体（zh-CN）", "台湾（zh-TW）")
        val cur = LanguageDetector.getChineseDialect()
        val checked = if (cur == LanguageDetector.DIALECT_ZH_TW) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("中文口音")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setChineseDialect(
                    if (which == 1) LanguageDetector.DIALECT_ZH_TW else LanguageDetector.DIALECT_ZH_CN)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 英文口音 */
    private fun showEnglishAccentDialog() {
        val items = arrayOf("美式英语 (en-US)", "英式英语 (en-GB)")
        val cur = LanguageDetector.getEnglishDialect()
        val checked = if (cur == LanguageDetector.DIALECT_EN_GB) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("英文口音")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setEnglishDialect(
                    if (which == 1) LanguageDetector.DIALECT_EN_GB else LanguageDetector.DIALECT_EN_US)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 西班牙语口音 */
    private fun showSpanishDialectDialog() {
        val items = arrayOf("西班牙西班牙语 (es-ES)", "墨西哥西班牙语 (es-MX)")
        val cur = LanguageDetector.getSpanishDialect()
        val checked = if (cur == LanguageDetector.DIALECT_ES_MX) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("西班牙语口音")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setSpanishDialect(
                    if (which == 1) LanguageDetector.DIALECT_ES_MX else LanguageDetector.DIALECT_ES_ES)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 法语口音 */
    private fun showFrenchDialectDialog() {
        val items = arrayOf("法国法语 (fr-FR)", "加拿大法语 (fr-CA)")
        val cur = LanguageDetector.getFrenchDialect()
        val checked = if (cur == LanguageDetector.DIALECT_FR_CA) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("法语口音")
            .setSingleChoiceItems(items, checked) { d, which ->
                LanguageDetector.setFrenchDialect(
                    if (which == 1) LanguageDetector.DIALECT_FR_CA else LanguageDetector.DIALECT_FR_FR)
                saveLanguageSettings()
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 音质模式：标准（原始音色）或增强（去嘶声+限幅） */
    private fun showDspDialog() {
        val items = arrayOf("标准（原始音色）", "增强（去嘶声）")
        val cur = voiceConfig!!.dspMode
        AlertDialog.Builder(this)
            .setTitle("音质模式")
            .setSingleChoiceItems(items, cur) { d, which ->
                voiceConfig!!.setDspMode(which)
                dspBtn!!.let { refreshDspButton(it) }
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshDspButton(btn: Button) {
        val name = if (voiceConfig!!.dspMode == 1) "增强（去嘶声）" else "标准（原始音色）"
        btn.text = "音质模式：" + name
    }

    // SharedPreferences 持久化
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
        // 检测语言白名单：默认只中英文（首次安装或未设置过）
        val enabled: Set<String>
        if (prefs.contains("enabled_langs")) {
            enabled = prefs.getStringSet("enabled_langs", null)!!
        } else {
            enabled = HashSet(listOf("en", "zh"))
        }
        LanguageDetector.setEnabledLanguages(enabled)
    }

    companion object {
        private const val PREFS_NAME = "vvtts_lang_settings"
    }
}