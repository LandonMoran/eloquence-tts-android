package android.widget;
public class Toast {
    public static int shown;
    /** Create a toast fixture without retaining its context or text. */
    public static Toast makeText(android.content.Context c,CharSequence text,int duration) { return new Toast(); }
    /** Create a toast fixture without resolving a string resource. */
    public static Toast makeText(android.content.Context c,int text,int duration) { return new Toast(); }
    /** Count displayed notifications so tests can detect stale UI callbacks. */
    public void show() { shown++; }
}
