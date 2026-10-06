package android.content;
public class Intent {
    private final java.util.Map<String,String> extras=new java.util.HashMap<>();
    /** Store a string extra and return this fixture for chained setup. */
    public Intent putExtra(String key,String value) { extras.put(key,value); return this; }
    /** Return a configured string extra or null when absent. */
    public String getStringExtra(String key) { return extras.get(key); }
}
