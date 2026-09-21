package com.xw.vvtts.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.view.View
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
 * Voice profile picker.
 * Tap a voice to select and return;; long-press opens the edit dialog (custom voice tuning).
 */
class VoiceProfileActivity : Activity() {
    private var voiceProfile: VoiceProfile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceProfile = VoiceProfile(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        // Top padding so the first voice (Reed) is not obscured by the ActionBar/status bar
        root.setPadding(pad, dp(48), pad, pad)

        val curPreset = voiceProfile!!.preset
        for (i in VoiceProfile.PRESET_NAMES.indices) {
            val idx = i + 1
            val row = TextView(this)
            var label = VoiceProfile.PRESET_NAMES[i]
            val isCur = idx == curPreset
            row.text = if (isCur) "● $label" else label
            row.textSize = 20f
            row.setPadding(0, dp(16), 0, dp(16))
            row.isClickable = true
            row.isLongClickable = true
            if (isCur) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    row.setStateDescription("selected")
                } else {
                    row.contentDescription = "$label, selected"
                }
            }

            // Tap: select and return
            row.setOnClickListener {
                voiceProfile!!.setPreset(idx)
                Toast.makeText(this, VoiceProfile.PRESET_NAMES[idx - 1], Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }

            // Long-press: edit this voice
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

    /** Edit dialog: gender radio + 6 sliders + reset/OK/cancel */
    private fun showEditDialog(preset: Int) {
        val voice = KonaVoice.byPreset(preset)

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = dp(16)
        box.setPadding(p, p, p, p)

        // Gender radio
        val genderLabel = TextView(this)
        genderLabel.text = "Gender"
        genderLabel.textSize = 14f
        box.addView(genderLabel)

        val genderGroup = RadioGroup(this)
        genderGroup.orientation = RadioGroup.HORIZONTAL
        val curGender = voiceProfile!!.getParam(preset, VoiceProfile.PARAM_GENDER)
        val genders = arrayOf("Male", "Female")
        for (g in 0..1) {
            val rb = RadioButton(this)
            // ids 1=male, 2=female - avoid id=0 (NO_ID crash)
            rb.text = genders[g]
            rb.id = g + 1
            genderGroup.addView(rb)
        }
        genderGroup.check(curGender + 1) // curGender 0/1 → id 1/2
        box.addView(genderGroup)

        // 6 slider params
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
            vals[i]!!.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            val bar = SeekBar(this)
            bar.id = View.generateViewId()
            label.setLabelFor(bar.id)
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
            .setTitle("Edit " + voice.name)
            .setView(dialogScroll)
            .setPositiveButton("OK") { _, _ ->
                // Save gender: RadioButton id 1/2 → ECI gender 0/1
                var gender = genderGroup.checkedRadioButtonId - 1
                if (gender < 0) gender = 0
                if (gender > 1) gender = 1
                voiceProfile!!.setParam(preset, VoiceProfile.PARAM_GENDER, gender)
                // Save the 6 sliders
                for (i in VoiceProfile.EDITABLE_PARAMS.indices) {
                    voiceProfile!!.setParam(preset, VoiceProfile.EDITABLE_PARAMS[i], bars[i]!!.progress)
                }
                Toast.makeText(this, "Saved custom parameters for " + voice.name, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .setNeutralButton("Reset defaults") { _, _ ->
                voiceProfile!!.resetPreset(preset)
                Toast.makeText(this, "Restored default parameters for " + voice.name, Toast.LENGTH_SHORT).show()
                // Reopen the editor showing the defaults
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