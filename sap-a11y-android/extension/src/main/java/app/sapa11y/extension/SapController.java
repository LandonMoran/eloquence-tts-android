package app.sapa11y.extension;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Android face of the mod, running inside the game process.
 *
 * On Windows the mod owns the keyboard, publishes a UIA tree, moves the game's
 * EventSystem selection itself and speaks through Tolk. This class does the
 * same on Android without an accessibility service:
 *
 * <ul>
 *   <li>it injects a transparent view into the game's own activity window, so
 *       TalkBack reads the control tree with no service to enable;</li>
 *   <li>it wraps the window callback so hardware keys and controllers drive the
 *       mod, and passes everything else through to the game;</li>
 *   <li>with TalkBack off it speaks through the system TTS engine, the Android
 *       counterpart of the mod's SAPI fallback.</li>
 * </ul>
 */
final class SapController {
    private static final String TAG = "SapA11y";
    private static final long POLL_MS = 500;
    // Unity + il2cpp come up well after the activity resumes. The old 3 s
    // fixed wait fired mid-bootstrap, so the first snapshots read
    // half-initialized hierarchies (transient node-count garble) and the
    // overlay published an empty/crashing tree. Now the start is gated on
    // the native bridge itself: no polling, no overlay, no announcements
    // until Unity has a loaded scene and the bridge resolves.
    private static final long START_DELAY_MS = 3000;
    private static final long BOOT_RETRY_MS = 2000;
    private static final int BOOT_MAX_RETRIES = 30;
    private static final float STICK_DEADZONE = 0.6f;

    private static SapController instance;

    static synchronized SapController get(Context context) {
        if (instance == null) instance = new SapController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<SapNode> nodes = new ArrayList<>();

    private SapSpeech speech;
    private SapDictionary dictionary;
    private SapOverlay overlay;
    private AccessibilityManager accessibilityManager;
    private AccessibilityManager.TouchExplorationStateChangeListener touchListener;

    private Activity activity;
    private Activity overlayActivity;
    private Activity callbackActivity;
    private Window.Callback originalCallback;
    private Window.Callback wrapper;
    private boolean running;
    private boolean touchExploration;

    private int focusIndex = -1;
    private String title = "";
    private String lastSignature = "";
    private String lastStructure = "";
    private boolean readyAnnounced;
    private int lastStickDirection;
    private int notReadyPolls;
    private boolean notReadyDiagnosed;
    private boolean consentGranted;
    private int consentAttempts;
    private int grantPoll;
    private boolean labelsReported;
    private int stablePolls;
    private boolean pendingAnnounce;
    private boolean phaseDirty;
    private String shopPhase = "menu";
    private int shopGold = -1;
    private int shopLives = -1;
    private int shopLivesMax = -1;
    private int shopTurn = -1;
    private int shopTier = -1;
    private int shopWins = -1;
    private int shopLosses = -1;
    private int shopFreeRolls = 0;
    private int shopMoustaches = -1;
    // A roll and a sale are announced from the snapshot that shows the result,
    // not from the click that asked for it: the click only proves it was sent.
    private boolean pendingRoll;
    private long pendingRollDue;
    private int pendingSellCheckId;
    private String pendingSellCheckName = "";
    private int pendingSellCheckGold = -1;
    /** MinionModel.SellValue of the pet being sold, or -1 when unreadable. */
    private int pendingSellCheckSell = -1;
    private long pendingSellCheckDue;
    // Controller trigger layers, the PC mod's LT status questions and RT quick
    // actions. Pads report triggers as motion axes, some as keys; both feed these.
    private boolean ltHeld;
    private boolean rtHeld;
    private int lastRzDirection;
    // Unknown-screen capture: the phantom purchase/code dialog never survives
    // to the next launch, so keep the last full trees in memory and dump them
    // to the report file when a suspicious screen appears.
    private final java.util.List<String> recentFullTrees = new java.util.ArrayList<>();
    private final java.util.Set<Integer> lastStableIds = new java.util.HashSet<>();
    private boolean modalActive;
    private boolean consentChecked;
    private String lastDumpedScreen = "";
    // Controller <-> TalkBack focus sync guard: TalkBack focus callbacks must
    // not re-drive the overlay, and controller moves must not loop back.
    private boolean syncingFocus;
    // Deferred sell, the PC mod's _pendingSell: select first, click Sell next poll.
    private int pendingSellId;
    private long pendingSellDue;
    private String pendingSellName = "";
    private int pendingSellSell = -1;
    // Battle narrator state, the PC mod's BattleNarrator polled instead of patched.
    private String battlePlayer = "";
    private String battleEnemy = "";
    private String lastBattleMsg = "";
    private int preBattleLives = -1;
    private String battleOutcome = "";
    // Scoreboard session, the PC mod's ScoreboardNavigator: opponents and pets
    // read from the native scoreboard object, browsable with TalkBack speech.
    private static final class SbOpponent {
        String header = "";
        final java.util.List<String> pets = new java.util.ArrayList<>();
    }
    private boolean sbSession;
    private final java.util.List<SbOpponent> sbOpponents = new java.util.ArrayList<>();
    private int sbOpp, sbPet, sbLine;
    private boolean sbWantOpen;
    private long sbWantDue;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollOnce();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private SapController(Context context) {
        this.context = context;
    }

    // -- lifecycle ---------------------------------------------------------

    /** Called when a game activity is resumed. */
    void attach(Activity resumed) {
        try {
            activity = resumed;
            if (speech == null) speech = new SapSpeech(context);
            if (dictionary == null) dictionary = new SapDictionary(context);
            if (accessibilityManager == null) {
                accessibilityManager =
                        (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (accessibilityManager != null) {
                    touchListener = enabled -> updateTouchExploration();
                    accessibilityManager.addTouchExplorationStateChangeListener(touchListener);
                }
            }
            if (overlay == null || overlayActivity != resumed) {
                // A recreated activity needs a view built with its own context.
                overlay = new SapOverlay(resumed, new SapOverlay.Callbacks() {
                    @Override
                    public void onActivate(int id) {
                        activateId(id);
                    }

                    @Override
                    public void onLongPress(int id) {
                        longPressId(id);
                    }

                    @Override
                    public void onAccessibilityFocus(int id) {
                        onTalkBackFocus(id);
                    }

                    @Override
                    public void onCustomAction(int id, int action) {
                        handleCustomAction(id, action);
                    }

                    @Override
                    public void onSetText(int id, String text) {
                        // TalkBack typing into an edit box: write straight
                        // into the game's input field, no service required.
                        if (!SapNative.nativeSetText(id, text)) {
                            diag("setText failed id=" + id, false);
                        }
                    }
                });
                overlayActivity = resumed;
            }
            installCallback(resumed);
            final Activity target = resumed;
            // Boot-gated start: wait out Unity init, then wait for the
            // native bridge (libil2cpp + domain + first loaded scene).
            // Polling before that returns garbled transitional snapshots.
            handler.postDelayed(new Runnable() {
                int retries = 0;
                @Override
                public void run() {
                    if (activity != target) return;
                    boolean ready = false;
                    try { ready = SapNative.nativeReady(); } catch (Throwable ignored) { }
                    if (!ready && retries < BOOT_MAX_RETRIES) {
                        retries++;
                        if (overlay != null) overlay.setStatus("Super Auto Pets - starting Unity...");
                        handler.postDelayed(this, BOOT_RETRY_MS);
                        return;
                    }
                    updateTouchExploration();
                    if (!running) {
                        running = true;
                        handler.post(poll);
                    }
                    diag("injected into the game window (bridge "
                            + (ready ? "ready" : "not ready after boot wait")
                            + ", mod build " + BUILD_TAG + ")", true);
                }
            }, START_DELAY_MS);
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
            toast("SAP accessibility failed to start: " + t);
        }
    }

    /** Called when that activity is destroyed. */
    void detach(Activity destroyed) {
        if (activity != destroyed) return;
        running = false;
        handler.removeCallbacks(poll);
        detachOverlay();
        activity = null;
        overlayActivity = null;
        callbackActivity = null;
        originalCallback = null;
        wrapper = null;
    }

    private void installCallback(final Activity target) {
        try {
            final Window window = target.getWindow();
            Window.Callback current = window.getCallback();
            if (current == null) return;
            if (wrapper != null && callbackActivity == target) return;
            originalCallback = current;
            callbackActivity = target;
            wrapper = (Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("dispatchKeyEvent".equals(name) && args != null && args.length == 1
                                && args[0] instanceof KeyEvent) {
                            if (handleKey((KeyEvent) args[0])) return true;
                        } else if ("dispatchGenericMotionEvent".equals(name) && args != null
                                && args.length == 1 && args[0] instanceof MotionEvent) {
                            handleMotion((MotionEvent) args[0]);
                        }
                        try {
                            return method.invoke(current, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            window.setCallback(wrapper);
        } catch (Throwable t) {
            Log.w(TAG, "could not wrap window callback", t);
        }
    }

    // -- overlay -----------------------------------------------------------

    private void updateTouchExploration() {
        boolean enabled = accessibilityManager != null && accessibilityManager.isTouchExplorationEnabled();
        touchExploration = enabled;
        // The host view is only injected once the first stable non-empty
        // snapshot arrives (see pollOnce): attaching an empty host during
        // Unity boot makes TalkBack cache a zero-node window and stutter.
        if (enabled) { if (!nodes.isEmpty()) attachOverlay(); }
        else detachOverlay();
        diag("TalkBack " + (enabled ? "on" : "off: speech mode")
                + ", " + nodes.size() + " controls"
                + (enabled && overlay != null && !overlay.isShown() ? " (tree held for boot)" : ""), true);
    }

    private void attachOverlay() {
        try {
            if (activity == null || overlay == null) return;
            View view = overlay.view();
            if (view.getParent() == null) {
                activity.addContentView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                Log.i(TAG, "accessibility view injected");
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not inject the accessibility view", t);
        }
    }

    private void detachOverlay() {
        try {
            if (overlay == null) return;
            View view = overlay.view();
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
        } catch (Throwable ignored) {
            // The window is going away anyway.
        }
    }

    // -- snapshot ----------------------------------------------------------

    private void pollOnce() {
        transitionProbe();
        try {
            String json = SapNative.nativeSnapshot();
            if (!SapNode.ready(json)) {
                if (overlay != null) overlay.setStatus("Super Auto Pets - waiting for the game");
                // The game is building a page. Unity does not consume touches
                // until it is done, so a tap now lands nowhere at all; saying
                // that out loud beats a silent "nothing happens".
                gameLoading = true;
                notReadyPolls++;
                if (notReadyPolls == 10 || (!notReadyDiagnosed && notReadyPolls > 10)) {
                    notReadyDiagnosed = true;
                    String sample = json == null ? "null" : (json.length() <= 300 ? json : json.substring(0, 300));
                    diag("waiting for the game: " + SapNative.nativeBackend() + " jsonLen="
                            + (json == null ? -1 : json.length()) + " sample=" + sample, true);
                } else if (notReadyPolls % 50 == 0) {
                    diag("still waiting for the game: " + SapNative.nativeBackend(), false);
                }
                return;
            }
            notReadyPolls = 0;

            updateShop(json);
            if (gameLoading) {
                gameLoading = false;
                diag("game ready again after loading", false);
                if (pendingLoadingTapId != 0) {
                    // Nothing was sent while the page was building, so send the
                    // tap the user asked for the moment it can land.
                    final int retryId = pendingLoadingTapId;
                    final int retryIndex = pendingLoadingTapIndex;
                    pendingLoadingTapId = 0;
                    handler.post(() -> activateId(retryId, retryIndex, true));
                }
            }
            if (!"shop".equals(shopPhase)) {
                // A hold only means something inside a shop turn.
                heldPetId = -1;
                heldPetExpiry = 0;
            }
            List<SapNode> parsed = describePets(SapNode.parseList(json));
            if (parsed.isEmpty() && overlay != null) {
                overlay.setStatus("Super Auto Pets - bridge ready, no controls found");
            }
            // Don't declare readiness on an empty snapshot: during Unity
            // boot the first ready JSONs are transitional (loading texts
            // only) and announcing then leaves TalkBack on an empty tree.
            if (!readyAnnounced && !parsed.isEmpty()) {
                readyAnnounced = true;
                diag("bridge ready, " + parsed.size() + " controls", true);
                handler.postDelayed(() -> {
                    if (nodes.isEmpty()) diag("scan: " + SapNative.nativeStats(), true);
                }, 4000);
                say(touchExploration
                        ? "Super Auto Pets accessibility ready. Swipe right to walk through the controls, double-tap to press one."
                        : "Super Auto Pets accessibility ready. Press H for help.", true);
            }

            // Controls whose centre is off the display can never be explored,
            // and a tree full of them makes TalkBack give up on the whole
            // window, so they never reach the overlay.
            List<SapNode> visible = onScreen(parsed);
            String nextTitleEarly = SapText.splitCamelCase(SapNode.title(json));
            boolean modalNow = looksModal(nextTitleEarly, visible);
            if (modalNow && !modalActive) {
                diag("modal dialog detected: title='" + nextTitleEarly + "' nodes="
                        + visible.size() + " " + lastModalReason, false);
            }
            modalActive = modalNow;
            // Stable ids track the FULL unfiltered tree, so one filtered poll
            // cannot forget the background shop and make everything look
            // "new" (and therefore modal) on the next poll.
            java.util.List<SapNode> fullVisible = visible;
            if (modalNow) visible = modalFilter(visible);
            StringBuilder signature = new StringBuilder();
            StringBuilder structure = new StringBuilder();
            for (SapNode node : visible) {
                signature.append(node.signature()).append('|');
                structure.append(node.structureSignature()).append('|');
            }
            String nextSignature = signature.toString();
            String nextStructure = structure.toString();

            boolean changed = !nextSignature.equals(lastSignature);
            // Typing changes only the input's value: the control set is the
            // same, so it must not count as a new screen.
            boolean structChanged = !nextStructure.equals(lastStructure);
            if (structChanged) {
                lastStructure = nextStructure;
            }
            if (changed) {
                lastSignature = nextSignature;
                stablePolls = 0;
                if (structChanged) pendingAnnounce = true;
                nodes.clear();
                nodes.addAll(visible);
                if (focusIndex >= nodes.size()) focusIndex = -1;

                String nextTitle = SapText.splitCamelCase(SapNode.title(json));
                if (!nextTitle.equals(title)) {
                    title = nextTitle;
                }
                Log.i(TAG, "snapshot: " + nodes.size() + " nodes, title=" + title
                        + (overlay != null && overlay.isShown() ? ", tree attached" : "")
                        + (modalActive ? ", modal" : ""));
                // What the tree actually exposes, in the report file, so the
                // next run can be diagnosed without adb or a screen reader.
                diag("tree " + (overlay != null && overlay.isShown() ? "attached" : "detached")
                        + (modalActive ? " modal" : "")
                        + ", " + nodes.size() + " nodes: " + nodeSummary(), false);
                // Keep the last full trees so a transient purchase/code dialog
                // can be reconstructed after it vanishes.
                lastStableIds.clear();
                for (SapNode n : fullVisible) lastStableIds.add(n.id);
                recentFullTrees.add(title + "|" + nodeSummary());
                while (recentFullTrees.size() > 3) recentFullTrees.remove(0);
                if (looksUnknown(title, nodes, shopPhase)) {
                    String key = title + "/" + nodes.size() + "/" + lastSignature.hashCode();
                    if (!key.equals(lastDumpedScreen)) {
                        lastDumpedScreen = key;
                        dumpUnknownScreen(modalActive ? "modal+keywords" : "keywords", title, nodes);
                        say("Dialog detected: " + (title.isEmpty() ? "options" : title)
                                + ". Swipe to explore its controls.", true);
                    }
                }
            } else if (stablePolls < 1000) {
                stablePolls++;
            }

            if (!labelsReported && !nodes.isEmpty()) {
                labelsReported = true;
                diag("labels: " + nodeSummary(), true);
            }

            // Hold garbled transitional snapshots back from TalkBack: while
            // the tree is changing every poll (Unity boot, scene swaps) the
            // overlay keeps the last stable tree instead of blasting
            // content-changed events that make TalkBack stutter/restart.
            // The status banner still shows progress via setStatus above.
            boolean stableEnough = !changed || stablePolls >= 2;
            if (touchExploration && overlay != null && !overlay.isShown()
                    && !visible.isEmpty() && stableEnough) {
                attachOverlay();
                diag("tree injected after boot: " + visible.size() + " controls", true);
            }
            // Typing into an edit box changes its snapshot value every
            // keystroke; pushing each one as a tree change knocks TalkBack
            // out of the field. While the focused node is an input and the
            // structure is unchanged, hold the exposed tree still, even when
            // the value churn makes the screen look unstable.
            boolean holdForInput = false;
            if (overlay != null && !nodes.isEmpty()) {
                int fid = overlay.focusedId();
                int fi = indexOfId(fid);
                if (fi >= 0 && "input".equals(nodes.get(fi).role) && !structChanged) {
                    holdForInput = true;
                }
            }
            if (overlay != null && (stableEnough || nodes.isEmpty()) && !holdForInput) {
                overlay.setShop(shopGold, shopPhase);
                overlay.update(nodes, title);
            }

            // TalkBack drops its speech and focus on every announcement, so
            // only narrate once the screen has stopped changing. The tree
            // event already went out with overlay.update above; never re-fire
            // here and never while an input is held.
            if (pendingAnnounce && stablePolls >= 2 && !holdForInput) {
                pendingAnnounce = false;
                if (phaseDirty) {
                    phaseDirty = false;
                    say(phaseLine(), true);
                }
                announceScreen();
            }
            if (!nodes.isEmpty()) syncFocusFromGame();
            // Deferred sell, second half: the game selected the pet on the X
            // press, so a Sell click now finishes it, as the PC mod does.
            if (sbWantOpen && !sbSession && SystemClock.uptimeMillis() >= sbWantDue) {
                sbWantOpen = false;
                say("Scoreboard not available.", true);
            }
            pollCombineWatch();
            pollShopEvents();
            if (pendingSellId != 0 && SystemClock.uptimeMillis() >= pendingSellDue) {
                int id = pendingSellId;
                pendingSellId = 0;
                if (!SapNative.nativeClickSell()) {
                    say("The sell button did not respond.", true);
                } else {
                    // Clicking Sell says nothing about whether the pet left the
                    // board, so the announcement waits for the next snapshot.
                    pendingSellCheckId = id;
                    pendingSellCheckName = pendingSellName;
                    pendingSellCheckGold = shopGold;
                    pendingSellCheckSell = pendingSellSell;
                    pendingSellCheckDue = SystemClock.uptimeMillis() + 1500;
                }
            }
            if (pendingSellCheckId != 0 && SystemClock.uptimeMillis() >= pendingSellCheckDue) {
                int id = pendingSellCheckId;
                String name = pendingSellCheckName;
                int goldBefore = pendingSellCheckGold;
                pendingSellCheckId = 0;
                if (indexOfId(id) < 0) {
                    // The gold the game actually paid wins when the snapshots
                    // show it; otherwise the pet's own SellValue (read from
                    // MinionModel.SellValue through the bridge) is the figure.
                    int back = goldBefore >= 0 && shopGold > goldBefore
                            ? shopGold - goldBefore : pendingSellCheckSell;
                    String refund = back >= 0 ? ", " + back + " gold back" : "";
                    say("Sold " + name + refund + ".", true);
                } else {
                    say(name + " is still on the board. The game did not take the sale.",
                            true);
                }
            }
            if (pendingRoll && SystemClock.uptimeMillis() >= pendingRollDue) {
                pendingRoll = false;
                say("Shop refreshed. " + known(shopGold) + " gold. " + shopSlotSummary(), true);
            }
            // Consent runs last and slowly: the tree must already be populated
            // before anything touches the game, because a call that blocks on
            // the Unity thread must not be allowed to starve the overlay.
            maybeGrantConsent();
        } catch (Throwable t) {
            Log.w(TAG, "poll failed", t);
        }
    }

    /**
     * Save consent through ConsentManager, at most once every ten polls (~5 s),
     * and only until it has worked once. The flags are persisted by the game,
     * so a marker file stops every later launch from rewriting them and from
     * telling the user to restart: that message is what confused players.
     * Only the data write is used: the window's own close path calls live UI
     * objects, which deadlocks when invoked off Unity's thread.
     */
    private void maybeGrantConsent() {
        if (!consentChecked) {
            consentChecked = true;
            if (consentMarker().exists()) {
                consentGranted = true;
                diag("consent already saved on a previous launch; not touching it", true);
                return;
            }
        }
        if (consentGranted || consentAttempts >= 20) return;
        if (++grantPoll < 10) return;
        grantPoll = 0;
        ++consentAttempts;
        try {
            consentGranted = SapNative.nativeGrantConsent();
            diag("consent attempt " + consentAttempts + ": "
                    + (consentGranted ? "saved" : "no manager/data yet"), false);
            if (consentGranted) {
                // Done once, recorded, and never mentioned again: the flags are
                // written, so there is nothing left to flip and nothing the
                // user has to restart.
                try {
                    consentMarker().createNewFile();
                } catch (Throwable ignored) {
                    // The flags are saved either way.
                }
                diag("consent saved; no further attempts", true);
            }
        } catch (Throwable t) {
            diag("consent attempt failed: " + t, false);
        }
    }

    private java.io.File consentMarker() {
        return new java.io.File(activity.getFilesDir(), "sapa11y-consent.txt");
    }

    // -- match transition evidence -----------------------------------------
    // After a play/start/continue control is activated, log what the game
    // state actually is for ten seconds. That separates "no match started"
    // from "match started, scanner found nothing" from "found, never shown".
    private long transitionWatchUntil;
    private int transitionWatchPoll;
    private String transitionWatchLabel = "";

    /**
     * Whether this control may be activated at all. A node that cannot be
     * placed on screen is never tapped — a tap for it lands on something else —
     * and store/payment controls are refused outright during a shop turn.
     */
    private boolean tapTargetUsable(SapNode node, int id) {
        if (node.x <= -5000 || node.y <= -5000) {
            diag("tap refused id=" + id + " label=" + node.label + " (no usable rect)", false);
            say("That control has nowhere on screen. Not tapping it.", true);
            return false;
        }
        if ("shop".equals(shopPhase) && node.zone.isEmpty() && looksLikeStoreControl(node.label)) {
            diag("tap refused id=" + id + " store control '" + node.label + "' in a shop turn",
                    false);
            say("That is the store, not the shop. Not tapping it.", true);
            return false;
        }
        return true;
    }

    /** Store, purchase and payment surfaces, which a shop turn must never reach. */
    private static boolean looksLikeStoreControl(String label) {
        String l = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        return l.contains("purchase") || l.contains("payment") || l.contains("checkout")
                || l.contains("buy ") || l.endsWith("buy") || l.contains("gems")
                || l.contains("subscribe") || l.contains("subscription")
                || l.contains("battle pass") || l.contains("restore") || l.contains("store")
                || l.contains("receipt") || l.contains("redeem");
    }

    private static boolean looksLikePlayControl(String label) {
        String l = label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
        return l.contains("start") || l.contains("play") || l.contains("arena")
                || l.contains("continue") || l.contains("resume") || l.contains("next");
    }

    private void watchTransition(String label) {
        transitionWatchLabel = label;
        transitionWatchUntil = SystemClock.uptimeMillis() + 10000;
        transitionWatchPoll = 0;
        // This control starts a scene load. Keep the scanner off the engine's
        // object graph until the load is over: a cross-thread walk during the
        // load blocked the UI thread and the app was killed for not responding.
        try {
            SapNative.nativeQuiet(TRANSITION_QUIET_MS);
        } catch (Throwable ignored) {
            // An older native library: the scan resumes on its own schedule.
        }
        diag("transition: '" + label + "' activated, watching the game state for 10s", true);
    }

    /** How long a scene-loading tap keeps the native scan paused. */
    private static final int TRANSITION_QUIET_MS = 8000;
    /** Stamped into the log so a copied report says which build it came from. */
    private static final String BUILD_TAG = "2026-11-22-shop-rules";

    private void transitionProbe() {
        if (transitionWatchUntil == 0) return;
        if (SystemClock.uptimeMillis() > transitionWatchUntil) {
            transitionWatchUntil = 0;
            diag("transition: watch ended, tree has " + nodes.size() + " controls", false);
            return;
        }
        if (++transitionWatchPoll % 4 != 1) return;
        String probe;
        try {
            probe = SapNative.nativeProbe();
        } catch (Throwable t) {
            probe = "probe failed: " + t;
        }
        diag("probe after '" + transitionWatchLabel + "': " + probe
                + " tree=" + nodes.size()
                + (overlay != null && overlay.isShown() ? " attached" : " detached"), false);
    }

    private static final String[] MODAL_KEYWORDS = {
        "confirm", "purchase", "verify", "verification", "code", "checkout",
        "are you sure", "dialog", "popup", "consent", "privacy", "terms",
        "buy now", "payment", "receipt", "redeem", "enter code"
    };

    private String lastModalReason = "";

    private boolean looksModal(String title, java.util.List<SapNode> list) {
        String t = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        boolean titleHit = false;
        String titleKw = "";
        if (!t.isEmpty()) {
            for (String k : MODAL_KEYWORDS) {
                if (t.contains(k)) { titleHit = true; titleKw = k; break; }
            }
        }
        int hits = 0;
        boolean hasDismiss = false;
        int focusable = 0;
        int zonedShop = 0;
        for (SapNode n : list) {
            if (!n.role.equals("text")) focusable++;
            if ("petshop".equals(n.zone) || "foodshop".equals(n.zone)
                    || "team".equals(n.zone) || "actions".equals(n.zone)) zonedShop++;
            String l = (n.label + " " + n.value + " " + n.detail).toLowerCase(java.util.Locale.ROOT);
            for (String k : MODAL_KEYWORDS) {
                if (l.contains(k)) { hits++; break; }
            }
            String trim = n.label.trim().toLowerCase(java.util.Locale.ROOT);
            if (trim.equals("confirm") || trim.equals("cancel") || trim.equals("close")
                    || trim.equals("ok") || trim.equals("no") || trim.equals("verify")) hasDismiss = true;
        }
        // The shop and battle trees are large and contain words like "code"
        // only by coincidence; a bare keyword hit once blanked the whole
        // shop. On shop-like screens demand title + body agreement.
        boolean shopLike = "shop".equals(shopPhase) || "battle".equals(shopPhase) || zonedShop >= 3;
        boolean fire;
        String why;
        if (shopLike) {
            fire = titleHit && (hits >= 1 || hasDismiss);
            why = "shopLike titleHit=" + titleHit + (titleHit ? "('" + titleKw + "')" : "")
                    + " hits=" + hits + " dismiss=" + hasDismiss
                    + " focusable=" + focusable + " zoned=" + zonedShop;
        } else {
            fire = (titleHit && (hits >= 1 || hasDismiss))
                    || (hits >= 2 && hasDismiss)
                    || (hits >= 3 && focusable <= 8);
            why = "titleHit=" + titleHit + (titleHit ? "('" + titleKw + "')" : "")
                    + " hits=" + hits + " dismiss=" + hasDismiss
                    + " focusable=" + focusable + " zoned=" + zonedShop;
        }
        if (fire) {
            lastModalReason = why;
            diag("modal fired: " + why + " title='" + title + "' phase=" + shopPhase, false);
        }
        return fire;
    }

    /** When a modal dialog is up, background board controls are unreachable
     * by touch anyway (the dim eats the tap) but TalkBack would still walk
     * them, so tapping "through" the dialog hits the wrong control. Keep
     * only the dialog cluster: nodes new since the stable screen, plus any
     * dismiss/confirm buttons and all static text (the dialog body).
     *
     * <p>Never blank the real tree on a false positive: if the filter would
     * drop every control, or more than half the focusable controls on a
     * shop-like screen, it logs and returns the full list instead. */
    private java.util.List<SapNode> modalFilter(java.util.List<SapNode> visible) {
        java.util.List<SapNode> out = new java.util.ArrayList<>();
        for (SapNode n : visible) {
            if (n.role.equals("text")) { out.add(n); continue; }
            String l = n.label.trim().toLowerCase(java.util.Locale.ROOT);
            boolean dismiss = l.equals("back") || l.equals("close") || l.equals("cancel")
                    || l.equals("confirm") || l.equals("ok") || l.equals("no")
                    || l.equals("verify") || l.equals("submit") || l.equals("accept");
            boolean isNew = !lastStableIds.contains(n.id);
            String full = (n.label + " " + n.detail).toLowerCase(java.util.Locale.ROOT);
            boolean keyword = false;
            for (String k : MODAL_KEYWORDS) if (full.contains(k)) { keyword = true; break; }
            if (dismiss || isNew || keyword) out.add(n);
        }
        int focusBefore = 0;
        for (SapNode n : visible) if (!n.role.equals("text")) focusBefore++;
        int focusAfter = 0;
        for (SapNode n : out) if (!n.role.equals("text")) focusAfter++;
        // If the heuristic would hide everything, the screen was not a modal:
        // fall back to the full list rather than an empty tree.
        if (out.isEmpty() || focusAfter == 0) {
            diag("modal filter suppressed (would blank " + focusBefore
                    + " controls): " + lastModalReason, false);
            return visible;
        }
        // On a real shop/battle tree the dialog cluster is a handful of
        // controls; dropping most of the tree means a false positive.
        if (focusBefore >= 5 && focusAfter * 2 < focusBefore) {
            diag("modal filter suppressed (kept " + focusAfter + "/" + focusBefore
                    + " controls): " + lastModalReason, false);
            return visible;
        }
        diag("modal filter kept " + focusAfter + "/" + focusBefore
                + " controls (" + out.size() + "/" + visible.size() + " nodes): "
                + lastModalReason, false);
        return out;
    }

    private boolean looksUnknown(String title, java.util.List<SapNode> list, String phase) {
        String t = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("purchas") || t.contains("checkout") || t.contains("verify")
                || t.contains("code") || t.contains("receipt")) return true;
        for (SapNode n : list) {
            if (n.role.equals("input")) {
                // An edit box outside account/setup screens is the code/email field.
                if ("menu".equals(phase) || modalActive) return true;
            }
            String l = (n.label + " " + n.value).toLowerCase(java.util.Locale.ROOT);
            if (l.contains("enter code") || l.contains("verification") || l.contains("receipt")
                    || l.contains("purchase failed") || l.contains("payment")) return true;
        }
        return false;
    }

    /** Full-tree dump for the report file: every node with role/label/zone/box,
     * plus input values (the code field), title, phase, gold and focus. */
    private void dumpUnknownScreen(String reason, String title, java.util.List<SapNode> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("unknown-screen (").append(reason).append(") title='").append(title)
          .append("' phase=").append(shopPhase).append(" gold=").append(shopGold)
          .append(" nodes=").append(list.size()).append('\n');
        for (int i = 0; i < list.size(); i++) {
            SapNode n = list.get(i);
            sb.append("  #").append(i).append(" id=").append(n.id).append(' ').append(n.role)
              .append(" zone=").append(n.zone.isEmpty() ? "-" : n.zone)
              .append(" label='").append(n.label).append("' value='").append(n.value)
              .append("' detail='").append(n.detail).append("' @")
              .append(Math.round(n.x)).append(',').append(Math.round(n.y))
              .append(' ').append(Math.round(n.width)).append('x').append(Math.round(n.height))
              .append(n.enabled ? "" : " DISABLED").append('\n');
            if (sb.length() > 6000) { sb.append("  ...truncated\n"); break; }
        }
        diag(sb.toString(), false);
    }

    /** Keep only controls with sane on-display coordinates. The native layer
     * marks unprojectable points with -10000; those are dropped here. Never
     * clamp a whole off-screen list into the tree: dozens of overlapping
     * minimum-size nodes at one corner crash TalkBack and read as zero. */
    private List<SapNode> onScreen(List<SapNode> in) {
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;
        List<SapNode> out = new ArrayList<>(in.size());
        int invalid = 0;
        int tinyText = 0;
        int offscreen = 0;
        // Named in the report: a screen whose Confirm/input is missing is
        // otherwise indistinguishable from one that has none.
        StringBuilder dropped = new StringBuilder();
        boolean hasControl = false;
        for (SapNode n : in) { if (!n.role.equals("text")) { hasControl = true; break; } }
        for (SapNode n : in) {
            if (n.x <= -5000 || n.y <= -5000) { invalid++; continue; }
            if (hasControl && n.role.equals("text") && n.width <= 1 && n.height <= 1) { tinyText++; continue; }
            if (n.x >= -50 && n.x <= sw + 50 && n.y >= -50 && n.y <= sh + 50) out.add(n);
            else {
                offscreen++;
                if (dropped.length() < 160) {
                    if (dropped.length() > 0) dropped.append(", ");
                    dropped.append(n.label.isEmpty() ? n.role : n.label)
                            .append('@').append(Math.round(n.x)).append(',').append(Math.round(n.y));
                }
            }
        }
        if (invalid > 0 || tinyText > 0 || offscreen > 0 || out.isEmpty()) {
            diag("coords: " + out.size() + "/" + in.size() + " on screen " + sw + "x" + sh
                    + " invalid=" + invalid + " tinyText=" + tinyText + " offscreen=" + offscreen
                    + (dropped.length() > 0 ? " dropped=" + dropped : ""), out.isEmpty());
        }
        return out;
    }

    /** True for a card line carrying a pet's stats. */
    private static boolean isPetLine(String detail) {
        return detail.contains(" attack, ") && detail.contains(" health");
    }

    /** False when the pet line stops after the tier: no ability was resolved. */
    private static boolean hasAbility(String detail) {
        int tier = detail.toLowerCase(java.util.Locale.ROOT).indexOf("tier ");
        return tier < 0 || detail.indexOf('.', tier) >= 0;
    }

    private static String petName(String detail) {
        int comma = detail.indexOf(',');
        return comma > 0 ? detail.substring(0, comma).trim() : detail.trim();
    }

    /**
     * Fill in a pet whose localized ability text did not resolve. The card
     * line already carries name, level, stats, tier and tribe; the bundled
     * wiki description supplies the missing ability sentence, so a pet is
     * never read as a bare stat line.
     */
    private List<SapNode> describePets(List<SapNode> in) {
        if (in.isEmpty()) return in;
        List<SapNode> out = new ArrayList<>(in.size());
        for (SapNode n : in) {
            if (n.detail.isEmpty() || !isPetLine(n.detail) || hasAbility(n.detail)) {
                out.add(n);
                continue;
            }
            String wiki = SapDictionary.descriptionFor(context, petName(n.detail));
            if (wiki != null) {
                // The wiki line opens with its own "Tier N." and the card has
                // already said the tier, so only the rest is added.
                wiki = wiki.replaceFirst("(?i)^tier\\s*\\d+\\.\\s*", "").trim();
            }
            out.add(wiki == null || wiki.isEmpty() ? n : n.withDetail(". " + wiki));
        }
        return out;
    }

    /** Shop and run state from the snapshot; the PC mod's phase detector, read-only. */
    private void updateShop(String json) {
        String phase = "menu";
        int gold = -1, lives = -1, livesMax = -1, turn = -1, tier = -1, wins = -1, losses = -1;
        int freeRolls = 0, moustaches = -1;
        String bp = "", be = "";
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            org.json.JSONObject shop = root.optJSONObject("shop");
            if (shop != null) {
                phase = shop.optString("phase", "menu");
                gold = shop.optInt("gold", -1);
                lives = shop.optInt("lives", -1);
                livesMax = shop.optInt("livesMax", -1);
                turn = shop.optInt("turn", -1);
                tier = shop.optInt("tier", -1);
                wins = shop.optInt("wins", -1);
                losses = shop.optInt("losses", -1);
                freeRolls = shop.optInt("freeRolls", 0);
                moustaches = shop.optInt("moustaches", -1);
            }
            org.json.JSONObject battle = root.optJSONObject("battle");
            if (battle != null) {
                bp = battle.optString("player", "");
                be = battle.optString("enemy", "");
            }
            org.json.JSONObject board = root.optJSONObject("scoreboard");
            boolean sbOpenNow = board != null && board.optBoolean("open", false);
            sbOpponents.clear();
            if (board != null) {
                org.json.JSONArray opps = board.optJSONArray("opponents");
                if (opps != null) {
                    for (int i = 0; i < opps.length(); i++) {
                        org.json.JSONObject o = opps.optJSONObject(i);
                        if (o == null) continue;
                        SbOpponent opp = new SbOpponent();
                        opp.header = o.optString("header", "");
                        org.json.JSONArray pets = o.optJSONArray("pets");
                        if (pets != null) {
                            for (int j = 0; j < pets.length(); j++) {
                                opp.pets.add(pets.optString(j, ""));
                            }
                        }
                        sbOpponents.add(opp);
                    }
                }
            }
            if (sbOpenNow && !sbSession) openScoreboardSession();
            else if (!sbOpenNow && sbSession) closeScoreboardSession(true);
            else if (sbSession) clampScoreboard();
        } catch (org.json.JSONException ignored) {
        }
        String oldPhase = shopPhase;
        int oldLives = shopLives;
        shopGold = gold;
        shopLives = lives;
        shopLivesMax = livesMax;
        shopTurn = turn;
        shopTier = tier;
        shopWins = wins;
        shopLosses = losses;
        shopFreeRolls = freeRolls;
        shopMoustaches = moustaches;
        if ("battle".equals(phase)) {
            if (!"battle".equals(oldPhase)) {
                preBattleLives = oldLives;
                lastBattleMsg = "";
            }
            diffBattle(battlePlayer, bp, battleEnemy, be);
            battlePlayer = bp;
            battleEnemy = be;
        } else {
            battlePlayer = "";
            battleEnemy = "";
            if ("battle".equals(oldPhase) && "shop".equals(phase)) {
                if (preBattleLives >= 0 && lives >= 0 && lives < preBattleLives) {
                    battleOutcome = "Battle lost.";
                } else {
                    battleOutcome = "Battle won!";
                }
            }
        }
        if (!phase.equals(shopPhase)) {
            shopPhase = phase;
            phaseDirty = true;
            pendingAnnounce = true;
            diag("phase: " + phaseLine(), true);
        }
    }

    private String known(int v) {
        return v >= 0 ? String.valueOf(v) : "unknown";
    }

    private String phaseLine() {
        if (shopPhase.equals("shop")) {
            StringBuilder sb = new StringBuilder();
            if (!battleOutcome.isEmpty()) {
                sb.append(battleOutcome).append(' ');
                battleOutcome = "";
            }
            sb.append("Shop.");
            if (shopTurn >= 0) sb.append(" Turn ").append(shopTurn).append('.');
            if (shopTier >= 0) sb.append(" Tier ").append(shopTier).append('.');
            if (shopGold >= 0) sb.append(' ').append(shopGold).append(" gold.");
            return sb.toString();
        }
        if (shopPhase.equals("battle")) {
            StringBuilder sb = new StringBuilder("Battle started.");
            String mine = shortNames(battlePlayer);
            String foe = shortNames(battleEnemy);
            if (!mine.isEmpty()) sb.append(" Your team: ").append(mine).append('.');
            if (!foe.isEmpty()) sb.append(" Enemy team: ").append(foe).append('.');
            sb.append(" Keys: P pause, G fast forward, U autoplay, K skip, Space repeats the last event.");
            return sb.toString();
        }
        return "";
    }

    /** Team line without the " 3/2" stats, for the battle-start announcement. */
    private String shortNames(String line) {
        return BoardView.names(line);
    }

    // The battle-line parsing is in BoardView so it can be tested without a
    // device; these keep the narrator's call sites readable.
    private String entryName(String entry) {
        return BoardView.name(entry);
    }

    private int entryHealth(String entry) {
        return BoardView.health(entry);
    }

    private int entryAttack(String entry) {
        return BoardView.attack(entry);
    }

    private String[] splitEntries(String line) {
        return BoardView.entries(line);
    }

    /** True when the slot took damage or fainted between snapshots. */
    private boolean slotHurt(String[] p, String[] c, int i) {
        return BoardView.slotHurt(p, c, i);
    }

    /**
     * Poll-diff narrator, the Android stand-in for the PC mod's event-driven
     * BattleNarrator. Super Auto Pets trades front to front with the player
     * striking first, so front-slot damage also yields the PC mod's
     * "X attacks Y" line; back-line damage stays attacker-free, as does
     * ability damage on PC. Faint and summon lines follow, per side.
     */
    private void diffBattle(String prevP, String curP, String prevE, String curE) {
        String[] pp = splitEntries(prevP);
        String[] cp = splitEntries(curP);
        String[] pe = splitEntries(prevE);
        String[] ce = splitEntries(curE);
        String pFront = entryName(cp.length > 0 ? cp[0] : (pp.length > 0 ? pp[0] : ""));
        String eFront = entryName(ce.length > 0 ? ce[0] : (pe.length > 0 ? pe[0] : ""));
        boolean eFrontHurt = slotHurt(pe, ce, 0);
        boolean pFrontHurt = slotHurt(pp, cp, 0);
        if (eFrontHurt && !pFront.isEmpty() && !eFront.isEmpty()) {
            battleSay("Your " + pFront + " attacks enemy " + eFront + ".");
        }
        if (pFrontHurt && !pFront.isEmpty() && !eFront.isEmpty()) {
            battleSay("Enemy " + eFront + " attacks your " + pFront + ".");
        }
        diffBattleTeams(prevP, curP, "Your ");
        diffBattleTeams(prevE, curE, "Enemy ");
    }

    private void diffBattleTeams(String prev, String cur, String owner) {
        if (cur == null) cur = "";
        if (prev == null) prev = "";
        if (prev.equals(cur)) return;
        String[] p = prev.isEmpty() ? new String[0] : prev.split(",");
        String[] c = cur.isEmpty() ? new String[0] : cur.split(",");
        int n = Math.max(p.length, c.length);
        for (int i = 0; i < n; i++) {
            String ps = i < p.length ? p[i] : "";
            String cs = i < c.length ? c[i] : "";
            if (ps.equals(cs)) continue;
            if (ps.isEmpty()) {
                // Summoned mid-battle (Bee, Zombie Cricket, etc.): name the slot.
                if (i == 0) battleSay(owner + entryName(cs) + " enters the front!");
                else battleSay(owner + entryName(cs) + " is summoned in slot " + (i + 1) + ".");
            } else if (cs.isEmpty()) {
                battleSay(owner + entryName(ps) + " fainted!");
            } else if (!entryName(ps).equals(entryName(cs))) {
                battleSay(owner + entryName(ps) + " fainted! " + owner + entryName(cs)
                        + " joined the battle.");
            } else {
                int bH = entryHealth(ps);
                int aH = entryHealth(cs);
                int bA = entryAttack(ps);
                int aA = entryAttack(cs);
                boolean buffed = (aH > bH && bH >= 0) || (aA > bA && bA >= 0);
                boolean hurt = aH >= 0 && bH >= 0 && aH < bH;
                if (buffed && !hurt) {
                    StringBuilder gain = new StringBuilder(owner + entryName(cs) + " is buffed: ");
                    boolean needComma = false;
                    if (aA > bA && bA >= 0) {
                        gain.append("+").append(aA - bA).append(" attack");
                        needComma = true;
                    }
                    if (aH > bH && bH >= 0) {
                        if (needComma) gain.append(", ");
                        gain.append("+").append(aH - bH).append(" health");
                    }
                    gain.append(".");
                    battleSay(gain.toString());
                } else if (hurt) {
                    int dmg = bH - aH;
                    // Back-line damage with an untouched front is ability/snipe
                    // damage on PC, never a front-trade attack line.
                    if (i > 0) battleSay(owner + entryName(cs) + " takes " + dmg
                            + " snipe damage. " + bH + " to " + aH + " health.");
                    else battleSay(owner + entryName(cs) + " took " + dmg + " damage.");
                } else if (aA != bA && aA >= 0 && bA >= 0) {
                    battleSay(owner + entryName(cs) + " attack " + bA + " to " + aA + ".");
                }
            }
        }
    }

    private void battleSay(String text) {
        lastBattleMsg = text;
        say(text, false);
        diag("battle: " + text, false);
    }

    private void announceGold() {
        say(shopGold >= 0 ? shopGold + " gold." : "Gold unknown.", true);
    }

    private void announceLives() {
        if (shopLives >= 0) {
            say(shopLives + " out of " + known(shopLivesMax) + " lives.", true);
        } else {
            say("Lives unknown.", true);
        }
    }

    private void announceTurn() {
        if (shopTurn >= 0 || shopTier >= 0) {
            say("Turn " + known(shopTurn) + ", tier " + known(shopTier) + ".", true);
        } else {
            say("Turn unknown.", true);
        }
    }

    private void announceWins() {
        if (shopWins >= 0 || shopLosses >= 0) {
            say(known(shopWins) + " wins, " + known(shopLosses) + " losses.", true);
        } else {
            say("Wins unknown.", true);
        }
    }

    /** Jump TalkBack/gamepad focus to the first control of a shop zone (PC mod S/F/T/D/V). */
    private void jumpToZone(String zone, String spoken) {
        for (int i = 0; i < nodes.size(); i++) {
            if (isFocusable(i) && zone.equals(nodes.get(i).zone)) {
                focusIndex = i;
                SapNative.nativeFocus(nodes.get(i).id);
                if (touchExploration) {
                    // TalkBack speaks the newly focused node itself, zone prefix
                    // included, so no extra announcement on top of it.
                    if (overlay != null) overlay.requestFocus(i);
                } else {
                    say(spoken + ". " + describe(nodes.get(i)), true);
                }
                return;
            }
        }
        say(spoken + ". Nothing there yet.", true);
    }

    private static final String[] ZONE_CYCLE = {"petshop", "foodshop", "team", "actions"};

    /** LB/RB: the PC mod's bumper zone cycle through pet shop, food, team, actions. */
    private void cycleZone(int delta) {
        String current = "";
        if (focusIndex >= 0 && focusIndex < nodes.size()) current = nodes.get(focusIndex).zone;
        int at = -1;
        for (int i = 0; i < ZONE_CYCLE.length; i++) {
            if (ZONE_CYCLE[i].equals(current)) {
                at = i;
                break;
            }
        }
        if (at < 0) at = delta > 0 ? -1 : 0;
        int next = Math.floorMod(at + delta, ZONE_CYCLE.length);
        jumpToZone(ZONE_CYCLE[next], SapNode.zoneName(ZONE_CYCLE[next]));
    }

    /** R in the shop: the PC mod's ReadBoardStatus in one line. */
    private String boardStatus() {
        return ShopView.statusLine(shopTurn, shopGold, shopFreeRolls, shopTier, shopLives,
                shopLivesMax, shopWins, shopLosses, shopMoustaches);
    }

    private void announceBoard() {
        String status = boardStatus();
        say(status.isEmpty() ? "Shop status unknown." : status + ".", true);
    }

    /** B key: the lobby's snacks counter, digits-slash-digits like the PC mod scans. */
    private void announceSnacks() {
        for (SapNode node : nodes) {
            String text = node.label.trim();
            if (text.matches("^\\d+\\s*/\\s*\\d+$")) {
                String[] parts = text.split("/");
                say("Snacks: " + parts[0].trim() + " of " + parts[1].trim() + ".", true);
                return;
            }
        }
        say("Snacks counter not found.", true);
    }

    /** C key: turn timer, the PC mod's ReadTurnTimer. */
    private void announceTimer() {
        if (!"shop".equals(shopPhase)) {
            say("Timer not available outside shop phase.", true);
            return;
        }
        int secs = SapNative.nativeShopTimer();
        if (secs == -101) {
            say("No active timer.", true);
        } else if (secs <= 0) {
            say(secs == -100 ? "Timer not available." : "Timer expired.", true);
        } else {
            int minutes = secs / 60;
            int seconds = secs % 60;
            if (minutes > 0) {
                say(minutes + (minutes == 1 ? " minute " : " minutes ") + seconds
                        + (seconds == 1 ? " second remaining." : " seconds remaining."), true);
            } else {
                say(seconds + (seconds == 1 ? " second remaining." : " seconds remaining."), true);
            }
        }
    }

    private void rollPressed() {
        if (!"shop".equals(shopPhase)) {
            say("Cannot roll now.", true);
            return;
        }
        boolean free = shopFreeRolls > 0;
        if (!ShopRules.canRoll(shopGold, shopFreeRolls)) {
            // The game would ignore the click and nothing would be said.
            say("Rolling costs one gold and you have none.", true);
            return;
        }
        if (!SapNative.nativeRoll()) {
            say("Cannot roll now.", true);
            return;
        }
        // The gold in this snapshot is the gold before the roll, so the result
        // is announced from the snapshot that shows it spent.
        pendingRoll = true;
        pendingRollDue = SystemClock.uptimeMillis() + 2000;
        say(free ? "Rolled with a free roll." : "Rolled.", true);
    }

    private void freezePressed() {
        if (focusIndex < 0 || focusIndex >= nodes.size()) {
            say("Select a shop item to freeze.", true);
            return;
        }
        SapNode node = nodes.get(focusIndex);
        if (!node.zone.isEmpty() && !"petshop".equals(node.zone)
                && !"foodshop".equals(node.zone)) {
            say("Only shop items can be frozen. " + spokenName(node)
                    + " is on the board.", true);
            return;
        }
        // FreezeItem toggles: a frozen card is unfrozen, which is why the
        // announcement follows ItemModel.Frozen before the click rather than
        // always saying "Froze".
        boolean wasFrozen = node.frozen == 1;
        if (SapNative.nativeFreeze(node.id)) {
            say((wasFrozen ? "Unfroze " : "Froze ") + spokenName(node) + ".", true);
            return;
        }
        // The game refuses to freeze an empty slot, and saying "select an item"
        // asked the user for something they had already selected.
        if (ShopView.isEmptySlot(node)) {
            say("Nothing to freeze. That shop slot is empty.", true);
        } else {
            say("Cannot freeze " + spokenName(node) + " now.", true);
        }
    }

    /** How a node is named when only one clause can be spoken. */
    private static String spokenName(SapNode node) {
        if (node == null) return "that item";
        if (!node.label.isEmpty()) return node.label;
        if (!node.detail.isEmpty()) {
            String first = node.detail.split("[,.]")[0].trim();
            if (!first.isEmpty()) return first;
        }
        return "that item";
    }

    /** "Slot 1: Otter. Slot 2: empty." for the shop cards in reading order. */
    private String shopSlotSummary() {
        String summary = ShopView.summary(nodes, "petshop");
        return summary.isEmpty() ? "The shop is empty." : summary;
    }

    private void endTurnPressed() {
        if (!"shop".equals(shopPhase)) {
            say("Cannot end turn now.", true);
            return;
        }
        if (SapNative.nativeEndTurn()) {
            say("Turn ended.", true);
            return;
        }
        // Some modes hide the Done holder under a different name; fall back
        // to the visible end-turn button, if the tree has exactly its label.
        for (int i = 0; i < nodes.size(); i++) {
            SapNode n = nodes.get(i);
            if (!n.enabled || n.role.equals("text")) continue;
            String l = n.label.trim();
            if (l.equalsIgnoreCase("End turn") || l.equalsIgnoreCase("Done")
                    || l.equalsIgnoreCase("End")
                    || l.equalsIgnoreCase("Battle") || l.equalsIgnoreCase("Battle!")
                    || l.equalsIgnoreCase("Fight") || l.equalsIgnoreCase("Fight!")
                    || l.equalsIgnoreCase("Start battle")) {
                focusIndex = i;
                SapNative.nativeFocus(n.id);
                if (tapOrInject(n.id, i)) {
                    say("Turn ended.", true);
                    return;
                }
                break;
            }
        }
        // File-only: the next report then names the actual arena screen.
        diag("endTurn failed, " + nodes.size() + " nodes: " + nodeSummary(), false);
        say("Cannot end turn now.", true);
    }

    private void pausePressed() {
        int r = SapNative.nativeBattlePause();
        if (r == 1) say("Battle paused.", true);
        else if (r == 2) say("Battle resumed.", true);
        else say("Cannot pause now.", true);
    }

    private void fastForwardPressed() {
        int r = SapNative.nativeBattleFastForward();
        if (r == 1) say("Fast forward on.", true);
        else if (r == 0) say("Fast forward off.", true);
        else say("Cannot fast forward now.", true);
    }

    private void autoplayPressed() {
        int r = SapNative.nativeBattleAutoplay();
        if (r == 1) say("Autoplay toggled.", true);
        else say("Cannot change autoplay now.", true);
    }

    private void skipPressed() {
        if (SapNative.nativeBattleSkip()) say("Skipping battle.", true);
        else say("Cannot skip now.", true);
    }

    private void readLastBattle() {
        if (!lastBattleMsg.isEmpty()) say(lastBattleMsg, true);
        else say("No battle events yet.", true);
    }

    /** O key: open the scoreboard, or close the session when it is showing. */
    private void toggleScoreboard() {
        if (sbSession) {
            SapNative.nativeScoreboardToggle();
            closeScoreboardSession(true);
            return;
        }
        if (SapNative.nativeScoreboardToggle()) {
            sbWantOpen = true;
            sbWantDue = SystemClock.uptimeMillis() + 3000;
        } else {
            say("Scoreboard not available.", true);
        }
    }

    private void openScoreboardSession() {
        sbSession = true;
        sbWantOpen = false;
        sbOpp = 0;
        sbPet = 0;
        sbLine = 0;
        if (sbOpponents.isEmpty()) {
            say("Scoreboard. No opponents yet.", true);
            return;
        }
        say("Scoreboard. " + sbOpponents.size() + (sbOpponents.size() == 1 ? " opponent. " : " opponents. ")
                + describeScoreboard(), true);
    }

    private void closeScoreboardSession(boolean announced) {
        sbSession = false;
        sbWantOpen = false;
        if (announced) say("Scoreboard closed.", true);
    }

    private void clampScoreboard() {
        if (sbOpponents.isEmpty()) {
            sbOpp = 0;
            sbPet = 0;
            sbLine = 0;
            return;
        }
        sbOpp = Math.max(0, Math.min(sbOpp, sbOpponents.size() - 1));
        int pets = sbOpponents.get(sbOpp).pets.size();
        sbPet = pets == 0 ? 0 : Math.max(0, Math.min(sbPet, pets - 1));
        sbLine = Math.max(0, Math.min(sbLine, sbPetLines().size() - 1));
    }

    private java.util.List<String> sbPetLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (sbOpp < 0 || sbOpp >= sbOpponents.size()) return lines;
        java.util.List<String> pets = sbOpponents.get(sbOpp).pets;
        if (sbPet < 0 || sbPet >= pets.size()) return lines;
        for (String part : pets.get(sbPet).split("\\. ")) {
            if (!part.isEmpty()) lines.add(part);
        }
        if (lines.isEmpty()) lines.add(pets.get(sbPet));
        return lines;
    }

    private String describeScoreboard() {
        if (sbOpponents.isEmpty()) return "No opponents.";
        SbOpponent opp = sbOpponents.get(sbOpp);
        StringBuilder sb = new StringBuilder();
        sb.append("Opponent ").append(sbOpp + 1).append(" of ").append(sbOpponents.size());
        if (!opp.header.isEmpty()) sb.append(": ").append(opp.header);
        if (!opp.pets.isEmpty()) {
            java.util.List<String> lines = sbPetLines();
            sb.append(". Pet ").append(sbPet + 1).append(" of ").append(opp.pets.size());
            if (!lines.isEmpty()) {
                sb.append(", line ").append(sbLine + 1).append(" of ").append(lines.size());
                sb.append(": ").append(lines.get(sbLine));
            }
        } else {
            sb.append(". No pets shown.");
        }
        return sb.toString();
    }

    private void speakScoreboardAll() {
        if (sbOpponents.isEmpty()) {
            say("Scoreboard. No opponents.", true);
            return;
        }
        SbOpponent opp = sbOpponents.get(sbOpp);
        StringBuilder sb = new StringBuilder();
        if (!opp.header.isEmpty()) sb.append(opp.header).append(". ");
        for (int i = 0; i < opp.pets.size(); i++) {
            sb.append("Pet ").append(i + 1).append(": ").append(opp.pets.get(i)).append(". ");
        }
        say(sb.toString(), true);
    }

    /**
     * The PC mod's scoreboard keys: Tab between opponents, arrows between
     * pets and through detail lines, Enter reads all, Escape closes.
     */
    private boolean handleScoreboardKey(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() != KeyEvent.ACTION_UP;
        if (!down) return true;
        switch (key) {
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                sbOpp = sbOpponents.isEmpty() ? 0 : (sbOpp + 1) % sbOpponents.size();
                sbPet = 0;
                sbLine = 0;
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                if (!sbOpponents.isEmpty()) {
                    sbOpp = (sbOpp - 1 + sbOpponents.size()) % sbOpponents.size();
                    sbPet = 0;
                    sbLine = 0;
                }
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (!sbOpponents.isEmpty() && !sbOpponents.get(sbOpp).pets.isEmpty()) {
                    int n = sbOpponents.get(sbOpp).pets.size();
                    sbPet = (sbPet - 1 + n) % n;
                    sbLine = 0;
                }
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (!sbOpponents.isEmpty() && !sbOpponents.get(sbOpp).pets.isEmpty()) {
                    int n = sbOpponents.get(sbOpp).pets.size();
                    sbPet = (sbPet + 1) % n;
                    sbLine = 0;
                }
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                sbLine = Math.min(sbLine + 1, Math.max(0, sbPetLines().size() - 1));
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                sbLine = Math.max(0, sbLine - 1);
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_HOME:
                sbPet = 0;
                sbLine = 0;
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                if (!sbOpponents.isEmpty()) {
                    sbPet = Math.max(0, sbOpponents.get(sbOpp).pets.size() - 1);
                }
                sbLine = 0;
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                say(describeScoreboard(), true);
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_Y:
                speakScoreboardAll();
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                toggleScoreboard();
                return true;
            default:
                break;
        }
        // Shift+Tab also steps back an opponent.
        if (key == KeyEvent.KEYCODE_TAB && event.isShiftPressed()) {
            if (!sbOpponents.isEmpty()) {
                sbOpp = (sbOpp - 1 + sbOpponents.size()) % sbOpponents.size();
                sbPet = 0;
                sbLine = 0;
            }
            say(describeScoreboard(), true);
            return true;
        }
        return false;
    }

    private boolean isBattlePadKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return true;
            default:
                return false;
        }
    }

    /**
     * The PC mod's context-modal controller for battles: X fast-forwards,
     * Y toggles autoplay, the right trigger skips, bumpers re-read.
     */
    private boolean handleBattlePad(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A:
                activate();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                back();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                fastForwardPressed();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                autoplayPressed();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
                skipPressed();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                readLastBattle();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                readScreen();
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                readLastBattle();
                return true;
            default:
                return false;
        }
    }

    private boolean isShopPadKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return true;
            default:
                return false;
        }
    }

    /**
     * The PC mod's context-modal controller for the shop: bare buttons move
     * between zones and act, holding LT turns them into status questions and
     * holding RT into the quick actions.
     */
    private boolean handleShopPad(int key) {
        if (ltHeld) {
            switch (key) {
                case KeyEvent.KEYCODE_BUTTON_A:
                    announceGold();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                    announceLives();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                    announceTurn();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    announceWins();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    announceDetails();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_L1:
                    toggleScoreboard();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_THUMBR:
                    jumpToZone("bullies", "Upcoming bullies");
                    return true;
                default:
                    return true;
            }
        }
        if (rtHeld) {
            switch (key) {
                case KeyEvent.KEYCODE_BUTTON_A:
                    rollPressed();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                    freezePressed();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_Y:
                    endTurnPressed();
                    return true;
                default:
                    return true;
            }
        }
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A:
                activate();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                back();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                sellPressed();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                announceBoard();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                cycleZone(-1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                cycleZone(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                readCurrent();
                return true;
            default:
                return false;
        }
    }

    /** X key: sell the focused team pet, the PC mod's deferred PerformSell. */
    private void sellPressed() {
        SapNode node =
                focusIndex >= 0 && focusIndex < nodes.size() ? nodes.get(focusIndex) : null;
        if (!"shop".equals(shopPhase) || node == null || !"team".equals(node.zone)) {
            say("Select a team pet to sell.", true);
            return;
        }
        if (SapNative.nativeSelectTeam(node.id)) {
            pendingSellId = node.id;
            pendingSellName = node.label;
            pendingSellSell = node.sell;
            pendingSellDue = SystemClock.uptimeMillis() + 1200;
            say("Selling " + node.label + "...", true);
        } else {
            say("Cannot sell " + node.label + " now.", true);
        }
    }

    private void announceDetails() {
        SapNode node = focusIndex >= 0 && focusIndex < nodes.size() ? nodes.get(focusIndex) : null;
        if (node == null) {
            say("No focused control.", true);
            return;
        }
        String text = node.label;
        if (!node.detail.isEmpty()) text += ". " + node.detail;
        say(text.isEmpty() ? "No details for this item." : text, true);
    }

    /** Compact dump of the first controls: id, role, label and screen box. */
    private String nodeSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size() && i < 8; i++) {
            SapNode node = nodes.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(node.role).append(':').append(node.label.isEmpty() ? "(blank)" : node.label)
                    .append('@').append(Math.round(node.x)).append(',').append(Math.round(node.y))
                    .append(' ').append(Math.round(node.width)).append('x').append(Math.round(node.height));
        }
        return sb.toString();
    }

    private void announceScreen() {
        if (dictionary != null && dictionary.isOpen()) return;
        if (focusIndex < 0) focusIndex = firstFocusable();
        if (touchExploration) {
            // TalkBack speaks the focused control itself; announce the screen
            // title so the move has context.
            if (!title.isEmpty() && overlay != null) overlay.announce(title);
            speakFocus();
            return;
        }
        if (!title.isEmpty()) say(title, true);
        String hint = menuHint(title);
        if (hint != null) say(hint, false);
        if (focusIndex >= 0) say(describe(nodes.get(focusIndex)), false);
    }

    /** The game moved its own selection (a screen opened, a popup closed). */
    private void syncFocusFromGame() {
        if (dictionary != null && dictionary.isOpen()) return;
        int nativeFocus = SapNative.nativeFocused();
        if (nativeFocus == 0) return;
        int index = indexOfId(nativeFocus);
        if (index >= 0 && index != focusIndex && !nodes.get(index).role.equals("text")) {
            focusIndex = index;
            refreshFocusLines();
            // Controller <-> TalkBack sync, game -> TalkBack: move the
            // screen-reader cursor so a popup the game focused is read.
            if (touchExploration && overlay != null && !syncingFocus) {
                syncingFocus = true;
                try { overlay.requestFocus(indexOfId(nativeFocus)); } finally { syncingFocus = false; }
            }
        }
    }

    /** TalkBack moved onto a control: mirror it into the controller state so
     * arrow keys, gamepad, detail lines and sell targeting all follow the
     * finger, and select it in the game. No overlay re-drive: the cursor is
     * already there, and re-focusing loops TalkBack back to the top. */
    private void onTalkBackFocus(int id) {
        SapNative.nativeFocus(id);
        if (syncingFocus) return;
        int index = indexOfId(id);
        if (index < 0) return;
        if (nodes.get(index).role.equals("text")) return;
        syncingFocus = true;
        try {
            focusIndex = index;
            refreshFocusLines();
            lastEarconIndex = -1; // let speakFocus-free path re-arm earcons
            SapNode focused = nodes.get(focusIndex);
            if ("team".equals(focused.zone) && !focused.label.isEmpty()
                    && teamNameRepeats(focused.label.split(" ")[0])) {
                earcon(1);
            }
        } finally {
            syncingFocus = false;
        }
    }

    /** TalkBack custom-action dispatch from SapOverlay (Roll / Freeze / ...). */
    private void handleCustomAction(int id, int action) {
        int index = indexOfId(id);
        if (index >= 0) focusIndex = index;
        if (action == SapOverlay.ACTION_ROLL) { rollPressed(); return; }
        if (action == SapOverlay.ACTION_END_TURN) { endTurnPressed(); return; }
        if (action == SapOverlay.ACTION_BOARD) { announceBoard(); return; }
        if (action == SapOverlay.ACTION_DETAILS) { announceDetails(); return; }
        if (action == SapOverlay.ACTION_FREEZE) {
            if (index >= 0) {
                SapNode n = nodes.get(index);
                if (SapNative.nativeFreeze(n.id)) {
                    say((n.frozen == 1 ? "Unfroze " : "Froze ") + n.label, true);
                    return;
                }
            }
            freezePressed();
            return;
        }
        if (action == SapOverlay.ACTION_SELL) { sellPressed(); return; }
        if (action == SapOverlay.ACTION_PLACE) { placeHeldPet(index); return; }
        activateId(id);
    }

    private int indexOfId(int id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id == id) return i;
        }
        return -1;
    }

    /** True when the new snapshot has exactly the same controls by id. */
    private boolean sameIds(List<SapNode> other) {
        if (other.size() != nodes.size()) return false;
        for (SapNode n : other) {
            if (indexOfId(n.id) < 0) return false;
        }
        return true;
    }

    private int firstFocusable() {
        for (int i = 0; i < nodes.size(); i++) {
            if (isFocusable(i)) return i;
        }
        return -1;
    }

    private boolean isFocusable(int index) {
        return index >= 0 && index < nodes.size() && nodes.get(index).focusable
                && !nodes.get(index).role.equals("text");
    }

    private int focusableCount() {
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) if (isFocusable(i)) count++;
        return count;
    }

    private int focusablePosition(int index) {
        int position = 0;
        for (int i = 0; i <= index && i < nodes.size(); i++) if (isFocusable(i)) position++;
        return position;
    }

    // -- speech ------------------------------------------------------------

    /** Screen-reader text: TalkBack announcement when it is running, TTS otherwise. */
    private void say(String text, boolean interrupt) {
        if (text == null || text.trim().isEmpty()) return;
        if (touchExploration) {
            if (overlay != null) overlay.announce(text);
            return;
        }
        if (speech != null) speech.speak(text, interrupt);
    }

    /** Move TalkBack's focus, or speak the control when TTS is in use. */
    // Detail lines of the focused control, like the PC mod's
    // ShopNavigationManager._currentDetailLines: Up and Down browse them,
    // Space re-reads the current one. Rebuilt on every focus move.
    private final java.util.List<String> focusLines = new java.util.ArrayList<>();
    private int focusLine;

    private void refreshFocusLines() {
        focusLines.clear();
        focusLine = 0;
        if (focusIndex < 0 || focusIndex >= nodes.size()) return;
        SapNode node = nodes.get(focusIndex);
        String full = node.label;
        if (!node.detail.isEmpty()) full += full.isEmpty() ? node.detail : ". " + node.detail;
        for (String part : full.split("\\. ")) {
            String line = part.trim();
            if (!line.isEmpty()) focusLines.add(line);
        }
    }

    private void moveFocusLine(int direction) {
        if (focusLines.isEmpty()) return;
        int next = focusLine + direction;
        if (next < 0) next = 0;
        if (next >= focusLines.size()) next = focusLines.size() - 1;
        focusLine = next;
        say(focusLines.get(focusLine), true);
    }

    // Earcons, the Android stand-in for the PC mod's SoundManager clips.
    // TalkBack owns speech, so cues are short system tones: one beep when
    // the focused team pet can combine (two share a name), two beeps after
    // a combine lands. Frozen/chained cues need game state the bridge cannot
    // read yet, so they stay silent until runtime probing names the API.
    private android.media.ToneGenerator toneGen;
    private int lastEarconIndex = -1;

    private void earcon(int beeps) {
        try {
            if (toneGen == null) {
                toneGen = new android.media.ToneGenerator(
                        android.media.AudioManager.STREAM_MUSIC, 60);
            }
            for (int i = 0; i < beeps; i++) {
                final int delay = i * 180;
                handler.postDelayed(() -> {
                    try {
                        toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120);
                    } catch (Throwable ignored) {
                    }
                }, delay);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean teamNameRepeats(String base) {
        if (base.isEmpty()) return false;
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            SapNode node = nodes.get(i);
            if (!"team".equals(node.zone) || node.label.isEmpty()) continue;
            if (node.label.split(" ")[0].equals(base) && ++count >= 2) return true;
        }
        return false;
    }

    private void speakFocus() {
        if (focusIndex < 0 || focusIndex >= nodes.size()) return;
        refreshFocusLines();
        SapNode focused = nodes.get(focusIndex);
        if (focusIndex != lastEarconIndex) {
            lastEarconIndex = focusIndex;
            if ("team".equals(focused.zone) && !focused.label.isEmpty()
                    && teamNameRepeats(focused.label.split(" ")[0])) {
                earcon(1);
            }
        }
        SapNative.nativeFocus(focused.id);
        if (touchExploration) {
            if (overlay != null && !syncingFocus) {
                syncingFocus = true;
                try { overlay.requestFocus(focusIndex); } finally { syncingFocus = false; }
            }
        } else {
            say(describe(nodes.get(focusIndex)), true);
        }
    }

    private String describe(SapNode node) {
        int index = Math.max(focusIndex, 0);
        String base = node.speech(node.role.equals("text") ? 0 : focusablePosition(index), focusableCount());
        String afford = affordabilitySuffix(node);
        return afford.isEmpty() ? base : base + afford;
    }

    /**
     * Affordability suffix for shop items. The rule lives in ShopView so the
     * controller and the TalkBack overlay cannot drift apart, and so an empty
     * slot is recognised by its real placeholder name ("Pet shop slot", not
     * "Empty") instead of being offered at 3 gold. Never disables the node: a
     * tap may still be valid (free rolls, frozen items, sales).
     */
    private String affordabilitySuffix(SapNode node) {
        if (!"shop".equals(shopPhase)) return "";
        return ShopView.affordability(node, shopGold);
    }

    private void moveFocus(int delta) {
        if (nodes.isEmpty()) return;
        int count = nodes.size();
        int index = focusIndex;
        for (int step = 0; step < count; step++) {
            index = Math.floorMod(index + delta, count);
            if (isFocusable(index)) {
                focusIndex = index;
                break;
            }
        }
        if (focusIndex >= 0) speakFocus();
    }

    private void moveFocusTo(int delta) {
        if (nodes.isEmpty()) return;
        int index = focusIndex;
        for (int step = 0; step < nodes.size(); step++) {
            index = Math.floorMod(index + delta, nodes.size());
            if (isFocusable(index)) {
                focusIndex = index;
                break;
            }
        }
        if (focusIndex >= 0) speakFocus();
    }

    private void activate() {
        if (isFocusable(focusIndex)) activateId(nodes.get(focusIndex).id);
    }

    // Food targeting session, like the PC mod's SpellFocus handling: after a
    // food item is used the team owns the keys until Enter confirms the pet
    // or Backspace cancels. Combine watch focuses the bought pet afterwards.
    private boolean targetingFood;
    private String targetingFoodName = "";
    private int targetingFoodId;
    private String pendingCombineName = "";
    private long combineWatchDue;

    /**
     * A genuine finger-equivalent tap: synthetic down/up dispatched to the
     * game's own window at the node's screen position. The synthetic UI-event
     * path (nativeTap) cannot drive some of the game's custom buttons, while
     * a real touch goes through Unity exactly like a finger; the transparent
     * overlay never consumes it, so nothing is double-activated. Returns
     * whether the game consumed the touch.
     */
    private boolean injectTapSync(final int sx, final int sy) {
        return injectTapSync(0, sx, sy);
    }

    /** Same, with the control id in the log line so a report shows what was sent. */
    private boolean injectTapSync(final int id, final int sx, final int sy) {
        final Activity a = activity;
        if (a == null || pressInFlight) return false;
        final boolean[] consumed = new boolean[2];
        Runnable press = () -> {
            try {
                View decor = a.getWindow().getDecorView();
                int[] loc = new int[2];
                decor.getLocationOnScreen(loc);
                float x = sx - loc[0];
                float y = sy - loc[1];
                long downTime = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                consumed[0] = decor.dispatchTouchEvent(down);
                MotionEvent up = MotionEvent.obtain(
                        downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                consumed[1] = decor.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
            } catch (Throwable t) {
                diag("inject tap failed: " + t, false);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            press.run();
        } else {
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            a.runOnUiThread(() -> {
                try {
                    press.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // A missing release still beats a hung TalkBack gesture.
            }
        }
        // Always logged: until this line appears with consumed=1, the touch
        // path has never actually reached the game, and that is the difference
        // between "the tap was not forwarded" and "the game ignored it".
        diag("inject tap id=" + id + " at " + sx + "," + sy
                + " consumedDown=" + consumed[0] + " consumedUp=" + consumed[1], false);
        return consumed[0] || consumed[1];
    }

    /**
     * A real drag between two points: the gesture the game itself uses to move
     * a pet from a shop slot onto a chosen board slot. Without it a pet can
     * only ever land in the first free slot, so the player cannot choose where
     * the team goes.
     */
    private boolean injectDragSync(final int id, final int fromX, final int fromY,
                                   final int toX, final int toY) {
        final Activity a = activity;
        if (a == null || pressInFlight) return false;
        final boolean[] consumed = new boolean[2];
        Runnable drag = () -> {
            try {
                View decor = a.getWindow().getDecorView();
                int[] loc = new int[2];
                decor.getLocationOnScreen(loc);
                float x0 = fromX - loc[0];
                float y0 = fromY - loc[1];
                float x1 = toX - loc[0];
                float y1 = toY - loc[1];
                long downTime = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, x0, y0, 0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                consumed[0] = decor.dispatchTouchEvent(down);
                // Sliding in steps rather than teleporting: UGUI needs move
                // events to start its drag threshold and to track the pointer.
                final int steps = 6;
                for (int i = 1; i <= steps; i++) {
                    long when = downTime + i * 30L;
                    float mx = x0 + (x1 - x0) * i / steps;
                    float my = y0 + (y1 - y0) * i / steps;
                    MotionEvent move = MotionEvent.obtain(
                            downTime, when, MotionEvent.ACTION_MOVE, mx, my, 0);
                    move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    decor.dispatchTouchEvent(move);
                    move.recycle();
                    SystemClock.sleep(30);
                }
                MotionEvent up = MotionEvent.obtain(
                        downTime, downTime + steps * 30L + 40L, MotionEvent.ACTION_UP, x1, y1, 0);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                consumed[1] = decor.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
            } catch (Throwable t) {
                diag("inject drag failed: " + t, false);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            drag.run();
        } else {
            a.runOnUiThread(drag);
        }
        diag("inject drag id=" + id + " from " + fromX + "," + fromY + " to " + toX + "," + toY
                + " consumedDown=" + consumed[0] + " consumedUp=" + consumed[1], false);
        return consumed[0] || consumed[1];
    }

    /**
     * A real held touch: press, hold long enough for the game's long-press
     * handling, release. Same delivery as injectTapSync, so the game sees it
     * exactly like a finger.
     */
    /** True while an injected press is still down, so taps cannot stack on it. */
    private volatile boolean pressInFlight;

    private boolean injectLongPressSync(final int sx, final int sy) {
        final Activity a = activity;
        if (a == null || pressInFlight) return false;
        pressInFlight = true;
        final boolean[] consumed = new boolean[1];
        Runnable press = () -> {
            try {
                View decor = a.getWindow().getDecorView();
                int[] loc = new int[2];
                decor.getLocationOnScreen(loc);
                final float x = sx - loc[0];
                final float y = sy - loc[1];
                final long downTime = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                consumed[0] = decor.dispatchTouchEvent(down);
                down.recycle();
                // Release after a real hold, from the handler so the UI thread
                // is free while the game processes the press.
                handler.postDelayed(() -> {
                    try {
                        MotionEvent up = MotionEvent.obtain(downTime,
                                SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
                        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        decor.dispatchTouchEvent(up);
                        up.recycle();
                    } catch (Throwable ignored) {
                        // The press already happened.
                    } finally {
                        pressInFlight = false;
                    }
                }, 700);
            } catch (Throwable t) {
                pressInFlight = false;
                diag("inject long press failed: " + t, false);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            press.run();
        } else {
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            a.runOnUiThread(() -> {
                try {
                    press.run();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // The release still runs from the handler.
            }
        }
        return consumed[0];
    }

    /**
     * nativeTap first; when the synthetic path cannot drive the control, a
     * genuine injected touch at the node's position. True when either was sent.
     */
    private boolean tapOrInject(int id, int index) {
        if (index < 0 || index >= nodes.size()) return false;
        String label = nodes.get(index).label;
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        // The card's own centre, not the centre of its clamped box: clamping
        // moved the tap point inside the screen but off the control.
        int[] p = nodes.get(index).tapPoint();
        int cx = p[0];
        int cy = p[1];
        // A point outside the screen cannot be this control's position, so
        // injecting there would press whatever happens to be under it.
        if (cx < 0 || cy < 0 || cx > dm.widthPixels || cy > dm.heightPixels) {
            diag("tap refused id=" + id + " at " + cx + "," + cy + " (off screen)", false);
            return false;
        }
        // A shop or team slot: run the game's own handler for that container,
        // exactly as the PC mod's PerformAction buys a pet, buys food and
        // chooses a team slot. This goes before the touch because a synthetic
        // tap on these custom controls can report success without running the
        // handler, which is what left the shop unresponsive.
        if (SapNative.nativeShopClick(id)) {
            diag("shop click id=" + id + " label=" + label, false);
            return true;
        }
        // A tap that starts a load pauses the scan before it lands, whichever
        // path below ends up delivering it.
        if (looksLikePlayControl(label)) {
            try {
                SapNative.nativeQuiet(TRANSITION_QUIET_MS);
            } catch (Throwable ignored) {
                // Older native library; the scan keeps its own schedule.
            }
        }
        // A genuine touch first. The synthetic UI-event route reports success
        // on this game's custom controls without actually running them, which
        // is why a plain double-tap did nothing while double-tap-and-hold (a
        // real held touch) drove the same control. The staleness check runs
        // first so the touch can only land on the control still at those
        // pixels; an unchanged box passes, a swapped screen refuses.
        if (SapNative.nativeTapCheck(id, cx, cy)) {
            if (injectTapSync(id, cx, cy)) {
                diag("tap touched id=" + id + " label=" + label, false);
                return true;
            }
        } else {
            diag("tap stale id=" + id + " label=" + label + " (screen changed, tap again)", false);
        }
        // Nothing consumed the touch, so the control needs the synthetic path
        // after all; the submit handlers below are the last resort.
        if (looksLikePlayControl(label)) {
            try {
                SapNative.nativeQuiet(TRANSITION_QUIET_MS);
            } catch (Throwable ignored) {
                // Older native library; the scan keeps its own schedule.
            }
        }
        if (SapNative.nativeTap(id)) {
            diag("tap synthetic id=" + id + " label=" + label, false);
            return true;
        }
        // The real touch went nowhere either: the game's own submit handlers
        // for Roll, Done, Battle and chooser confirms, matched by label.
        if (SapNative.nativeSubmitFallback(id, label)) {
            diag("tap submitted id=" + id + " label=" + label, false);
            return true;
        }
        return false;
    }

    /**
     * TalkBack long press: a genuine held touch at the control, which is what
     * the gesture used to send through the transparent overlay before the tree
     * advertised CLICK on every node. That path is the one that still drives
     * controls the synthetic click cannot, so it is kept working deliberately.
     */
    private void longPressId(int id) {
        int index = indexOfId(id);
        if (index < 0) {
            activateId(id);
            return;
        }
        focusIndex = index;
        SapNative.nativeFocus(id);
        if (!tapTargetUsable(nodes.get(index), id)) return;
        int[] p = nodes.get(index).tapPoint();
        int cx = p[0];
        int cy = p[1];
        if (injectLongPressSync(cx, cy)) {
            diag("long press injected id=" + id + " label=" + nodes.get(index).label, false);
            return;
        }
        activateId(id);
    }

    private void activateId(int id) {
        activateId(id, indexOfId(id), false);
    }

    /** True once the game has consumed the tap; the retry path must not queue again. */
    private void activateId(int id, int index, boolean alreadyRetried) {
        if (index >= 0) focusIndex = index;
        if (index >= 0 && !tapTargetUsable(nodes.get(index), id)) return;
        if (gameLoading && !alreadyRetried) {
            // Queue it instead of dropping it: a tap during a load used to be
            // swallowed, which read as "the menu stopped responding".
            pendingLoadingTapId = id;
            pendingLoadingTapIndex = index;
            String name = index >= 0 ? nodes.get(index).label : "That";
            diag("tap deferred id=" + id + " label=" + name + " (game still loading)", false);
            say("Still loading. I will tap " + (name.isEmpty() ? "it" : name)
                    + " as soon as the game is ready.", true);
            return;
        }
        // A pet is held — picked up from the shop row or from the board — so
        // activating another board slot puts it there. This is the mobile form
        // of the PC mod's "choose the slot, then Enter": the board itself is
        // the slot chooser, and nothing is asked twice.
        if (heldPetId >= 0 && SystemClock.uptimeMillis() < heldPetExpiry
                && index >= 0 && "team".equals(nodes.get(index).zone)
                && id != heldPetId) {
            placeHeldPet(index);
            return;
        }
        // Confirming a team pet while targeting applies the food, as Enter
        // does on PC once the team zone owns the selection.
        if (targetingFood && index >= 0 && "team".equals(nodes.get(index).zone)) {
            String pet = nodes.get(index).label;
            // A whole-team food has no aim target, so the game ignores a pet
            // click and waits for its own SpellZone click. The PC mod does the
            // same check and clicks the zone; without this the item could
            // never be used and the port claimed it had been.
            if (ShopRules.spellCoversTeam(SapNative.nativeSpellFocusAimless())
                    && SapNative.nativeUseSpellZone()) {
                say("Used " + targetingFoodName + " on your team.", true);
                targetingFood = false;
                return;
            }
            SapNative.nativeFocus(id);
            if (tapOrInject(id, index)) {
                say("Used " + targetingFoodName + " on " + pet + ".", true);
            } else {
                say(targetingFoodName + ", not available", true);
            }
            targetingFood = false;
            return;
        }
        // The game's own controls act on the current selection, so make this
        // control the selection first, as the PC mod's keys do.
        SapNative.nativeFocus(id);
        if (!tapOrInject(id, index)) {
            String label = index >= 0 ? nodes.get(index).label : "Item";
            // File-only: the next report shows which control would not tap.
            diag("tap failed id=" + id + " label=" + label, false);
            say("The game did not take that tap. Try again.", true);
            return;
        }
        if (index < 0) return;
        SapNode node = nodes.get(index);
        if (looksLikePlayControl(node.label)) watchTransition(node.label);
        if ("foodshop".equals(node.zone)) {
            // The game entered food targeting: hand the keys to the team, as
            // the PC mod's SpellFocus branch does.
            targetingFood = true;
            targetingFoodName = node.label.isEmpty() ? "food" : node.label;
            targetingFoodId = id;
            say(ShopRules.spellCoversTeam(SapNative.nativeSpellFocusAimless())
                    ? "Swipe to the team and double-tap any pet to use " + targetingFoodName
                            + " on your whole team."
                    : "Swipe to the team and double-tap the pet to use " + targetingFoodName
                            + " on.", true);
            jumpToZone("team", "Team");
        } else if ("shop".equals(shopPhase) && "petshop".equals(node.zone)) {
            // Remember which card this was. Buying drops a pet into the first
            // free slot, but the player has to be able to choose the slot, so
            // the card stays "held" and a team slot can be dragged onto from
            // exactly here.
            heldPetId = id;
            heldPetName = node.label.isEmpty()
                    ? (node.detail.isEmpty() ? "pet" : node.detail.split(",")[0])
                    : node.label.split(",")[0];
            heldPetX = node.x;
            heldPetY = node.y;
            heldPetExpiry = SystemClock.uptimeMillis() + 12000;
            // Bought a pet: watch for the combine, then focus the result.
            String base = node.label.isEmpty() ? "" : node.label.split(" ")[0];
            if (!base.isEmpty()) {
                pendingCombineName = base;
                combineWatchDue = SystemClock.uptimeMillis() + 2500;
            }
        } else if ("team".equals(node.zone) && !node.label.startsWith("Team slot")) {
            // A board pet is picked up by activating it: the next board slot
            // activated takes it, which is how the team order (and therefore
            // what fights in front) gets chosen. TalkBack's own focus already
            // walks the board, so this is the whole "where do you want it" step.
            heldPetId = id;
            heldPetName = node.label.isEmpty()
                    ? (node.detail.isEmpty() ? "pet" : node.detail.split(",")[0])
                    : node.label.split(",")[0];
            heldPetX = node.x;
            heldPetY = node.y;
            heldPetExpiry = SystemClock.uptimeMillis() + 12000;
            say(heldPetName + " picked up. Swipe to the board slot you want and double-tap it.",
                    true);
        }
    }

    // True while the native side reports no snapshot: the game is building a
    // page and Unity ignores touches until it finishes. A tap asked for during
    // that window is remembered and sent once, when the page is there.
    private boolean gameLoading;
    private int pendingLoadingTapId;
    private int pendingLoadingTapIndex = -1;

    // The pet last activated (shop card or board pet), so the next board slot
    // activated can take it. Expires so an old hold cannot move the wrong pet.
    private int heldPetId = -1;
    private String heldPetName = "";
    private float heldPetX;
    private float heldPetY;
    private long heldPetExpiry;

    /**
     * Drag the held shop pet onto the slot the user chose. The game's own way of
     * placing a pet is a drag from card to slot; a tap can only append to the
     * first free place.
     */
    private void placeHeldPet(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= nodes.size()) return;
        SapNode target = nodes.get(targetIndex);
        if (!"team".equals(target.zone)) {
            say("Pick a team slot to place the pet in.", true);
            return;
        }
        if (heldPetId < 0) {
            say("Pick a pet from the shop first.", true);
            return;
        }
        String slot = slotNumber(targetIndex);
        // The game's own team-slot handler first: while a bought pet is being
        // aimed, this is the click that places it, and it is the PC mod's
        // "Enter on the team slot". The drag below is the gesture fallback.
        if (SapNative.nativeShopClick(target.id)) {
            diag("place: '" + heldPetName + "' -> " + slot + " by handler", true);
            heldPetId = -1;
            heldPetExpiry = 0;
            say("Placing " + heldPetName + " in " + slot + ".", true);
            return;
        }
        // The drop point must still be that slot. A drag released on a stale
        // point drops the pet onto whatever is there now, so when the slot is
        // not where the tree said, the game's own click path is used instead.
        boolean aimed = SapNative.nativeTapCheck(target.id, Math.round(target.x),
                Math.round(target.y));
        boolean sent;
        if (aimed) {
            sent = injectDragSync(heldPetId, Math.round(heldPetX), Math.round(heldPetY),
                    Math.round(target.x), Math.round(target.y));
        } else {
            sent = tapOrInject(target.id, targetIndex);
        }
        diag("place: '" + heldPetName + "' -> slot " + slot + " drag=" + aimed
                + " sent=" + sent, true);
        heldPetId = -1;
        heldPetExpiry = 0;
        if (sent) {
            say("Placing " + heldPetName + " in " + slot + ".", true);
        } else {
            say("Could not place " + heldPetName + ".", true);
        }
    }

    /** "slot 2" for the second team slot in reading order. */
    private String slotNumber(int index) {
        int position = 0;
        for (int i = 0; i <= index && i < nodes.size(); i++) {
            if (!"team".equals(nodes.get(i).zone)) continue;
            position++;
            if (i == index) break;
        }
        return position > 0 ? "slot " + position : "the board";
    }

    /** Backspace cancels food targeting, like CancelCurrentAction on PC. */
    private void cancelAction() {
        if (targetingFood) {
            targetingFood = false;
            SapNative.nativeFocus(targetingFoodId);
            SapNative.nativeTap(targetingFoodId);
            say("Cancelled " + targetingFoodName + ".", true);
            return;
        }
        back();
    }

    // Shop event narration, the PC mod's ShopNarrator without its Harmony
    // hooks: consecutive stable shop snapshots are diffed per zone, so buys,
    // restocks, new teammates and new turns are spoken.
    private final java.util.Map<String, java.util.List<String>> prevZoneLabels = new java.util.HashMap<>();
    private int prevGold = -1;
    private int prevTurn = -1;

    private void pollShopEvents() {
        if (!"shop".equals(shopPhase)) {
            prevGold = -1;
            return;
        }
        java.util.Map<String, java.util.List<String>> now = new java.util.HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            SapNode node = nodes.get(i);
            if (!isFocusable(i)) continue;
            java.util.List<String> list = now.get(node.zone);
            if (list == null) {
                list = new java.util.ArrayList<>();
                now.put(node.zone, list);
            }
            // Cards carry their name in detail, not label, so a label-only
            // list left every real pet out of the diff: a purchase could never
            // remove a name and a teammate could never join one. slotName is
            // the same name the tree speaks.
            list.add(ShopView.slotName(node));
        }
        if (prevGold < 0) {
            prevZoneLabels.clear();
            prevZoneLabels.putAll(now);
            prevGold = shopGold;
            prevTurn = shopTurn;
            return;
        }
        java.util.List<String> added = new java.util.ArrayList<>();
        java.util.List<String> removed = new java.util.ArrayList<>();
        java.util.List<String> joined = new java.util.ArrayList<>();
        for (String zone : new String[]{"petshop", "foodshop"}) {
            java.util.List<String> before = prevZoneLabels.get(zone);
            java.util.List<String> after = now.get(zone);
            if (before == null) before = new java.util.ArrayList<>();
            if (after == null) after = new java.util.ArrayList<>();
            for (String label : after) if (!before.contains(label)) added.add(label);
            for (String label : before) if (!after.contains(label)) removed.add(label);
        }
        java.util.List<String> teamBefore = prevZoneLabels.get("team");
        java.util.List<String> teamAfter = now.get("team");
        if (teamBefore == null) teamBefore = new java.util.ArrayList<>();
        if (teamAfter == null) teamAfter = new java.util.ArrayList<>();
        for (String label : teamAfter) if (!teamBefore.contains(label)) joined.add(label);
        prevZoneLabels.clear();
        prevZoneLabels.putAll(now);
        boolean newTurn = shopTurn != prevTurn;
        int spent = prevGold - shopGold;
        prevGold = shopGold;
        prevTurn = shopTurn;
        // A buy already speaks through the tap itself; the combine watch
        // speaks for merges, so stay quiet for those.
        if (!pendingCombineName.isEmpty() || !removed.isEmpty() && spent > 0 && !joined.isEmpty()) return;
        if (spent > 0 && !removed.isEmpty()) {
            say("Bought " + removed.get(0) + ".", true);
            return;
        }
        if (!joined.isEmpty()) say(joined.get(0) + " joined the team.", true);
        if (newTurn && !added.isEmpty()) {
            say("Turn " + shopTurn + ". New in the shop: " + android.text.TextUtils.join(", ", added) + ".", true);
        } else if (newTurn) {
            say("Turn " + shopTurn + ".", true);
        } else if (!added.isEmpty() && spent <= 0) {
            say("New in the shop: " + android.text.TextUtils.join(", ", added) + ".", true);
        }
    }

    /** Second half of a buy: focus the combined pet once the team settles. */
    private void pollCombineWatch() {
        if (pendingCombineName.isEmpty() || SystemClock.uptimeMillis() < combineWatchDue) return;
        String base = pendingCombineName;
        pendingCombineName = "";
        for (int i = 0; i < nodes.size(); i++) {
            SapNode node = nodes.get(i);
            if (!isFocusable(i) || !"team".equals(node.zone)) continue;
            if (ShopView.slotName(node).contains(base)) {
                focusIndex = i;
                speakFocus();
                earcon(2);
                say("Combined. " + node.label + ".", true);
                return;
            }
        }
        jumpToZone("team", "Team");
    }

    private void back() {
        // Escape also cancels food targeting, as on PC.
        if (targetingFood) {
            cancelAction();
            return;
        }
        for (int i = 0; i < nodes.size(); i++) {
            SapNode node = nodes.get(i);
            if (!isFocusable(i)) continue;
            String label = node.label.toLowerCase(Locale.ROOT);
            if (label.equals("back") || label.equals("close") || label.equals("cancel")
                    || label.equals("escape") || label.equals("no")) {
                focusIndex = i;
                SapNative.nativeFocus(node.id);
                if (!SapNative.nativeTap(node.id)) say(node.label + ", not available", true);
                return;
            }
        }
        sendSystemKey(KeyEvent.KEYCODE_BACK);
    }

    /** Give the game the key it would have received, through the real callback. */
    private void sendSystemKey(int keyCode) {
        if (originalCallback == null) return;
        long now = SystemClock.uptimeMillis();
        originalCallback.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        originalCallback.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /** Read the focused item again (X, right stick click). */
    private void readCurrent() {
        if (focusIndex >= 0) speakFocus();
    }

    /**
     * The PC mod's menu navigators (achievements, leaderboard, replay, news,
     * picker): the tree already exposes every control, so the port keeps the
     * opening hint each navigator speaks when its page opens.
     */
    private String menuHint(String page) {
        String t = page.toLowerCase(Locale.ROOT);
        if (t.contains("achievement")) {
            return "Achievements. Tab moves between the filters and the list. Up and down move through the pets. Escape closes.";
        }
        if (t.contains("leaderboard")) {
            return "Leaderboard. Up and down read the players, Home and End go to either end, R reads your rank. Escape closes.";
        }
        if (t.contains("replay")) {
            return "Replays. Up and down move through the replays, Enter plays one. Escape closes.";
        }
        if (t.contains("news")) {
            return "News. Up and down move through the stories, Enter opens one. Escape closes.";
        }
        if (t.contains("picker") || t.contains("option") || t.contains("setting") || t.contains("language")) {
            return "Options. Up and down move through the choices, Enter picks one. Escape closes.";
        }
        if (t.contains("choos") || t.contains("pack")) {
            return "Chooser. Up and down move through the choices, Enter picks one. Escape closes.";
        }
        return null;
    }

    private void readScreen() {
        StringBuilder sb = new StringBuilder();
        if (!title.isEmpty()) sb.append(title).append(". ");
        String hint = menuHint(title);
        if (hint != null) sb.append(hint).append(" ");
        for (int i = 0; i < nodes.size(); i++) {
            SapNode node = nodes.get(i);
            if (node.role.equals("text")) {
                if (!node.label.isEmpty()) sb.append(node.label).append(". ");
            } else if (isFocusable(i)) {
                sb.append(node.speech(focusablePosition(i), focusableCount())).append(". ");
            }
        }
        say(sb.length() == 0 ? "This screen has no text." : sb.toString(), true);
    }

    private void help() {
        say("Keys. Left and right, previous and next item. Up and down, detail lines of a pet or food. Enter or the A button, activate. Space, re-read the current detail line. Backspace, cancel food targeting. "
                + "Escape or B, back. Home and end, first and last item. R or Y, read the shop or the screen. "
                + "S, pet shop. F, food shop. T, team. D, actions. V, upcoming bullies. "
                + "Left and right bumper, previous or next zone. "
                + "A, gold. L, lives. N, turn and tier. W, wins. "
                + "Q, roll. Z, freeze the focused shop item. E, end turn. X, sell the focused team pet. "
                + "C, turn timer. B, snacks counter. O, scoreboard. I, details of the focused pet. "
                + "In battle: P pause, G fast forward, U autoplay, K skip, Space repeats the last event. "
                + "On a controller, hold the left trigger for the status questions "
                + "and the right trigger for roll, freeze and end turn. "
                + "J or the left stick click, keyword dictionary. "
                + "View, this help. Start, back. With TalkBack on, swipe through the "
                + "controls and double-tap to activate; buying, selling and rolling "
                + "work by double-tapping the control itself.", true);
    }

    private void openDictionary() {
        if (dictionary == null) return;
        if (dictionary.isEmpty()) {
            say("The keyword dictionary is not available.", true);
            return;
        }
        dictionary.open();
        say("Dictionary. " + dictionary.summary() + " " + dictionary.current(), true);
    }

    // -- input -------------------------------------------------------------

    /** Called from the window callback; returning true consumes the key. */
    private boolean handleKey(KeyEvent event) {
        if (dictionary != null && dictionary.isOpen()) return handleDictionaryKey(event);

        int key = event.getKeyCode();
        if (!isHandledKey(key)) return false;
        // Trigger layers work from key events too; some pads send L2/R2 as
        // keys instead of motion axes, and both must release the layer.
        if (key == KeyEvent.KEYCODE_BUTTON_L2) {
            ltHeld = event.getAction() == KeyEvent.ACTION_DOWN;
            return true;
        }
        if (key == KeyEvent.KEYCODE_BUTTON_R2) {
            rtHeld = event.getAction() == KeyEvent.ACTION_DOWN;
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        // In the shop a controller is modal, like the PC mod's ResolveShop:
        // bare buttons drive zones and actions, LT asks status, RT acts fast.
        if ("shop".equals(shopPhase) && isShopPadKey(key)) return handleShopPad(key);
        // In battle X fast-forwards, Y toggles autoplay and RT skips, like ResolveBattle.
        if ("battle".equals(shopPhase) && isBattlePadKey(key)) return handleBattlePad(key);
        // An open scoreboard owns its navigation keys, like ScoreboardNavigator.
        if (sbSession && handleScoreboardKey(event)) return true;
        // Shop status comes from game state, not the tree, so it answers
        // even when nothing is scanned yet.
                    switch (key) {
            case KeyEvent.KEYCODE_A:
                announceGold();
                return true;
            case KeyEvent.KEYCODE_L:
                announceLives();
                return true;
            case KeyEvent.KEYCODE_N:
                announceTurn();
                return true;
            case KeyEvent.KEYCODE_W:
                announceWins();
                return true;
            case KeyEvent.KEYCODE_I:
                announceDetails();
                return true;
            case KeyEvent.KEYCODE_S:
                jumpToZone("petshop", "Pet shop");
                return true;
            case KeyEvent.KEYCODE_F:
                jumpToZone("foodshop", "Food shop");
                return true;
            case KeyEvent.KEYCODE_T:
                jumpToZone("team", "Team");
                return true;
            case KeyEvent.KEYCODE_D:
                jumpToZone("actions", "Actions");
                return true;
            case KeyEvent.KEYCODE_V:
                jumpToZone("bullies", "Upcoming bullies");
                return true;
            case KeyEvent.KEYCODE_Q:
                rollPressed();
                return true;
            case KeyEvent.KEYCODE_Z:
                freezePressed();
                return true;
            case KeyEvent.KEYCODE_E:
                endTurnPressed();
                return true;
            case KeyEvent.KEYCODE_R:
                if ("shop".equals(shopPhase)) announceBoard();
                else readScreen();
                return true;
            case KeyEvent.KEYCODE_X:
                if ("shop".equals(shopPhase)) sellPressed();
                else readCurrent();
                return true;
            case KeyEvent.KEYCODE_C:
                announceTimer();
                return true;
            case KeyEvent.KEYCODE_O:
                toggleScoreboard();
                return true;
            case KeyEvent.KEYCODE_B:
                announceSnacks();
                return true;
            case KeyEvent.KEYCODE_P:
                pausePressed();
                return true;
            case KeyEvent.KEYCODE_G:
                fastForwardPressed();
                return true;
            case KeyEvent.KEYCODE_U:
                autoplayPressed();
                return true;
            case KeyEvent.KEYCODE_K:
                skipPressed();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                // In battle Space re-reads the last announcement, as on PC;
                // elsewhere it falls through to activating the focused control.
                if ("battle".equals(shopPhase) && !lastBattleMsg.isEmpty()) {
                    readLastBattle();
                    return true;
                }
                break;
        }
        // Nothing to navigate: do not steal the game's own keys.
        if (nodes.isEmpty()) return false;

        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // Pets and food carry multi-line details, so Up and Down browse
                // their lines first, as on PC; plain buttons move the focus.
                if (focusLines.size() > 1) moveFocusLine(1);
                else moveFocus(1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusLines.size() > 1) moveFocusLine(-1);
                else moveFocus(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_BUTTON_R1:
                moveFocus(1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_BUTTON_L1:
                moveFocus(-1);
                break;
            case KeyEvent.KEYCODE_TAB:
                moveFocus(event.isShiftPressed() ? -1 : 1);
                break;
            case KeyEvent.KEYCODE_DEL:
                cancelAction();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_1:
                activate();
                break;
            case KeyEvent.KEYCODE_SPACE:
                // On a pet or food Space re-reads the current detail line, as
                // on PC; elsewhere it activates the focused control.
                if (focusLines.size() > 1) say(focusLines.get(focusLine), true);
                else activate();
                break;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_START:
                back();
                break;
            case KeyEvent.KEYCODE_HOME:
                moveFocusTo(Integer.MIN_VALUE / 2);
                break;
            case KeyEvent.KEYCODE_MOVE_END:
                moveFocusTo(Integer.MAX_VALUE / 2);
                break;
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                readCurrent();
                break;
            case KeyEvent.KEYCODE_H:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                help();
                break;
            case KeyEvent.KEYCODE_J:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                openDictionary();
                break;
            case KeyEvent.KEYCODE_BUTTON_Y:
                readScreen();
                break;
            default:
                return false;
        }
        return true;
    }

    /** Left stick and D-pad hat navigation; the game still receives the event. */
    private void handleMotion(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return;
        // Pads report LT/RT as trigger axes rather than keys: they arm the
        // PC mod's status-question and quick-action layers while held.
        ltHeld = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f;
        rtHeld = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f;
        // Right stick up/down is Home/End, like the PC mod's RUp/RDown.
        float z = event.getAxisValue(MotionEvent.AXIS_Z);
        float rz = event.getAxisValue(MotionEvent.AXIS_RZ);
        float r = Math.abs(z) >= Math.abs(rz) ? z : rz;
        int rd = 0;
        if (r > STICK_DEADZONE) rd = 1;
        else if (r < -STICK_DEADZONE) rd = -1;
        if (rd != 0 && rd != lastRzDirection) {
            moveFocusTo(rd > 0 ? Integer.MAX_VALUE / 2 : Integer.MIN_VALUE / 2);
        }
        lastRzDirection = rd;
        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);
        if (Math.abs(x) < 0.01f && Math.abs(y) < 0.01f) {
            x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        }
        int direction = 0;
        if (Math.abs(x) >= Math.abs(y)) {
            if (x > STICK_DEADZONE) direction = 1;
            else if (x < -STICK_DEADZONE) direction = -1;
        } else {
            if (y > STICK_DEADZONE) direction = 1;
            else if (y < -STICK_DEADZONE) direction = -1;
        }
        if (direction == 0) {
            lastStickDirection = 0;
            return;
        }
        if (direction == lastStickDirection) return;
        lastStickDirection = direction;
        moveFocus(direction);
    }

    private boolean handleDictionaryKey(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() != KeyEvent.ACTION_UP;

        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (down) say(dictionary.move(1), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (down) say(dictionary.move(-1), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) say(dictionary.moveCategory(1), true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) say(dictionary.moveCategory(-1), true);
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) say(dictionary.current(), true);
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (down) {
                    dictionary.close();
                    say("Dictionary closed.", true);
                }
                return true;
            case KeyEvent.KEYCODE_DEL:
                if (down && speech != null) speech.speak(dictionary.backspace(), true);
                return true;
            default:
                break;
        }

        if (!down) return true;
        int unicode = event.getUnicodeChar();
        if (unicode == 0) return true;
        char c = (char) unicode;
        if (Character.isLetterOrDigit(c) || c == ' ') {
            say(dictionary.type(c), true);
        }
        return true;
    }

    private boolean isHandledKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_B:
            case KeyEvent.KEYCODE_C:
            case KeyEvent.KEYCODE_I:
            case KeyEvent.KEYCODE_L:
            case KeyEvent.KEYCODE_N:
            case KeyEvent.KEYCODE_O:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_X:
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_T:
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_V:
            case KeyEvent.KEYCODE_Q:
            case KeyEvent.KEYCODE_Z:
            case KeyEvent.KEYCODE_E:
            case KeyEvent.KEYCODE_R:
            case KeyEvent.KEYCODE_H:
            case KeyEvent.KEYCODE_P:
            case KeyEvent.KEYCODE_G:
            case KeyEvent.KEYCODE_U:
            case KeyEvent.KEYCODE_K:
            case KeyEvent.KEYCODE_J:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_1:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return true;
            default:
                return false;
        }
    }

    // -- diagnostics -------------------------------------------------------

    /**
     * Everything the tester can hear or send back without a PC: logcat, a
     * toast and a file in the app's external files dir.
     */
    private void diag(String message, boolean show) {
        Log.i(TAG, message);
        if (show) toast(message);
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            File file = new File(dir, "sapa11y-diagnostics.txt");
            if (file.length() > 64 * 1024) file.delete();
            try (FileWriter out = new FileWriter(file, true)) {
                out.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                        + " " + message + "\n");
            }
        } catch (Throwable ignored) {
            // Diagnostics are best effort.
        }
    }

    private void toast(String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // No window token; the log and file still have it.
        }
    }
}
