package com.google.firebase.crashlytics;

/**
 * Host-test stub for com.google.firebase.crashlytics.FirebaseCrashlytics.
 * The real SDK auto-initializes through FirebaseInitProvider and can't run on
 * the JVM host gate. CrashlyticsFx guards every call in try/catch, so this stub
 * only needs to satisfy the source contract while compiling every src file.
 */
public class FirebaseCrashlytics {
    private static final FirebaseCrashlytics INSTANCE = new FirebaseCrashlytics();

    public static FirebaseCrashlytics getInstance() { return INSTANCE; }

    public void setCustomKey(String key, String value) {}
    public void setCustomKey(String key, int value) {}
    public void setCustomKey(String key, boolean value) {}
    public void log(String msg) {}
    public void recordException(Throwable t) {}
}