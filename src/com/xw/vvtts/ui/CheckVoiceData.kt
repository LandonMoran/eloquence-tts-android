package com.xw.vvtts.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        val available = ArrayList<String>()
        // BCP-47 tags; must stay in sync with onGetVoices / onGetDefaultVoiceNameFor.
        available.add("en-US")
        available.add("en-GB")
        available.add("de-DE")
        available.add("fr-FR")
        available.add("fr-CA")
        available.add("es-ES")
        available.add("es-US")
        available.add("es-MX")
        available.add("it-IT")
        available.add("ja-JP")
        available.add("pl-PL")
        available.add("pt-PT")
        available.add("pt-BR")
        available.add("fi-FI")
        available.add("zh-CN")
        result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
        setResult(RESULT_OK, result)
        finish()
    }
}