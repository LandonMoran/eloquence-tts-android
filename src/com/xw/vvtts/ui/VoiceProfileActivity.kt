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
import com.xw.vvtts.utils.VoiceProfile

/**
 * Voice profile picker.
 * Tap a voice to select and return.
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


            root.addView(row)
        }

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(root)
        setContentView(scroll)
    }

}