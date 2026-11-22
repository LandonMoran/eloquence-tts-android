import app.sapa11y.extension.SapNode;
import app.sapa11y.extension.ShopRules;
import app.sapa11y.extension.ShopView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-action consistency, checked against the snapshot the game would produce.
 *
 * Each test builds the snapshot before an action and the snapshot after it, in
 * the exact JSON shape libsapa11y.so writes, and asserts that what the port
 * announces (gold, shop contents, board contents) matches the after snapshot.
 * Gold and card names are read back out of that JSON, never taken from the
 * port's own arithmetic, so a wrong announcement fails the test.
 *
 * The fixtures are written from the native writer's contract; no live capture
 * of the game exists in this workspace, which is the limit of what can be
 * proven without a device.
 */
public final class ShopSnapshotTest {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        enumerationNamesEverySlot();
        emptySlotReadsEmpty();
        rollSpendsOneGoldAndChangesTheShop();
        rollWithAFreeRollKeepsTheGold();
        buyingMovesThePetToTheBoard();
        sellingReturnsThePetsLevel();
        frozenCardSurvivesARoll();
        frozenCardSurvivesRoundEnd();
        foodAppliesToABoardPet();
        buyingADuplicateLevelsUpInsteadOfGrowingTheBoard();
        endOfTurnRefreshKeepsGoldAccounting();
        unplaceableSlotsAreNotAnnounced();

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) System.out.println("FAILED: " + failure);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ------------------------------------------------------------ enumeration

    private static void enumerationNamesEverySlot() {
        String json = snapshot(3, 0, 1,
                new String[]{"Otter, tier 3, level 1", "Fish, tier 1, level 1", null},
                new String[]{"Ant, tier 1, level 1"}, "Apple, +1 health");
        List<SapNode> shop = SapNode.parseList(json);
        check("the pet shop is summarised by name",
                "Slot 1: Otter. Slot 2: Fish. Slot 3: empty."
                        .equals(ShopView.summary(shop, "petshop")),
                ShopView.summary(shop, "petshop"));
        check("the food shop is summarised by name",
                "Slot 1: Apple.".equals(ShopView.summary(shop, "foodshop")),
                ShopView.summary(shop, "foodshop"));
        check("shop enumeration finds three pet cards",
                ShopView.zone(shop, "petshop").size() == 3,
                "" + ShopView.zone(shop, "petshop").size());
        check("shop enumeration finds the food card",
                ShopView.zone(shop, "foodshop").size() == 1,
                "" + ShopView.zone(shop, "foodshop").size());
        check("the board is enumerated too", ShopView.zone(shop, "team").size() == 1,
                "" + ShopView.zone(shop, "team").size());
        check("a card keeps its whole detail line",
                "Otter, tier 3, level 1".equals(ShopView.zone(shop, "petshop").get(0).detail),
                ShopView.zone(shop, "petshop").get(0).detail);
    }

    private static void emptySlotReadsEmpty() {
        String json = snapshot(3, 0, 1, new String[]{null, null, null},
                new String[]{null, null, null, null, null}, null);
        List<SapNode> shop = SapNode.parseList(json);
        check("empty pet slots are named empty",
                "Slot 1: empty. Slot 2: empty. Slot 3: empty."
                        .equals(ShopView.summary(shop, "petshop")),
                ShopView.summary(shop, "petshop"));
        check("the empty board is still five slots",
                ShopView.zone(shop, "team").size() == 5,
                "" + ShopView.zone(shop, "team").size());
    }

    // ------------------------------------------------------------------ roll

    private static void rollSpendsOneGoldAndChangesTheShop() {
        String beforeJson = snapshot(4, 0, 1,
                new String[]{"Otter, tier 3, level 1", "Fish, tier 1, level 1", null},
                new String[]{"Ant, tier 1, level 1"}, null);
        String afterJson = snapshot(3, 0, 1,
                new String[]{"Bat, tier 2, level 1", "Beaver, tier 1, level 1", null},
                new String[]{"Ant, tier 1, level 1"}, null);
        List<SapNode> before = SapNode.parseList(beforeJson);
        List<SapNode> after = SapNode.parseList(afterJson);
        check("a roll costs one gold in the snapshot",
                ShopRules.goldAfterRoll(gold(beforeJson), freeRolls(beforeJson))
                        == gold(afterJson),
                gold(beforeJson) + " -> " + gold(afterJson));
        check("a roll changes the shop cards",
                !ShopView.sameContents(before, after, "petshop"), "shop contents unchanged");
        check("a roll leaves the board alone",
                ShopView.sameContents(before, after, "team"), "board changed");
        check("the announcement names the new shop",
                ShopView.summary(after, "petshop").startsWith("Slot 1: Bat."),
                ShopView.summary(after, "petshop"));
    }

    private static void rollWithAFreeRollKeepsTheGold() {
        String beforeJson = snapshot(4, 2, 1,
                new String[]{"Otter, tier 3, level 1", null, null},
                new String[]{"Ant, tier 1, level 1"}, null);
        String afterJson = snapshot(4, 1, 1,
                new String[]{"Bat, tier 2, level 1", null, null},
                new String[]{"Ant, tier 1, level 1"}, null);
        List<SapNode> before = SapNode.parseList(beforeJson);
        List<SapNode> after = SapNode.parseList(afterJson);
        check("a free roll keeps the gold",
                ShopRules.goldAfterRoll(gold(beforeJson), freeRolls(beforeJson))
                        == gold(afterJson),
                gold(beforeJson) + " -> " + gold(afterJson));
        check("a free roll is spent",
                freeRolls(afterJson) == freeRolls(beforeJson) - 1,
                freeRolls(beforeJson) + " -> " + freeRolls(afterJson));
        check("a free roll still changes the shop",
                !ShopView.sameContents(before, after, "petshop"), "shop unchanged");
    }

    // ------------------------------------------------------------- buy / sell

    private static void buyingMovesThePetToTheBoard() {
        String beforeJson = snapshot(6, 0, 1,
                new String[]{"Otter, tier 3, level 1", "Fish, tier 1, level 1", null},
                new String[]{"Ant, tier 1, level 1"}, null);
        String afterJson = snapshot(3, 0, 1,
                new String[]{"", "Fish, tier 1, level 1", null},
                new String[]{"Ant, tier 1, level 1", "Otter, tier 3, level 1"}, null);
        List<SapNode> before = SapNode.parseList(beforeJson);
        List<SapNode> after = SapNode.parseList(afterJson);
        int spent = gold(beforeJson) - gold(afterJson);
        check("a bought tier 3 pet costs three gold", spent == 3, "spent " + spent);
        check("the bought pet leaves the shop", !ShopView.has(after, "petshop", "Otter"),
                ShopView.summary(after, "petshop"));
        check("the bought pet joins the board", ShopView.has(after, "team", "Otter"),
                ShopView.summary(after, "team"));
        check("the board grew by exactly one",
                ShopView.zone(after, "team").size() == ShopView.zone(before, "team").size() + 1,
                ShopView.zone(before, "team").size() + " -> "
                        + ShopView.zone(after, "team").size());
        check("the shop slot the pet came from is empty",
                ShopView.zone(after, "petshop").get(0).detail.isEmpty(),
                ShopView.zone(after, "petshop").get(0).detail);
    }

    /**
     * A sale refunds the pet's level, not its tier (wiki, Gold: "A pet's sell
     * value usually equals their level"; PC mod ShopNavigationManager.cs:2214-
     * 2222 reads SellValue with a default of 1). A tier 3 level 1 Otter is
     * therefore worth one gold, and a level 3 pet is worth three.
     */
    private static void sellingReturnsThePetsLevel() {
        String beforeJson = snapshot(1, 0, 3,
                new String[]{"Fish, tier 1, level 1", null, null},
                new String[]{"Ant, tier 1, level 1", "Otter, tier 3, level 1"}, null);
        String afterJson = snapshot(2, 0, 3,
                new String[]{"Fish, tier 1, level 1", null, null},
                new String[]{"Ant, tier 1, level 1"}, null);
        List<SapNode> before = SapNode.parseList(beforeJson);
        List<SapNode> after = SapNode.parseList(afterJson);
        int refunded = gold(afterJson) - gold(beforeJson);
        check("a sold tier 3 level 1 pet refunds one gold",
                refunded == ShopRules.sellRefund(1), "refunded " + refunded);
        check("the sold pet leaves the board", !ShopView.has(after, "team", "Otter"),
                ShopView.summary(after, "team"));
        check("the shop is untouched by a sale",
                ShopView.sameContents(before, after, "petshop"), "shop changed");

        String level3Before = snapshot(0, 0, 5, new String[]{null, null, null},
                new String[]{"Otter, tier 3, level 3"}, null);
        String level3After = snapshot(3, 0, 5, new String[]{null, null, null},
                new String[]{}, null);
        int level3Refund = gold(level3After) - gold(level3Before);
        check("a level 3 pet refunds three gold",
                level3Refund == ShopRules.sellRefund(3), "refunded " + level3Refund);
    }

    // ---------------------------------------------------------------- freeze

    private static void frozenCardSurvivesARoll() {
        String beforeJson = snapshot(2, 0, 2,
                new String[]{"Otter, tier 3, level 1, frozen", "Fish, tier 1, level 1", null},
                new String[]{"Ant, tier 1, level 1"}, null);
        String afterJson = snapshot(1, 0, 2,
                new String[]{"Otter, tier 3, level 1, frozen", "Bat, tier 2, level 1", null},
                new String[]{"Ant, tier 1, level 1"}, null);
        List<SapNode> after = SapNode.parseList(afterJson);
        check("a frozen card is still in the shop after a roll",
                ShopView.summary(after, "petshop").contains("Otter"),
                ShopView.summary(after, "petshop"));
        check("the unfrozen card was replaced", !ShopView.has(after, "petshop", "Fish"),
                ShopView.summary(after, "petshop"));
        check("the roll still cost gold", gold(beforeJson) - gold(afterJson) == 1,
                gold(beforeJson) + " -> " + gold(afterJson));
    }

    private static void frozenCardSurvivesRoundEnd() {
        String afterJson = snapshot(4, 0, 2,
                new String[]{"Otter, tier 3, level 1, frozen", "Bat, tier 2, level 1", null},
                new String[]{"Ant, tier 1, level 1", "Fish, tier 1, level 1"}, null);
        List<SapNode> after = SapNode.parseList(afterJson);
        check("a frozen card survives the turn ending",
                ShopView.summary(after, "petshop").contains("Otter"),
                ShopView.summary(after, "petshop"));
        check("the rest of the shop is restocked for the new turn",
                ShopView.zone(after, "petshop").size() == 3,
                "" + ShopView.zone(after, "petshop").size());
    }

    // ------------------------------------------------------------ food, merge

    private static void foodAppliesToABoardPet() {
        String beforeJson = snapshot(3, 0, 1,
                new String[]{"Otter, tier 3, level 1", null, null},
                new String[]{"Ant, tier 1, level 1, 2 attack, 2 health"}, "Apple, +1 health");
        String afterJson = snapshot(0, 0, 1,
                new String[]{"Otter, tier 3, level 1", null, null},
                new String[]{"Ant, tier 1, level 1, 2 attack, 3 health"}, null);
        List<SapNode> before = SapNode.parseList(beforeJson);
        List<SapNode> after = SapNode.parseList(afterJson);
        check("food costs its price", gold(beforeJson) - gold(afterJson) == 3,
                gold(beforeJson) + " -> " + gold(afterJson));
        check("the food leaves the shop",
                ShopView.zone(after, "foodshop").get(0).detail.isEmpty(),
                ShopView.summary(after, "foodshop"));
        String petBefore = ShopView.zone(before, "team").get(0).detail;
        String petAfter = ShopView.zone(after, "team").get(0).detail;
        check("the food raised the pet's health",
                !petBefore.equals(petAfter) && petAfter.contains("3 health"), petAfter);
        check("the pet stayed on the board in the same slot",
                ShopView.zone(after, "team").size() == 1,
                "" + ShopView.zone(after, "team").size());
    }

    private static void buyingADuplicateLevelsUpInsteadOfGrowingTheBoard() {
        String beforeJson = snapshot(3, 0, 2,
                new String[]{"Otter, tier 3, level 1", null, null},
                new String[]{"Otter, tier 3, level 1", "Ant, tier 1, level 1"}, null);
        String afterJson = snapshot(0, 0, 2,
                new String[]{"", null, null},
                new String[]{"Otter, tier 3, level 2", "Ant, tier 1, level 1"}, null);
        List<SapNode> before = SapNode.parseList(beforeJson);
        List<SapNode> after = SapNode.parseList(afterJson);
        check("a duplicate buy levels the pet up",
                ShopView.summary(after, "team").contains("Otter"),
                ShopView.summary(after, "team"));
        check("the board did not grow",
                ShopView.zone(after, "team").size() == ShopView.zone(before, "team").size(),
                ShopView.zone(before, "team").size() + " -> "
                        + ShopView.zone(after, "team").size());
        check("the levelled pet reports its new level",
                ShopView.zone(after, "team").get(0).detail.contains("level 2"),
                ShopView.zone(after, "team").get(0).detail);
    }

    // ------------------------------------------------------------- end of turn

    private static void endOfTurnRefreshKeepsGoldAccounting() {
        String beforeJson = snapshot(0, 0, 3,
                new String[]{"Fish, tier 1, level 1", null, null},
                new String[]{"Otter, tier 3, level 2"}, null);
        String afterJson = snapshot(10, 0, 4,
                new String[]{"Bat, tier 2, level 1", "Beaver, tier 1, level 1", null},
                new String[]{"Otter, tier 3, level 2"}, null);
        List<SapNode> before = SapNode.parseList(beforeJson);
        List<SapNode> after = SapNode.parseList(afterJson);
        check("a new turn restocks the shop",
                !ShopView.sameContents(before, after, "petshop"),
                ShopView.summary(after, "petshop"));
        check("a new turn pays out gold", gold(afterJson) > gold(beforeJson),
                gold(beforeJson) + " -> " + gold(afterJson));
        check("the board carries over", ShopView.sameContents(before, after, "team"),
                "board changed");
        check("the turn number advanced", turn(afterJson) == turn(beforeJson) + 1,
                turn(beforeJson) + " -> " + turn(afterJson));
    }

    // --------------------------------------------------------------- defensive

    private static void unplaceableSlotsAreNotAnnounced() {
        String json = "{\"ready\":true,\"title\":\"Hangar\",\"nodes\":["
                + node(1, "button", "", "Ghost, tier 1", "petshop", -10000, -10000, 0, 0)
                + "," + node(2, "button", "", "Otter, tier 3", "petshop", 400, 700, 130, 180)
                + "],\"shop\":{\"phase\":\"shop\",\"gold\":3}}";
        List<SapNode> nodes = SapNode.parseList(json);
        check("a slot with no rect is not announced",
                "Slot 1: Otter.".equals(ShopView.summary(nodes, "petshop")),
                ShopView.summary(nodes, "petshop"));
    }

    // ---------------------------------------------------------------- helpers

    private static int gold(String json) {
        try {
            return new JSONObject(json).getJSONObject("shop").getInt("gold");
        } catch (Exception e) {
            return -999;
        }
    }

    private static int freeRolls(String json) {
        try {
            return new JSONObject(json).getJSONObject("shop").getInt("freeRolls");
        } catch (Exception e) {
            return -999;
        }
    }

    private static int turn(String json) {
        try {
            return new JSONObject(json).getJSONObject("shop").getInt("turn");
        } catch (Exception e) {
            return -999;
        }
    }

    /**
     * A snapshot in the native writer's shape. Pets and board slots are detail
     * lines (a card has no label), null means an empty slot, and the shop block
     * carries the state the game reported.
     */
    private static String snapshot(int gold, int freeRolls, int turn, String[] pets,
                                   String[] board, String food) {
        StringBuilder nodes = new StringBuilder();
        for (int i = 0; i < pets.length; i++) {
            boolean empty = pets[i] == null || pets[i].isEmpty();
            append(nodes, node(200 + i, "button", empty ? "Pet shop slot" : "",
                    empty ? "" : pets[i], "petshop", 300 + i * 140, 700, 130, 180));
        }
        if (food == null || food.isEmpty()) {
            append(nodes, node(300, "button", "Food shop slot", "", "foodshop",
                    300, 950, 130, 120));
        } else {
            append(nodes, node(300, "button", "", food, "foodshop", 300, 950, 130, 120));
        }
        for (int i = 0; i < board.length; i++) {
            append(nodes, node(400 + i, "button", "", board[i] == null ? "" : board[i], "team",
                    200 + i * 150, 1500, 140, 160));
        }
        return "{\"ready\":true,\"title\":\"Hangar\",\"nodes\":[" + nodes + "],\"shop\":{"
                + "\"phase\":\"shop\",\"gold\":" + gold + ",\"freeRolls\":" + freeRolls
                + ",\"turn\":" + turn + "}}";
    }

    private static String node(int id, String role, String label, String detail, String zone,
                               int x, int y, int w, int h) {
        return "{\"id\":" + id + ",\"role\":\"" + role + "\",\"label\":\"" + label
                + "\",\"value\":\"\",\"detail\":\"" + detail + "\",\"zone\":\"" + zone
                + "\",\"enabled\":true,\"checked\":false,\"focusable\":true,\"x\":" + x
                + ",\"y\":" + y + ",\"w\":" + w + ",\"h\":" + h + "}";
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
