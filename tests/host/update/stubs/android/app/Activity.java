package android.app;
public class Activity extends android.content.Context {
    public boolean isFinishing() { return false; }
    public boolean isDestroyed() { return false; }
    public void startActivity(android.content.Intent intent) {}
}
