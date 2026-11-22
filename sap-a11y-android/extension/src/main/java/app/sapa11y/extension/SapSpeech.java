package app.sapa11y.extension;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/**
 * Speech, the Android counterpart of the Windows mod's TolkSpeech.
 *
 * On Windows Tolk hands text to NVDA/JAWS when a screen reader is running and
 * falls back to SAPI otherwise. Android's equivalent split needs an overlay
 * view to post announcements (only a View may send them), so this port speaks
 * through the system TTS engine, the SAPI side of that split. That also lets a
 * user pick Eloquence as the engine and get the same voice as on the PC.
 *
 * Interrupt semantics match the PC mod: a move through a menu cuts off the
 * previous line, a continuation queues behind it.
 */
final class SapSpeech {
    private static final String TAG = "SapA11y";

    private TextToSpeech tts;
    private boolean ttsReady;

    SapSpeech(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                try {
                    tts.setLanguage(Locale.getDefault());
                } catch (RuntimeException e) {
                    Log.w(TAG, "TTS language unavailable", e);
                }
            } else {
                Log.w(TAG, "TTS init failed: " + status);
            }
        });
    }

    void speak(String text, boolean interrupt) {
        if (text == null) return;
        String line = text.trim();
        if (line.isEmpty()) return;
        if (!ttsReady || tts == null) return;
        tts.speak(line, interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "sapa11y");
    }

    void stop() {
        if (tts != null && ttsReady) {
            try {
                tts.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    void shutdown() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (RuntimeException ignored) {
            }
            tts = null;
            ttsReady = false;
        }
    }
}
