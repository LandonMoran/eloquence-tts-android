package android.content;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.*;
import android.os.UserManager;
/** Host filesystem/lock fixture. CE and DE are genuinely separate directories. */
public class Context {
    public static File root;
    public static boolean credentialUnavailable;
    public static final Map<String,Set<SharedPreferences.OnSharedPreferenceChangeListener>> listeners = new HashMap<>();
    private final boolean device;
    public Context() { this(false); }
    private Context(boolean device) { this.device=device; }
    public Context getApplicationContext() { return new Context(false); }
    public Context createDeviceProtectedStorageContext() { return new Context(true); }
    public Context createCredentialProtectedStorageContext() { return new Context(false); }
    public File getDataDir() {
        if (!device && credentialUnavailable) throw new IllegalStateException("Credential storage locked");
        return new File(root,device?"de":"ce");
    }
    public File getFilesDir() { File f=new File(getDataDir(),"files"); f.mkdirs(); return f; }
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();
        info.nativeLibraryDir=root.getPath(); return info;
    }
    public File getCacheDir() { File f=new File(getDataDir(),"cache"); f.mkdirs(); return f; }
    public Object getSystemService(Class<?> cls) { return new UserManager(); }
    public SharedPreferences getSharedPreferences(String name,int mode) {
        String key=getDataDir()+"/"+name;
        Set<SharedPreferences.OnSharedPreferenceChangeListener> set=listeners.computeIfAbsent(key,k->new HashSet<>());
        return (SharedPreferences) Proxy.newProxyInstance(Context.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "registerOnSharedPreferenceChangeListener": set.add((SharedPreferences.OnSharedPreferenceChangeListener)args[0]); return null;
                case "unregisterOnSharedPreferenceChangeListener": set.remove(args[0]); return null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy==args[0];
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
    }
}
