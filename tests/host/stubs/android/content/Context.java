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
    /** Create a credential-storage context for the host fixture. */
    public Context() { this(false); }
    /** Select credential or device storage for this context instance. */
    private Context(boolean device) { this.device=device; }
    /** Return an application context backed by credential storage. */
    public Context getApplicationContext() { return new Context(false); }
    /** Return a context backed by the fixture's separate device-storage directory. */
    public Context createDeviceProtectedStorageContext() { return new Context(true); }
    /** Return a context backed by the fixture's credential-storage directory. */
    public Context createCredentialProtectedStorageContext() { return new Context(false); }
    /** Resolve the selected storage directory, rejecting credential access when simulated as locked. */
    public File getDataDir() {
        if (!device && credentialUnavailable) throw new IllegalStateException("Credential storage locked");
        return new File(root,device?"de":"ce");
    }
    /** Create and return the files directory within the selected storage area. */
    public File getFilesDir() { File f=new File(getDataDir(),"files"); f.mkdirs(); return f; }
    /** Point the native-library directory at the fixture root for engine initialization. */
    public android.content.pm.ApplicationInfo getApplicationInfo() {
        android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();
        info.nativeLibraryDir=root.getPath(); return info;
    }
    /** Create and return a cache directory within the selected storage area. */
    public File getCacheDir() { File f=new File(getDataDir(),"cache"); f.mkdirs(); return f; }
    /** Return the UserManager fixture used to control simulated unlock state. */
    public Object getSystemService(Class<?> cls) { return new UserManager(); }
    /** Provide a listener-only SharedPreferences proxy keyed by storage area and preference name. */
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
