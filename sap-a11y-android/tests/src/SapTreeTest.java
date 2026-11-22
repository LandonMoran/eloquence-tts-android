import app.sapa11y.extension.SapNode;
import app.sapa11y.extension.SapText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Contract tests for the tree layer the shop is read through.
 *
 * The game itself cannot run here, so these encode the parts of the shop that
 * the port owns: the node JSON the native side writes, the geometry a tap is
 * aimed with, and the way a shop slot is spoken. The layout fixture follows the
 * PC mod's arena shape (MinionShop 3 slots, SpellShop 1, MinionArmy 5, the
 * action bar), which is the specification this port is written against.
 */
public final class SapTreeTest {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        shopSnapshotKeepsEverySlot();
        zoneCountsMatchPcModLayout();
        noZonedNodeIsUnprojectable();
        truncatedSnapshotIsNoSnapshot();
        garbageIsNoSnapshot();
        boundsAreClampedToTheScreen();
        tapPointIsTheControlCentreNotTheClampedCentre();
        tapPointOfAnUnprojectableNodeStaysUnusable();
        petCardSpeaksItsNameNotItem();
        speechCarriesZoneAndPosition();
        textRoleSpeaksOnlyItsLabel();
        disabledNodeSpeaksUnavailable();
        withDetailKeepsGeometry();
        tinyControlGetsATapTarget();
        zoneNamesFollowThePcMod();
        spriteMarkupBecomesWords();
        camelCaseAndCounts();
        sentenceJoinDoesNotDoubleStop();

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) System.out.println("FAILED: " + failure);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ------------------------------------------------------------------ shop

    private static void shopSnapshotKeepsEverySlot() {
        List<SapNode> nodes = SapNode.parseList(shopJson());
        check("shop snapshot parses every node", nodes.size() == 20,
                "expected 20 nodes, got " + nodes.size());
        // Nine slots plus the action bar's Roll control.
        long zoned = nodes.stream().filter(n -> !n.zone.isEmpty()).count();
        check("every zoned control survives parsing", zoned == 10, "got " + zoned + " zoned nodes");
    }

    private static void zoneCountsMatchPcModLayout() {
        List<SapNode> nodes = SapNode.parseList(shopJson());
        check("three pet shop slots",
                countZone(nodes, "petshop") == 3, "got " + countZone(nodes, "petshop"));
        check("one food shop slot",
                countZone(nodes, "foodshop") == 1, "got " + countZone(nodes, "foodshop"));
        check("five team slots",
                countZone(nodes, "team") == 5, "got " + countZone(nodes, "team"));
        check("one action control",
                countZone(nodes, "actions") == 1, "got " + countZone(nodes, "actions"));
    }

    /**
     * The bug that emptied the shop twice: nodes reached the controller with the
     * -10000 "no rect" sentinel, so they were filtered out of the tree. The
     * native side now refuses to publish them, and this is the invariant.
     */
    private static void noZonedNodeIsUnprojectable() {
        for (SapNode node : SapNode.parseList(shopJson())) {
            if (node.zone.isEmpty()) continue;
            check("slot " + node.zone + " " + node.label + " has a usable rect",
                    node.x > -5000 && node.y > -5000 && node.width > 1 && node.height > 1,
                    "rect " + node.x + "," + node.y + " " + node.width + "x" + node.height);
        }
    }

    private static void truncatedSnapshotIsNoSnapshot() {
        String full = shopJson();
        String cut = full.substring(0, full.length() / 2);
        check("a half-written snapshot yields no nodes",
                SapNode.parseList(cut).isEmpty(),
                "got " + SapNode.parseList(cut).size() + " nodes from a truncated snapshot");
    }

    private static void garbageIsNoSnapshot() {
        check("non-JSON is no snapshot", SapNode.parseList("not json at all").isEmpty(), "parsed it");
        check("null is no snapshot", SapNode.parseList(null).isEmpty(), "parsed null");
        check("empty is no snapshot", SapNode.parseList("").isEmpty(), "parsed empty");
    }

    // -------------------------------------------------------------- geometry

    private static void boundsAreClampedToTheScreen() {
        SapNode card = new SapNode(11, "button", "", "", true, false, true,
                1000, 1212, 300, 400, "Otter, tier 3", "petshop");
        int[] b = card.bounds(1080, 2424);
        check("bounds clamp to the screen", Arrays.equals(b, new int[]{850, 1012, 1080, 1412}),
                Arrays.toString(b));
    }

    /**
     * Clamping the box used to move the tap point too, so a card at the edge was
     * tapped inside the screen but outside the card.
     */
    private static void tapPointIsTheControlCentreNotTheClampedCentre() {
        SapNode card = new SapNode(11, "button", "", "", true, false, true,
                1000, 1212, 300, 400, "Otter, tier 3", "petshop");
        int[] p = card.tapPoint();
        check("tap point is the control centre", p[0] == 1000 && p[1] == 1212,
                p[0] + "," + p[1]);
        int[] b = card.bounds(1080, 2424);
        int clampedCentre = (b[0] + b[2]) / 2;
        check("tap point is not the clamped box centre", p[0] != clampedCentre,
                "both were " + p[0]);
    }

    private static void tapPointOfAnUnprojectableNodeStaysUnusable() {
        SapNode dead = new SapNode(5, "button", "Store", "", true, false, true,
                -10000, -10000, 0, 0, "", "");
        int[] p = dead.tapPoint();
        check("an unplaced control keeps its sentinel tap point",
                p[0] <= -5000 && p[1] <= -5000, p[0] + "," + p[1]);
    }

    private static void tinyControlGetsATapTarget() {
        SapNode tiny = new SapNode(7, "button", "Roll", "", true, false, true,
                540, 2000, 0, 0, "", "actions");
        int[] b = tiny.bounds(1080, 2424);
        check("a sizeless control still gets a tap target",
                b[2] - b[0] >= 48 && b[3] - b[1] >= 48,
                (b[2] - b[0]) + "x" + (b[3] - b[1]));
    }

    // ---------------------------------------------------------------- speech

    private static void petCardSpeaksItsNameNotItem() {
        SapNode card = new SapNode(11, "button", "", "", true, false, true,
                500, 1200, 300, 400, "Otter, tier 3, level 2", "petshop");
        String speech = card.speech(1, 4);
        check("a pet card speaks its name", speech.startsWith("Pet shop: Otter"), speech);
        check("a pet card is not called item", !speech.contains("item"), speech);
    }

    private static void speechCarriesZoneAndPosition() {
        SapNode slot = new SapNode(12, "button", "Team slot 3", "", true, false, true,
                500, 1500, 200, 200, "", "team");
        String speech = slot.speech(3, 5);
        check("speech carries the zone", speech.startsWith("Team: Team slot 3"), speech);
        check("speech carries the position", speech.endsWith("3 of 5"), speech);
        check("speech calls a control a button", speech.contains("button"), speech);
    }

    private static void textRoleSpeaksOnlyItsLabel() {
        SapNode text = new SapNode(-1, "text", "Gold 3", "", true, false, false,
                100, 80, 120, 40, "", "");
        check("static text speaks only its text", "Gold 3".equals(text.speech(1, 20)),
                text.speech(1, 20));
    }

    private static void disabledNodeSpeaksUnavailable() {
        SapNode button = new SapNode(9, "button", "Sell", "", false, false, true,
                500, 2000, 200, 100, "", "actions");
        check("a disabled control says so", button.speech(1, 1).contains("unavailable"),
                button.speech(1, 1));
    }

    private static void withDetailKeepsGeometry() {
        SapNode card = new SapNode(11, "button", "", "", true, false, true,
                500, 1200, 300, 400, "Otter", "petshop");
        SapNode detailed = card.withDetail(". When sold, gives a random pet.");
        check("adding detail keeps the rect",
                detailed.x == card.x && detailed.y == card.y
                        && detailed.width == card.width && detailed.height == card.height,
                detailed.x + "," + detailed.y + " " + detailed.width + "x" + detailed.height);
        check("adding detail keeps the zone", "petshop".equals(detailed.zone), detailed.zone);
    }

    private static void zoneNamesFollowThePcMod() {
        check("pet shop zone name", "Pet shop".equals(SapNode.zoneName("petshop")),
                SapNode.zoneName("petshop"));
        check("food shop zone name", "Food shop".equals(SapNode.zoneName("foodshop")),
                SapNode.zoneName("foodshop"));
        check("team zone name", "Team".equals(SapNode.zoneName("team")),
                SapNode.zoneName("team"));
        check("actions zone name", "Actions".equals(SapNode.zoneName("actions")),
                SapNode.zoneName("actions"));
        check("bullies zone name", "Upcoming bullies".equals(SapNode.zoneName("bullies")),
                SapNode.zoneName("bullies"));
        check("unknown zone has no prefix", SapNode.zoneName("other").isEmpty(),
                SapNode.zoneName("other"));
    }

    // ------------------------------------------------------------------ text

    private static void spriteMarkupBecomesWords() {
        check("attack sprite becomes a word",
                "+2 attack".equals(SapText.clean("+2<sprite name=\"Attack\">")),
                SapText.clean("+2<sprite name=\"Attack\">"));
        check("health sprite becomes a word",
                "3 health".equals(SapText.clean("3<sprite name=Health>")),
                SapText.clean("3<sprite name=Health>"));
        check("unknown rich text tags are dropped",
                "Otter".equals(SapText.clean("<b>Otter</b>")), SapText.clean("<b>Otter</b>"));
        check("broken lines become spaces",
                "Otter tier 3".equals(SapText.clean("Otter<br>tier 3")),
                SapText.clean("Otter<br>tier 3"));
    }

    private static void camelCaseAndCounts() {
        check("screen names split", "User Settings".equals(SapText.splitCamelCase("UserSettings")),
                SapText.splitCamelCase("UserSettings"));
        check("singular count", "1 win".equals(SapText.count(1, "win", "wins")),
                SapText.count(1, "win", "wins"));
        check("plural count", "3 wins".equals(SapText.count(3, "win", "wins")),
                SapText.count(3, "win", "wins"));
    }

    private static void sentenceJoinDoesNotDoubleStop() {
        check("a full stop is not doubled",
                "Sold Otter. Bought Fish."
                        .equals(SapText.joinSentences(Arrays.asList("Sold Otter.", "Bought Fish."))),
                SapText.joinSentences(Arrays.asList("Sold Otter.", "Bought Fish.")));
        check("a missing stop is added between clauses",
                "Sold Otter. Bought Fish"
                        .equals(SapText.joinSentences(Arrays.asList("Sold Otter", "Bought Fish"))),
                SapText.joinSentences(Arrays.asList("Sold Otter", "Bought Fish")));
    }

    // --------------------------------------------------------------- helpers

    private static int countZone(List<SapNode> nodes, String zone) {
        int count = 0;
        for (SapNode node : nodes) if (zone.equals(node.zone)) count++;
        return count;
    }

    /** The exact node shape libsapa11y.so writes, in reading order. */
    private static String shopJson() {
        StringBuilder nodes = new StringBuilder();
        // Ten top-bar controls: scoreboard, gold, lives, turn, timer, tabs.
        String[] bar = {"Scoreboard", "Gold 3", "Lives 2 of 3", "Turn 5", "Timer 12", "Pets",
                "Food", "Toys", "Store", "Menu"};
        for (int i = 0; i < bar.length; i++) {
            append(nodes, node(-100 - i, "button", bar[i], "", "", 60 + i * 100, 80, 90, 60,
                    true, ""));
        }
        // Three pet shop slots, one food slot, five team slots, one action.
        String[] pets = {"Otter, tier 3, level 2", "Fish, tier 1, level 1",
                "Beaver, tier 1, level 1"};
        for (int i = 0; i < pets.length; i++) {
            append(nodes, node(200 + i, "button", "", pets[i], "petshop", 300 + i * 140, 700,
                    130, 180, true, ""));
        }
        append(nodes, node(300, "button", "Apple", "Tier 1, +1 health", "foodshop", 300, 950,
                130, 120, true, ""));
        for (int i = 0; i < 5; i++) {
            append(nodes, node(400 + i, "button", "Team slot " + (i + 1), "", "team",
                    200 + i * 150, 1500, 140, 160, true, ""));
        }
        append(nodes, node(900, "button", "Roll", "", "actions", 900, 2100, 200, 100, true, ""));
        return "{\"ready\":true,\"title\":\"Hangar\",\"nodes\":[" + nodes + "],\"shop\":{}}";
    }

    private static String node(int id, String role, String label, String detail, String zone,
                               int x, int y, int w, int h, boolean enabled, String value) {
        return "{\"id\":" + id + ",\"role\":\"" + role + "\",\"label\":\"" + label
                + "\",\"value\":\"" + value + "\",\"detail\":\"" + detail
                + "\",\"zone\":\"" + zone + "\",\"enabled\":" + enabled
                + ",\"checked\":false,\"focusable\":true,\"x\":" + x + ",\"y\":" + y
                + ",\"w\":" + w + ",\"h\":" + h + "}";
    }

    private static void append(StringBuilder sb, String node) {
        if (sb.length() > 0) sb.append(',');
        sb.append(node);
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add(what + " (" + detail + ")");
        }
    }
}
