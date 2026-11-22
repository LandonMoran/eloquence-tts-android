package app.sapa11y.extension;

/**
 * JNI entry points into libsapa11y.so.
 *
 * The native side waits for libil2cpp.so to appear, resolves the il2cpp
 * reflection API, and then answers snapshots of the game's live UGUI controls.
 */
public final class SapNative {
    static {
        System.loadLibrary("sapa11y");
    }

    private SapNative() {}

    public static native boolean nativeReady();

    public static native String nativeSnapshot();

    public static native boolean nativeTap(int id);

    /** True when the tap point is still inside the tapped control's live rect. */
    public static native boolean nativeTapCheck(int id, float cx, float cy);

    /**
     * Hold the native scan off the game graph for a while. Called before a tap
     * that loads a scene: reading live objects while the game loads them is
     * what froze the UI thread and ended in an ANR kill.
     */
    public static native void nativeQuiet(int ms);

    /** Last resort for shop buttons: the game's own HangarMain submit handlers. */
    public static native boolean nativeSubmitFallback(int id, String label);

    public static native boolean nativeSetText(int id, String text);

    public static native boolean nativeFocus(int id);

    public static native int nativeFocused();

    public static native String nativeBackend();

    public static native String nativeStats();

    /** Game state behind the tree: hangar, phase, gold, zones, node count. */
    public static native String nativeProbe();

    /** Grant the game's privacy/terms consent and close its window. */
    public static native boolean nativeGrantConsent();

    /** Click the game's own Roll button (Q key). */
    public static native boolean nativeRoll();

    /** Click the game's own End Turn button (E key). */
    public static native boolean nativeEndTurn();

    /**
     * Click a shop or team slot through the game's own handler for its
     * container (HangarMain.HandleMinionShopClick / HandleSpellShopClick /
     * HandleMinionArmyClick). False when the control is not a slot.
     */
    public static native boolean nativeShopClick(int id);

    /**
     * Whether the food being aimed covers the whole team: 1 yes, 0 no (it
     * targets one pet), -1 when no food is being aimed. A whole-team food is
     * confirmed with nativeUseSpellZone, not with a pet click.
     */
    public static native int nativeSpellFocusAimless();

    /** Confirm a whole-team food through the game's own SpellZone click. */
    public static native boolean nativeUseSpellZone();

    /** Freeze the shop item behind the focused control (Z key). */
    public static native boolean nativeFreeze(int id);

    /** Select the team pet behind the focused control (X key, first half of sell). */
    public static native boolean nativeSelectTeam(int id);

    /** Click the game's own Sell button (X key, deferred second half). */
    public static native boolean nativeClickSell();

    /** Turn timer seconds, or negative when there is no counting timer. */
    public static native int nativeShopTimer();

    /** Pause/Play toggle: 1 paused, 2 resumed, 0 failed (P key). */
    public static native int nativeBattlePause();

    /** Fast forward click: 1 on, 0 off, -1 failed (G key). */
    public static native int nativeBattleFastForward();

    /** Autoplay click: 1 on, 0 off, -1 failed (U key). */
    public static native int nativeBattleAutoplay();

    /** Skip button click (K key). */
    public static native boolean nativeBattleSkip();

    /** Open or close the game's own scoreboard (O key). */
    public static native boolean nativeScoreboardToggle();

    /** Where the native crash breadcrumbs and stage log are written. */
    public static native void nativeSetLogPath(String path);
}
