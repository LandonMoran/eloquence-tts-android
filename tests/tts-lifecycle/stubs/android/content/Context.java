package android.content;

/** Minimal JVM context; these tests never initialize Android storage or JNI. */
public class Context {
    public Context getApplicationContext() { return this; }
    public Context createDeviceProtectedStorageContext() { return this; }
}
