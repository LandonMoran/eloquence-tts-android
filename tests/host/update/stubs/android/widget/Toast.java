package android.widget;
public class Toast {
    public static int shown;
    public static Toast makeText(android.content.Context c,CharSequence text,int duration) { return new Toast(); }
    public static Toast makeText(android.content.Context c,int text,int duration) { return new Toast(); }
    public void show() { shown++; }
}
