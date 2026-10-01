package com.xw.ttsprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * Functional probe for the Eloquence TTS engine on the emulator.
 *
 * Behavior:
 *   speak #1 immediately (unlocked(: auto-detect text mixing English + Chinese,
 *     so the emulator log shows one non-zh segment for \"release five seven\" and one
 *     0x6xxxx zh segment for the Han run.
 *   speak #2 firest ~8s later ( by then the smoke script has locked the device(;
 *     if the lock-screen fix works,the engine synthesizes it with no exceptions.
 *
 * Pass/fail is judged purely from logcat markers ( VvTtsService \"seg dialect=\" lines and
 * absence of SecurityException/FATAL(; the probe itself only emits log markers.
 */
public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    static final String TAG = "TTSProbe";

    private TextToSpeech tts;
    private boolean ready;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Log.i(TAG, "PROBE_START");
        tts = new TextToSpeech(getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "PROBE_INIT_FAIL status=" + status);
            finish();
            return;
        }
        ready = true;
        // Pin the app's default en voice explicitly;auto-detect ( engine-side( still
        // segments by text language. The point is: all-Latin \"release five seven\"
        // must NOT resolve to a 0x6xxxx zh dialect.

        int r = tts.setLanguage(Locale.US);
        Log.i(TAG, "PROBE_LANG en r=" + r);
        speak("release five seven.. 中文测试. This must be English, not Chinese.", "probe1");
        Log.i(TAG, "PROBE_SPEAK1_QUEUED");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "PROBE_SPEAK2_FIRED");
                speak("locked utterance test.", "probe2");
            }
        }, 8000);
        Log.i(TAG, "PROBE_SCHEDULED_SPEAK2_T8S");
    }

    private void speak(String text, String id) {
        try {
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
            Log.i(TAG, "PROBE_SPEAK " + id + " r=" + r);
        } catch (Throwable t) {
            Log.e(TAG, "PROBE_SPEAK_EX " + id + " " + t, t);
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}