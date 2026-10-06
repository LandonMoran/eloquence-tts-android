package android.app;
public class Activity extends android.content.Context {
    /** Report the fixture activity as not finishing. */
    public boolean isFinishing() { return false; }
    /** Report the fixture activity as not destroyed; ownership teardown is exercised explicitly. */
    public boolean isDestroyed() { return false; }
    /** Accept an intent without launching any external activity in host tests. */
    public void startActivity(android.content.Intent intent) {}
}
