package android.os;
public class Looper {
    /** Return a looper token without starting an Android event loop. */
    public static Looper getMainLooper() { return new Looper(); }
}
