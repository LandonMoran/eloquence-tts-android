package android.content;
public class Context {
    /** Return a separate application context that does not retain the activity. */
    public Context getApplicationContext() { return new Context(); }
    /** Return host resources supplying the density used by progress UI construction. */
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
    /** Return a deterministic placeholder for a string resource. */
    public String getString(int id) { return "resource "+id; }
    /** Return a resource placeholder without formatting arguments in the lifecycle fixture. */
    public String getString(int id,Object... args) { return "resource "+id; }
}
