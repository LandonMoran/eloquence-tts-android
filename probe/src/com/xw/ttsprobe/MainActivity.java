package com.xw.ttsprobe;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Set;

/**
 * Diagnostics-only probe: binds a NAMED TTS engine (never the system default) and
 * exercises the getVoices() binder round-trip that the Settings app dies on.
 * Emits log markers only; speaks nothing.
 */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    static final String TAG = "TTSProbe";
    static final String ENGINE_PACKAGE = "com.xw.vvttts";

    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "PROBE_START engine=" + ENGINE_PACKAGE);
        try {
            // 3-arg constructor: bind a specific engine WITHOUT changing the default.
            tts = new TextToSpeech(getApplicationContext(), this, ENGINE_PACKAGE);
        } catch (Throwable t) {
            Log.e(TAG, "PROBE_CTOR_EX", t);
            finish();
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "PROBE_INIT_FAIL status=" + status);
            finish();
            return;
        }
        Log.i(TAG, "PROBE_READY");
        try {
            Set<Voice> voices = tts.getVoices();
                        int n = voices == null ? -1 : voices.size();
                        Log.i(TAG, "PROBE_GETVOICES size=" + n);
                        if (voices != null && n > 0) {
                            StringBuilder sb = new StringBuilder();
                            int i = 0;
                            for (Voice v : voices) {
                                if (i >= 3) break;
                                sb.append(i).append(":").append(v.getName()).append("/").append(v.getLocale()).append(";");
                                i++;
                            }
                            Log.i(TAG, "PROBE_GETVOICES sample=" + sb);
                        }
        } catch (Throwable t) {
            Log.e(TAG, "PROBE_GETVOICES_EX - this is the crash we are hunting", t);
        } finally {
            if (tts != null) {
                try { tts.shutdown(); } catch (Throwable ignore) {}
                tts = null;
            }
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignore) {}
            tts = null;
        }
        super.onDestroy();
    }
}