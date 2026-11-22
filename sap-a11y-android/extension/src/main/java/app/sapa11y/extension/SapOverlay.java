package app.sapa11y.extension;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The TalkBack face of the mod.
 *
 * On Windows the mod publishes a real UIA tree through UnityA11y/AccessKit, so
 * a screen reader can browse the game's controls with its own gestures. Android
 * has no way to add nodes to another app's window, so this port injects a
 * transparent view into the game's own activity window and publishes the
 * bridge's control list as a tree of virtual nodes from it. Because the view
 * belongs to the game's window, TalkBack reads it with no accessibility
 * service to enable.
 *
 * <ul>
 *   <li>TalkBack's explore-by-touch hits the view, and the node under the
 *       finger gets accessibility focus;</li>
 *   <li>swipe left/right walks the controls in reading order;</li>
 *   <li>double-tap sends {@code ACTION_CLICK}, which taps the real control
 *       through the native bridge.</li>
 * </ul>
 *
 * Virtual node ids are the game's own control ids from the native snapshot,
 * never list positions: the tree is rebuilt twice a second and a position
 * would point at a different control by the time TalkBack double-taps it.
 * Stable ids keep activation and focus on the control the user touched.
 *
 * The view only consumes hover events; ordinary touches pass through to Unity
 * when TalkBack is off.
 */
final class SapOverlay {
    private static final int HOST_ID = View.NO_ID;

    interface Callbacks {
        void onActivate(int id);

        /** Real long press at the control, so hold gestures reach the game. */
        void onLongPress(int id);

        void onAccessibilityFocus(int id);

        void onSetText(int id, String text);

        void onCustomAction(int id, int action);
    }

    /** TalkBack custom actions, so taps are not the only path. Global shop
     * actions ride on every control; zone actions ride on their zone. */
    static final int ACTION_ROLL = 0x100001;
    static final int ACTION_FREEZE = 0x100002;
    static final int ACTION_SELL = 0x100003;
    static final int ACTION_END_TURN = 0x100004;
    static final int ACTION_DETAILS = 0x100005;
    static final int ACTION_BOARD = 0x100006;
    /** Move the shop pet picked up with the previous double-tap into this slot. */
    static final int ACTION_PLACE = 0x100007;

    private final Context context;
    private final Callbacks callbacks;

    private TreeView view;
    private List<SapNode> nodes = new ArrayList<>();
    private String title = "";
    private int shopGold = -1;
    private String shopPhase = "";

    void setShop(int gold, String phase) {
        shopGold = gold;
        shopPhase = phase == null ? "" : phase;
    }

    SapOverlay(Context context, Callbacks callbacks) {
        this.context = context;
        this.callbacks = callbacks;
    }

    /** The view to add to the game's content frame; created on first use. */
    View view() {
        if (view == null) view = new TreeView(context);
        return view;
    }

    boolean isShown() {
        return view != null && view.isAttachedToWindow();
    }

    /** Refresh the tree; TalkBack keeps its focus if the controls did not change.
     * Sends exactly one event: STATE_CHANGED when the screen title changed so
     * TalkBack enters the new tree, CONTENT_CHANGED otherwise. The controller
     * only calls this once the tree is stable, so no second timer lives here. */
    void update(List<SapNode> newNodes, String newTitle) {
        String before = signature(nodes, title);
        String after = signature(newNodes, newTitle);
        if (before.equals(after)) return;
        boolean isNewScreen = !newTitle.equals(title);
        nodes = new ArrayList<>(newNodes);
        title = newTitle;
        if (view != null) view.onTreeChanged(isNewScreen);
    }

    /** The control TalkBack is currently on, or -1; used to hold the tree while typing. */
    int focusedId() {
        return view == null ? -1 : view.focusedId;
    }

    /** Move TalkBack's focus, so arrow keys and the gamepad drive the tree too. */
    void requestFocus(int index) {
        if (view != null) view.focusNode(idAt(index));
    }

    /** Status line shown in the banner, e.g. while the bridge is still loading. */
    void setStatus(String text) {
        if (view != null) view.setStatus(text);
    }

    /** Spoken status through the active screen reader, no service required. */
    void announce(String text) {
        if (view != null && text != null && !text.isEmpty()) {
            view.announceForAccessibility(text);
        }
    }

    private int idAt(int index) {
        return (index >= 0 && index < nodes.size()) ? nodes.get(index).id : -1;
    }

    private int indexOf(int id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id == id) return i;
        }
        return -1;
    }

    private static String signature(List<SapNode> list, String title) {
        StringBuilder sb = new StringBuilder(title).append('|');
        for (SapNode node : list) sb.append(node.signature()).append('|');
        return sb.toString();
    }

    private final class TreeView extends FrameLayout {
        private final Provider provider = new Provider();
        private int focusedId = -1;
        private final TextView status;

        TreeView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            setFocusable(false);
            setClickable(false);
            setWillNotDraw(true);

            // Visible and readable proof that the overlay is attached, without
            // needing logcat or adb.
            status = new TextView(context);
            status.setTextColor(0xFFFFFFFF);
            status.setBackgroundColor(0x99000000);
            status.setTextSize(12);
            status.setPadding(24, 12, 24, 12);
            // Visual proof only: if TalkBack lands here the first swipe hits
            // the banner instead of the first control, burying the tree.
            status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(status, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START));
        }

        @Override
        public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return provider;
        }

        void setStatus(String text) {
            status.setText(text);
        }

        void onTreeChanged(boolean isNewScreen) {
            if (indexOf(focusedId) < 0) focusedId = -1;
            status.setText((title.isEmpty() ? "Super Auto Pets" : title)
                    + " - " + nodes.size() + " controls");
            invalidate();
            sendAccessibilityEvent(isNewScreen
                    ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    : AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    int id = nodeAt(event.getX(), event.getY());
                    if (id >= 0 && id != focusedId) focusNode(id);
                    return true;
                }
                default:
                    return super.onHoverEvent(event);
            }
        }

        private int nodeAt(float x, float y) {
            int width = getWidth() > 0 ? getWidth() : 1;
            int height = getHeight() > 0 ? getHeight() : 1;
            for (int i = 0; i < nodes.size(); i++) {
                int[] b = nodes.get(i).bounds(width, height);
                if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) return nodes.get(i).id;
            }
            return -1;
        }

        void focusNode(int id) {
            int index = indexOf(id);
            if (index < 0) return;
            focusedId = id;
            SapNode node = nodes.get(index);
            callbacks.onAccessibilityFocus(node.id);
            sendNodeEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, id);
        }

        private void sendNodeEvent(int type, int id) {
            int index = indexOf(id);
            if (index < 0) return;
            SapNode node = nodes.get(index);
            AccessibilityEvent event = AccessibilityEvent.obtain(type);
            event.setPackageName(context.getPackageName());
            event.setClassName(classNameFor(node));
            event.setContentDescription(describe(node));
            event.setEnabled(node.enabled);
            event.setSource(TreeView.this, id);
            ViewParent parent = getParent();
            if (parent != null) parent.requestSendAccessibilityEvent(TreeView.this, event);
        }

        private AccessibilityNodeInfo rootNode() {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(TreeView.this);
            onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.view.ViewGroup");
            info.setPackageName(context.getPackageName());
            info.setContentDescription(title.isEmpty() ? "Super Auto Pets" : title);
            // TalkBack drops a host whose own node looks invisible, taking every
            // virtual child with it, so state the host is on screen explicitly.
            info.setVisibleToUser(true);
            info.setEnabled(true);
            info.setFocusable(false);
            int rootWidth = getWidth() > 0 ? getWidth() : context.getResources().getDisplayMetrics().widthPixels;
            int rootHeight = getHeight() > 0 ? getHeight() : context.getResources().getDisplayMetrics().heightPixels;
            info.setBoundsInScreen(new Rect(0, 0, rootWidth, rootHeight));
            for (int i = 0; i < nodes.size(); i++) info.addChild(TreeView.this, nodes.get(i).id);
            return info;
        }

        private AccessibilityNodeInfo nodeInfo(int index) {
            if (index < 0 || index >= nodes.size()) return null;
            SapNode node = nodes.get(index);
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(TreeView.this, node.id);
            info.setPackageName(context.getPackageName());
            info.setClassName(classNameFor(node));
            info.setContentDescription(describe(node));
            info.setEnabled(node.enabled);
            info.setFocusable(true);
            info.setVisibleToUser(true);
            if (node.role.equals("text")) {
                info.setText(node.label);
            } else if (node.role.equals("input")) {
                // An edit box TalkBack can type into: the current game text is
                // the editable content, and typing sends ACTION_SET_TEXT.
                info.setText(node.value);
                info.setEditable(true);
                info.setClickable(node.enabled);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS);
            }
            boolean control = !node.role.equals("text");
            // Clickable even when the game reports the control as not
            // interactable: a control in a transient state (page swapping in,
            // selection not yet set) still accepts the tap, and with no CLICK
            // action TalkBack falls back to "double-tap and hold" instead of
            // activating. The tap itself decides success and speaks
            // "unavailable" when it fails.
            info.setClickable(control);
            if (node.role.equals("toggle")) {
                info.setCheckable(true);
                info.setChecked(node.checked);
            }
            int width = getWidth() > 0 ? getWidth() : context.getResources().getDisplayMetrics().widthPixels;
            int height = getHeight() > 0 ? getHeight() : context.getResources().getDisplayMetrics().heightPixels;
            int[] b = node.bounds(width, height);
            info.setBoundsInScreen(new Rect(b[0], b[1], b[2], b[3]));
            info.setBoundsInParent(new Rect(b[0], b[1], b[2], b[3]));
            // Sliders/scrollbars stay CLICK-only: the snapshot carries only a
            // formatted value string, never a min/max, so there is nothing
            // correct to advertise for progress gestures.
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            if (control) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                // TalkBack long-press falls back here when the gesture would
                // otherwise pass through the transparent host to the game.
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
                if ("petshop".equals(node.zone) || "foodshop".equals(node.zone)) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_FREEZE, "Freeze this item"));
                }
                if ("team".equals(node.zone)) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_SELL, "Sell this pet"));
                    // Where a pet goes is a drag in this game, so the slot needs
                    // an action of its own: pick a shop pet, then place it here.
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_PLACE, "Place held pet here"));
                }
            }
            // Global actions on every node, text included: these four do not
            // depend on the node, and they are what keeps Roll / End turn /
            // board status reachable on a screen whose own buttons are not
            // exposed (no laid-out control, page mid-swap).
            if (!node.role.equals("input")) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_ROLL, "Roll shop"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_END_TURN, "End turn"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_BOARD, "Read shop status"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_DETAILS, "Read details"));
            }
            info.setParent(TreeView.this);
            return info;
        }

        private final class Provider extends AccessibilityNodeProvider {
            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
                if (virtualViewId == HOST_ID) return rootNode();
                return nodeInfo(indexOf(virtualViewId));
            }

            @Override
            public boolean performAction(int virtualViewId, int action, Bundle arguments) {
                if (virtualViewId == HOST_ID) {
                    return performAccessibilityAction(action, arguments);
                }
                int index = indexOf(virtualViewId);
                if (index < 0) return false;
                SapNode node = nodes.get(index);
                switch (action) {
                    case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                        focusNode(node.id);
                        return true;
                    case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                        if (focusedId == node.id) {
                            focusedId = -1;
                            sendNodeEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, node.id);
                        }
                        return true;
                    case AccessibilityNodeInfo.ACTION_FOCUS:
                        focusNode(node.id);
                        return true;
                    case AccessibilityNodeInfo.ACTION_CLICK: {
                        focusedId = node.id;
                        callbacks.onActivate(node.id);
                        sendNodeEvent(AccessibilityEvent.TYPE_VIEW_CLICKED, node.id);
                        return true;
                    }
                    case AccessibilityNodeInfo.ACTION_LONG_CLICK: {
                        focusedId = node.id;
                        callbacks.onLongPress(node.id);
                        sendNodeEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, node.id);
                        return true;
                    }
                    case AccessibilityNodeInfo.ACTION_SET_TEXT: {
                        if (!node.role.equals("input") || !node.enabled) return false;
                        CharSequence seq = arguments != null
                                ? arguments.getCharSequence(
                                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE) : null;
                        String text = seq == null ? null : seq.toString();
                        if (text == null) return false;
                        callbacks.onSetText(node.id, text.toString());
                        return true;
                    }
                    case ACTION_ROLL:
                    case ACTION_FREEZE:
                    case ACTION_SELL:
                    case ACTION_PLACE:
                    case ACTION_END_TURN:
                    case ACTION_DETAILS:
                    case ACTION_BOARD: {
                        focusedId = node.id;
                        callbacks.onCustomAction(node.id, action);
                        sendNodeEvent(AccessibilityEvent.TYPE_VIEW_CLICKED, node.id);
                        return true;
                    }
                    default:
                        return false;
                }
            }

            @Override
            public AccessibilityNodeInfo findFocus(int focus) {
                if ((focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
                        || focus == AccessibilityNodeInfo.FOCUS_INPUT) && focusedId >= 0) {
                    return nodeInfo(indexOf(focusedId));
                }
                return null;
            }
        }
    }

    private static String classNameFor(SapNode node) {
        switch (node.role) {
            case "toggle":
                return "android.widget.Switch";
            case "slider":
            case "scrollbar":
                return "android.widget.SeekBar";
            case "input":
                return "android.widget.EditText";
            case "dropdown":
                return "android.widget.Spinner";
            case "text":
                return "android.widget.TextView";
            default:
                return "android.widget.Button";
        }
    }

    private String describe(SapNode node) {
        StringBuilder sb = new StringBuilder();
        String prefix = SapNode.zoneName(node.zone);
        if (!prefix.isEmpty()) sb.append(prefix).append(": ");
        sb.append(node.role.equals("input") && !node.value.isEmpty() ? node.value : node.label);
        if (!node.value.isEmpty() && !node.role.equals("input")) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(node.value);
        }
        // A shop or team pet has no label: the whole description sits in
        // detail. Skipping it because detail "contains" the empty label hid
        // every pet card from TalkBack.
        if (!node.detail.isEmpty()
                && (node.label.isEmpty() || !node.detail.contains(node.label))) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(node.detail);
        }
        // One rule for the controller and the tree: see ShopView.affordability.
        if ("shop".equals(shopPhase)) sb.append(ShopView.affordability(node, shopGold));
        if (!node.enabled) sb.append(", unavailable");
        if (sb.length() == 0) sb.append(node.role.equals("text") ? "Text" : "Control");
        return sb.toString();
    }
}
