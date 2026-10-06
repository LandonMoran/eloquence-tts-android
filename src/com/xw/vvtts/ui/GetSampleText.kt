package com.xw.vvtts.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        val voiceName = intent.getStringExtra("voiceName")
        val voice = if (voiceName != null) {
            com.xw.vvtts.engine.VoiceRegistry.voices.firstOrNull { it.voiceName == voiceName }
        } else {
            com.xw.vvtts.engine.VoiceRegistry.find(
                intent.getStringExtra("language") ?: "en",
                intent.getStringExtra("country"))
        }
        // Unsupported locales/variants return no sample; do not mislabel English.
        val supported = voice != null && intent.getStringExtra("variant").isNullOrEmpty()
        if (supported) result.putExtra("sampleText", com.xw.vvtts.engine.VoiceRegistry.sample(voice!!))
        // Android Settings onSampleTextReceived checks LANG_AVAILABLE (0),
        // not Activity.RESULT_OK (-1). Keep the TTS sample result contract.
        setResult(if (supported) android.speech.tts.TextToSpeech.LANG_AVAILABLE
            else android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED, result)
        finish()
    }
}