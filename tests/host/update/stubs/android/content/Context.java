package android.content;
public class Context {
    public Context getApplicationContext() { return new Context(); }
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
    public String getString(int id) { return "resource "+id; }
    public String getString(int id,Object... args) { return "resource "+id; }
}
