package app.sapa11y.extension;

import java.util.ArrayList;
import java.util.List;

/**
 * A shop or board read straight out of a snapshot.
 *
 * Everything the port says after an action comes from here, so what it claims
 * can be checked against the snapshot the game produced rather than against a
 * value the port worked out for itself.
 */
public final class ShopView {
    private ShopView() {}

    /** Controls in one zone, in reading order, without ones that cannot be placed. */
    public static List<SapNode> zone(List<SapNode> nodes, String zone) {
        List<SapNode> out = new ArrayList<>();
        if (nodes == null) return out;
        for (SapNode node : nodes) {
            if (!zone.equals(node.zone)) continue;
            if (node.x <= -5000 || node.y <= -5000) continue;
            out.add(node);
        }
        return out;
    }

    /** The card's name: its label, else the first clause of its detail, else empty. */
    public static String slotName(SapNode node) {
        if (node == null) return "empty";
        if (!node.detail.isEmpty()) {
            String first = node.detail.split("[,.]")[0].trim();
            if (!first.isEmpty() && !isPlaceholder(first)) return first;
        }
        if (!node.label.isEmpty() && !isPlaceholder(node.label)) return node.label;
        return "empty";
    }

    /**
     * The native side names an empty slot after the slot itself ("Pet shop
     * slot", "Team slot 2"), so a summary built from labels alone read
     * "Slot 3: Pet shop slot" or announced an empty board slot as a pet called
     * "Team slot 2". Those names mean empty.
     *
     * The trailing slot number is what the old check missed: "team slot 2"
     * does not end with "team slot", so every numbered team slot escaped.
     */
    private static boolean isPlaceholder(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT).trim();
        lower = lower.replaceAll("\\s+\\d+$", "").trim();
        return lower.endsWith("shop slot") || lower.endsWith("team slot")
                || lower.equals("empty") || lower.startsWith("empty ");
    }

    /** "Slot 1: Otter. Slot 2: empty." for one zone. */
    public static String summary(List<SapNode> nodes, String zone) {
        StringBuilder sb = new StringBuilder();
        int slot = 0;
        for (SapNode node : zone(nodes, zone)) {
            slot++;
            if (sb.length() > 0) sb.append(' ');
            sb.append("Slot ").append(slot).append(": ").append(slotName(node)).append('.');
        }
        return sb.toString();
    }

    /** True when the same slots hold the same named things in the same order. */
    public static boolean sameContents(List<SapNode> before, List<SapNode> after, String zone) {
        List<SapNode> left = zone(before, zone);
        List<SapNode> right = zone(after, zone);
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!slotName(left.get(i)).equals(slotName(right.get(i)))) return false;
        }
        return true;
    }

    /** True when the zone holds a slot with this name. */
    public static boolean has(List<SapNode> nodes, String zone, String name) {
        for (SapNode node : zone(nodes, zone)) {
            if (slotName(node).equals(name)) return true;
        }
        return false;
    }

    /**
     * True for a slot with nothing in it. The native side names an empty slot
     * after the slot itself ("Pet shop slot", "Food shop slot"), so a check for
     * a label starting with "Empty" never matched and an empty card was
     * announced as costing gold.
     */
    public static boolean isEmptySlot(SapNode node) {
        return node == null || slotName(node).equals("empty");
    }

    /** The names in one zone, in reading order, one per slot, empties included. */
    public static List<String> zoneNames(List<SapNode> nodes, String zone) {
        List<String> out = new ArrayList<>();
        for (SapNode node : zone(nodes, zone)) out.add(slotName(node));
        return out;
    }

    /**
     * The price the PC mod assumes when the game's own price cannot be read:
     * PetStatsReader.ReadMinionBrief reads `minion.Price` with a default of 3
     * (PetStatsReader.cs:113-118). Since ItemModel.Price (dump.cs:555387) is
     * resolved through the bridge this is only the unresolved-member fallback.
     */
    public static final int ITEM_PRICE = 3;

    /**
     * The board-status line, in the PC mod's order and join.
     *
     * PetStatsReader.ReadBoardStatus (SuperAutoAccessibility/Gameplay/
     * PetStatsReader.cs:302-344) appends turn, gold, free rolls, tier, lives,
     * wins, losses and moustaches to a list only when each could be read, then
     * joins with ", ". Unknown values are therefore left out instead of being
     * printed as -1, and a field cannot start the line with a separator.
     */
    public static String statusLine(int turn, int gold, int freeRolls, int tier, int lives,
                                    int livesMax, int wins, int losses, int moustaches) {
        StringBuilder sb = new StringBuilder();
        if (turn >= 0) appendPart(sb, "Turn " + turn);
        if (gold >= 0) appendPart(sb, gold + " gold");
        if (freeRolls > 0) appendPart(sb, freeRolls == 1 ? "1 free roll" : freeRolls + " free rolls");
        if (tier >= 0) appendPart(sb, "tier " + tier);
        if (lives >= 0) {
            appendPart(sb, livesMax >= 0 ? lives + " of " + livesMax + " lives" : lives + " lives");
        }
        if (wins >= 0) appendPart(sb, wins == 1 ? "1 win" : wins + " wins");
        if (losses >= 0) appendPart(sb, losses == 1 ? "1 loss" : losses + " losses");
        if (moustaches >= 0) {
            appendPart(sb, moustaches == 1 ? "1 moustache" : moustaches + " moustaches");
        }
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(part);
    }

    /**
     * What a shop card costs, whether the player can pay, whether it is frozen,
     * and what a team pet is worth, or empty when there is nothing to add.
     *
     * The numbers come from the game: ItemModel.Price (dump.cs:555387) for shop
     * cards, ItemModel.Frozen (dump.cs:555389) for the frozen marker and
     * MinionModel.SellValue (dump.cs:551320) for a team pet's refund. Only when
     * the bridge could not resolve a member (-1) does the port fall back to the
     * PC mod's flat price (PetStatsReader.cs:113-118) or say nothing about a
     * sale. An empty slot says nothing, and unknown gold is never a reason to
     * refuse.
     */
    public static String affordability(SapNode node, int gold) {
        if (node == null || isEmptySlot(node)) return "";
        if ("team".equals(node.zone)) {
            return node.sell >= 0
                    ? ", sells for " + ShopRules.sellRefund(0, node.sell) + " gold"
                    : "";
        }
        if (gold < 0) return "";
        String frozen = node.frozen == 1 ? ", frozen" : "";
        if ("petshop".equals(node.zone)) {
            int price = node.price >= 0 ? node.price : ITEM_PRICE;
            return (gold >= price
                    ? ", costs " + price + " gold, affordable"
                    : ", costs " + price + " gold, not enough gold") + frozen;
        }
        if ("foodshop".equals(node.zone)) {
            if (node.price >= 0) {
                return (gold >= node.price
                        ? ", costs " + node.price + " gold, affordable"
                        : ", costs " + node.price + " gold, not enough gold") + frozen;
            }
            if (gold <= 0) return ", not enough gold" + frozen;
            return ", " + gold + " gold left" + frozen;
        }
        return "";
    }
}
