package com.xw.vvtts.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        result.putExtra("available", true)
        result.putExtra("languages", arrayOf("chi"))
        result.putExtra("country", arrayOf(""))
        setResult(RESULT_OK, result)
        finish()
    }
}