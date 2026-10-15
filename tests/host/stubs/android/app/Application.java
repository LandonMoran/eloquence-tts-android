package android.app;
/**
 * Host-test stub for android.app.Application.
 * Application.onCreate runs in EVERY process the app spawns (main and the
 * isolated ":tts" engine process), which is the anchor for CrashlyticsFx
 * bootstrapping in secondary processes where the FirebaseInitProvider
 * ContentProvider never fires.
 */
public class Application extends android.content.Context {
    public void onCreate() {}
}