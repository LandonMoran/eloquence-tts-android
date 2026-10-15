package com.google.firebase;
import android.content.Context;
/**
 * Host-test stub for com.google.firebase.FirebaseApp.
 * CrashlyticsFx.initProcess() explicitly initializes the default FirebaseApp in
 * the isolated ":tts" engine process, where the auto-init FirebaseInitProvider
 * ContentProvider never runs. This stub satisfies the source contract on the
 * JVM host gate without real Firebase.
 */
public class FirebaseApp {
    public static java.util.List<FirebaseApp> getApps(Context c) {
        return new java.util.ArrayList<>();
    }
    public static FirebaseApp initializeApp(Context c) { return new FirebaseApp(); }
}