package com.xw.vvtts.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        val lang = intent?.getStringExtra("language")
        if (lang != null && lang.lowercase().startsWith("zh")) {
            result.putExtra("sampleText", "你好，这是Eloquence语音合成测试。")
        } else {
            result.putExtra("sampleText", "Hello, this is an Eloquence speech synthesis test.")
        }
        setResult(RESULT_OK, result)
        finish()
    }
}