package app.sapa11y.extension;

/**
 * Defaults for the accessibility patch. The Morphe patch settings UI writes
 * the chosen values into the APK at patch time and those win; the words
 * below only apply to bundles built before the settings existed. Change a
 * word here and rebuild to change the fallback baked into the extension.
 *
 * Orientation, how the game screen turns:
 *   "user"      (default) Follow Android: rotates with the phone when
 *               auto-rotate is on, stays put when the rotation is locked.
 *               A rotation lock to portrait or landscape is always honored.
 *   "landscape" Leave the game exactly as it was: locked to landscape.
 *   "portrait"  Lock the game to portrait, unless the rotation is locked in
 *               Android settings, in which case the lock wins.
 */
public final class SapSettings {
    public static final String ORIENTATION = "user";

    private SapSettings() {}
}
