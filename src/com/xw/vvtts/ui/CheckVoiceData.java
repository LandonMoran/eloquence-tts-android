package com.xw.vvtts.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

public class CheckVoiceData extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent result = new Intent();
        result.putExtra("available", true);
        String[] langs = {"chi", "zho"};
        result.putExtra("languages", new String[]{"chi"});
        result.putExtra("country", new String[]{""});
        
        setResult(RESULT_OK, result);
        finish();
    }
}
