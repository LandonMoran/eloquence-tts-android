package com.xw.vvtts.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        // zh voices are not linked in this build, so always offer the English sample.

        result.putExtra("sampleText", "Hello, this is an Eloquence speech synthesis test.")
        setResult(RESULT_OK, result)
        finish()
    }
}