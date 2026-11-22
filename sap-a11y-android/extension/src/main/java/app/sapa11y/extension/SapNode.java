package app.sapa11y.extension;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One control or line of static text from the game's current screen. */
public final class SapNode {
    public final int id;
    public final String role;
    public final String label;
    public final String value;
    public final boolean enabled;
    public final boolean checked;
    public final boolean focusable;
    /** Screen position and size of the control, for the accessibility tree. */
    public final float x;
    public final float y;
    public final float width;
    public final float height;
    public final String detail;
    /** Shop zone from the PC mod's ShopZone: petshop, foodshop, team, actions, or empty. */
    public final String zone;
    /**
     * The game's own numbers, or -1 when the bridge could not resolve them.
     * ItemModel.Price and ItemModel.Frozen are properties; MinionModel.SellValue
     * is a Nullable&lt;int&gt; field. Only a resolved member is published, so -1
     * means "unknown" and never "free" or "not frozen".
     */
    public final int price;
    public final int frozen;
    public final int sell;

    public SapNode(int id, String role, String label, String value,
                   boolean enabled, boolean checked, boolean focusable,
                   float x, float y, float width, float height, String detail, String zone) {
        this(id, role, label, value, enabled, checked, focusable, x, y, width, height, detail, zone,
                -1, -1, -1);
    }

    public SapNode(int id, String role, String label, String value,
                   boolean enabled, boolean checked, boolean focusable,
                   float x, float y, float width, float height, String detail, String zone,
                   int price, int frozen, int sell) {
        this.id = id;
        this.role = role;
        this.label = label;
        this.value = value;
        this.enabled = enabled;
        this.checked = checked;
        this.focusable = focusable;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.detail = detail;
        this.zone = zone == null ? "" : zone;
        this.price = price;
        this.frozen = frozen;
        this.sell = sell;
    }

    /** The same node with more description, for a missing ability line. */
    public SapNode withDetail(String extra) {
        return new SapNode(id, role, label, value, enabled, checked, focusable,
                x, y, width, height, detail + extra, zone, price, frozen, sell);
    }

    /**
     * ItemModel.Frozen as 1/0/-1. The bridge publishes it as a JSON boolean and
     * omits the key entirely when the member did not resolve, so an absent key
     * is unknown (-1), never "not frozen".
     */
    private static int frozenOf(JSONObject o) {
        Object value = o.opt("frozen");
        if (value instanceof Boolean) return ((Boolean) value) ? 1 : 0;
        if (value instanceof Number) return ((Number) value).intValue() != 0 ? 1 : 0;
        return -1;
    }

    public static List<SapNode> parseList(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<SapNode> nodes = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("nodes");
            if (array == null) return nodes;
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                nodes.add(new SapNode(
                        o.optInt("id"),
                        o.optString("role", "button"),
                        SapText.clean(o.optString("label", "")),
                        SapText.clean(o.optString("value", "")),
                        o.optBoolean("enabled", true),
                        o.optBoolean("checked", false),
                        o.optBoolean("focusable", true),
                        (float) o.optDouble("x", 0),
                        (float) o.optDouble("y", 0),
                        (float) o.optDouble("w", 0),
                        (float) o.optDouble("h", 0),
                        o.optString("detail", ""),
                        o.optString("zone", ""),
                        o.optInt("price", -1),
                        frozenOf(o),
                        o.optInt("sell", -1)));
            }
        } catch (JSONException ignored) {
            // A half-written snapshot is no snapshot. Returning the nodes parsed
            // so far published a truncated tree (the first two shop cards and
            // nothing else) as if it were the whole screen.
            return Collections.emptyList();
        }
        return nodes;
    }

    public static String title(String json) {
        try {
            return new JSONObject(json).optString("title", "");
        } catch (JSONException e) {
            return "";
        }
    }

    public static boolean ready(String json) {
        try {
            return new JSONObject(json).optBoolean("ready", false);
        } catch (JSONException e) {
            return false;
        }
    }

    /** What the screen reader says for this control. */
    public String speech(int index, int total) {
        StringBuilder sb = new StringBuilder();
        String prefix = zoneName(zone);
        if (!prefix.isEmpty()) sb.append(prefix).append(": ");
        // A pet card carries no label; its name is the start of the detail
        // line, so calling it "item" only got in the way.
        String name = label.isEmpty() ? (detail.isEmpty() ? "item" : "") : label;
        if (!name.isEmpty()) sb.append(name);
        if (!value.isEmpty() && (name.isEmpty() || !value.contains(name))) {
            appendClause(sb, value);
        }
        if (!detail.isEmpty() && (name.isEmpty() || !detail.contains(name))) {
            appendClause(sb, detail);
        }
        if (!enabled) sb.append(", unavailable");
        switch (role) {
            case "toggle":
                break; // the value already says checked / not checked
            case "slider":
                sb.append(", slider");
                break;
            case "input":
                sb.append(", edit box");
                break;
            case "dropdown":
                sb.append(", drop-down");
                break;
            case "text":
                return name;
            default:
                sb.append(", button");
        }
        if (total > 1) sb.append(", ").append(index).append(" of ").append(total);
        return sb.toString();
    }

    /**
     * Bounds on screen for a virtual accessibility node. Unity's UGUI reports
     * the RectTransform size in pixels; the position is the pivot, so the box is
     * centred on it. Controls without a usable rect get a minimum tap target.
     */
    public int[] bounds(int screenWidth, int screenHeight) {
        float boxWidth = width > 1 ? Math.min(width, screenWidth) : 160;
        float boxHeight = height > 1 ? Math.min(height, screenHeight) : 48;
        int left = Math.round(x - boxWidth / 2f);
        int top = Math.round(y - boxHeight / 2f);
        int right = left + Math.round(boxWidth);
        int bottom = top + Math.round(boxHeight);
        // Clamping the box into the screen also moves its centre, and the centre
        // is where a tap lands: a card hanging off the edge would be tapped at a
        // point inside the screen but outside the card. Only the reported bounds
        // are clamped; the tap point has to come from the unclamped box.
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.max(left + 1, Math.min(right, screenWidth));
        bottom = Math.max(top + 1, Math.min(bottom, screenHeight));
        return new int[]{left, top, right, bottom};
    }

    /** The control's own centre, unclamped, so a tap aims at the card not the screen. */
    public int[] tapPoint() {
        return new int[]{Math.round(x), Math.round(y)};
    }

    public String signature() {
        return id + ":" + role + ":" + label + ":" + value + ":" + detail + ":" + zone + ":" + enabled + ":" + checked;
    }

    /** Same identity without the editable value, so typing does not look like a new screen. */
    public String structureSignature() {
        return id + ":" + role + ":" + label + ":" + detail + ":" + zone + ":" + enabled + ":" + checked;
    }

    /**
     * Adds a clause without the stray comma an empty label used to leave behind:
     * a pet card has no label, so its speech read "Pet shop: , Otter".
     */
    private static void appendClause(StringBuilder sb, String text) {
        if (text == null || text.isEmpty()) return;
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(", ");
        sb.append(text);
    }

    /** Spoken zone header, the PC mod's S/F/T/D zone names. */
    public static String zoneName(String zone) {
        if (zone == null) return "";
        switch (zone) {
            case "petshop": return "Pet shop";
            case "foodshop": return "Food shop";
            case "team": return "Team";
            case "actions": return "Actions";
            case "bullies": return "Upcoming bullies";
            default: return "";
        }
    }
}
