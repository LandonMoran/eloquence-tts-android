package android.speech.tts;

import android.content.Context;
import android.content.Intent;

public class TextToSpeechService extends Context {
    public void onDestroy() {}
    public boolean onUnbind(Intent intent) { return false; }
}
