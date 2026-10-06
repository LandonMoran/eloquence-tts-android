package android.os;
public class UserManager {
    public static boolean unlocked = true;
    /** Return the fixture's configurable unlock state. */
    public boolean isUserUnlocked() { return unlocked; }
}
