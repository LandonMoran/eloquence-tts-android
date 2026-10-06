package android.content;
public class Intent {
    private final java.util.Map<String,String> extras=new java.util.HashMap<>();
    public Intent putExtra(String key,String value) { extras.put(key,value); return this; }
    public String getStringExtra(String key) { return extras.get(key); }
}
