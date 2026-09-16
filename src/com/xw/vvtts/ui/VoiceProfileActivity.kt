package com.xw.vvtts.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.xw.vvtts.utils.KonaVoice
import com.xw.vvtts.utils.VoiceProfile

/**
 * 发音角色选择。
 * 点击角色立即选中并返回；长按进入编辑对话框（自定义捏声）。
 */
class VoiceProfileActivity : Activity() {
    private var voiceProfile: VoiceProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceProfile = VoiceProfile(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        // 顶部大幅留白，确保第一个角色（Reed）不被 ActionBar/状态栏遮挡
        root.setPadding(pad, dp(48), pad, pad)

        val curPreset = voiceProfile!!.preset
        for (i in VoiceProfile.PRESET_NAMES.indices) {
            val idx = i + 1
            val row = TextView(this)
            var label = VoiceProfile.PRESET_NAMES[i]
            if (idx == curPreset) label = "● $label"
            row.text = label
            row.textSize = 20f
            row.setPadding(0, dp(16), 0, dp(16))
            row.isClickable = true
            row.isLongClickable = true

            // 点击：选中并返回
            row.setOnClickListener {
                voiceProfile!!.setPreset(idx)
                Toast.makeText(this, VoiceProfile.PRESET_NAMES[idx - 1], Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }

            // 长按：编辑该角色
            row.setOnLongClickListener {
                showEditDialog(idx)
                true
            }

            root.addView(row)
        }

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)
    }

    /** 编辑对话框：性别单选 + 6 个滑块 + 恢复默认/确定/取消 */
    private fun showEditDialog(preset: Int) {
        val voice = KonaVoice.byPreset(preset)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = dp(16)
        box.setPadding(p, p, p, p)

        // 性别单选
        val genderLabel = TextView(this)
        genderLabel.text = "性别"
        genderLabel.textSize = 14f
        box.addView(genderLabel)

        val genderGroup = RadioGroup(this)
        genderGroup.orientation = RadioGroup.HORIZONTAL
        val curGender = voiceProfile!!.getParam(preset, VoiceProfile.PARAM_GENDER)
        val genders = arrayOf("男声", "女声")
        for (g in 0..1) {
            val rb = RadioButton(this)
            rb.id = g + 1          // 用 1=男, 2=女，避免 id=0 触发 NO_ID 异常
            rb.text = genders[g]
            genderGroup.addView(rb)
        }
        genderGroup.check(curGender + 1) // curGender 0/1 → id 1/2
        box.addView(genderGroup)

        // 6 个滑块参数
        val cur = IntArray(VoiceProfile.EDITABLE_PARAMS.size)
        val bars = arrayOfNulls<SeekBar>(VoiceProfile.EDITABLE_PARAMS.size)
        val vals = arrayOfNulls<TextView>(VoiceProfile.EDITABLE_PARAMS.size)

        for (i in VoiceProfile.EDITABLE_PARAMS.indices) {
            val param = VoiceProfile.EDITABLE_PARAMS[i]
            cur[i] = voiceProfile!!.getParam(preset, param)

            val label = TextView(this)
            label.text = VoiceProfile.paramName(param)
            label.textSize = 13f
            label.setPadding(0, dp(8), 0, 0)
            box.addView(label)

            vals[i] = TextView(this)
            vals[i]!!.text = cur[i].toString()
            vals[i]!!.textSize = 12f
            box.addView(vals[i])

            val bar = SeekBar(this)
            bar.max = paramMax(param)
            bar.progress = cur[i]
            val fi = i
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, progress: Int, fromUser: Boolean) {
                    vals[fi]!!.text = progress.toString()
                }

                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
            })
            bars[i] = bar
            box.addView(bar)
        }

        val dialogScroll = ScrollView(this)
        dialogScroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle("编辑 " + voice.name)
            .setView(dialogScroll)
            .setPositiveButton("确定") { _, _ ->
                // 保存性别：RadioButton id 1/2 → ECI gender 0/1
                var gender = genderGroup.checkedRadioButtonId - 1
                if (gender < 0) gender = 0
                if (gender > 1) gender = 1
                voiceProfile!!.setParam(preset, VoiceProfile.PARAM_GENDER, gender)
                // 保存 6 个滑块
                for (i in VoiceProfile.EDITABLE_PARAMS.indices) {
                    voiceProfile!!.setParam(preset, VoiceProfile.EDITABLE_PARAMS[i], bars[i]!!.progress)
                }
                Toast.makeText(this, "已保存 " + voice.name + " 的自定义参数", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消") { _, _ -> }
            .setNeutralButton("恢复默认") { _, _ ->
                voiceProfile!!.resetPreset(preset)
                Toast.makeText(this, "已恢复 " + voice.name + " 的默认参数", Toast.LENGTH_SHORT).show()
                // 重新打开编辑框显示默认值
                showEditDialog(preset)
            }
            .create()
            .show()
    }

    private fun paramMax(param: Int): Int {
        return when (param) {
            VoiceProfile.PARAM_PITCH_BASE -> 120
            VoiceProfile.PARAM_SPEED -> 250
            else -> 100
        }
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density).toInt()
}