import app.sapa11y.extension.BoardView;
import app.sapa11y.extension.SapNode;
import app.sapa11y.extension.ShopView;

import java.util.List;

/**
 * Board-state extraction and ordering, as the pure layer sees it.
 *
 * Nothing here touches the game: it feeds the exact strings and node JSON that
 * libsapa11y.so produces, and checks what the port reads back out. The rules
 * asserted are the platform-independent ones, cited where they come from:
 *
 * - the battle team line is "Name attack/health" per pet, comma separated with
 *   no space (native/src/sapa11y.cpp, team_line);
 * - the status line is the PC mod's ReadBoardStatus order and join
 *   (SuperAutoAccessibility/Gameplay/PetStatsReader.cs:302-344);
 * - a shop item's price, frozen state and a team pet's sell value come from the
 *   game's own members (ItemModel.Price dump.cs:555387, ItemModel.Frozen
 *   dump.cs:555389, MinionModel.SellValue dump.cs:551320); the PC mod's flat 3
 *   gold (PetStatsReader.cs:113-118) is only the unresolved-member fallback.
 */
public final class ShopBoardTest {
    private static int passed;
    private static final java.util.List<String> failures = new java.util.ArrayList<>();

    public static void main(String[] args) {
        battleLineReadsInSlotOrder();
        namesWithSpacesSurvive();
        missingStatsReadAsUnknown();
        emptyEntriesAreDropped();
        battleStartLineDropsTheStats();
        slotHurtCoversDamageFaintsAndSwaps();
        zoneNamesKeepSlotOrderAndEmpties();
        emptySlotsAreRecognisedByPlaceholder();
        statusLineFollowsThePcOrder();
        statusLineNeverStartsWithASeparator();
        affordabilityFollowsTheFlatPrice();
        priceAndFrozenComeFromTheModel();
        sellValueComesFromTheModel();
        itemFactsAreParsedFromTheSnapshot();
        summaryNumbersSlotsInTheOrderGiven();

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) System.out.println("FAILED: " + failure);
        if (!failures.isEmpty()) System.exit(1);
    }

    // ------------------------------------------------------------ battle line

    private static void battleLineReadsInSlotOrder() {
        String line = "Flea 3/2,Beaver 2/2,Duck 1/3";
        String[] entries = BoardView.entries(line);
        check("a battle line splits into three pets", entries.length == 3, "" + entries.length);
        check("slot 1 name", "Flea".equals(BoardView.name(entries[0])), BoardView.name(entries[0]));
        check("slot 1 attack", BoardView.attack(entries[0]) == 3, "" + BoardView.attack(entries[0]));
        check("slot 1 health", BoardView.health(entries[0]) == 2, "" + BoardView.health(entries[0]));
        check("slot 2 name", "Beaver".equals(BoardView.name(entries[1])), BoardView.name(entries[1]));
        check("slot 3 health", BoardView.health(entries[2]) == 3, "" + BoardView.health(entries[2]));
    }

    private static void namesWithSpacesSurvive() {
        String[] entries = BoardView.entries("Golden Retriever 4/5,Groundhog 2/3");
        check("a two-word pet keeps its whole name",
                "Golden Retriever".equals(BoardView.name(entries[0])),
                BoardView.name(entries[0]));
        check("its stats are still read",
                BoardView.attack(entries[0]) == 4 && BoardView.health(entries[0]) == 5,
                BoardView.attack(entries[0]) + "/" + BoardView.health(entries[0]));
        check("the next pet is not shifted", "Groundhog".equals(BoardView.name(entries[1])),
                BoardView.name(entries[1]));
    }

    private static void missingStatsReadAsUnknown() {
        check("a name without stats is all name",
                "Otter".equals(BoardView.name("Otter")), BoardView.name("Otter"));
        check("a name without stats has unknown attack",
                BoardView.attack("Otter") == -1, "" + BoardView.attack("Otter"));
        check("a name without stats has unknown health",
                BoardView.health("Otter") == -1, "" + BoardView.health("Otter"));
        check("a broken stat tail is unknown too",
                BoardView.health("Otter 3/") == -1, "" + BoardView.health("Otter 3/"));
        check("an empty entry has no name", BoardView.name("").isEmpty(), BoardView.name(""));
    }

    private static void emptyEntriesAreDropped() {
        check("an empty slot in the middle is dropped",
                BoardView.entries("Flea 3/2,,Beaver 2/2").length == 2,
                "" + BoardView.entries("Flea 3/2,,Beaver 2/2").length);
        check("whitespace is trimmed from an entry",
                "Beaver".equals(BoardView.name(BoardView.entries("Flea 3/2, Beaver 2/2")[1])),
                BoardView.name(BoardView.entries("Flea 3/2, Beaver 2/2")[1]));
        check("no line is no pets", BoardView.entries(null).length == 0, "null");
        check("an empty line is no pets", BoardView.entries("").length == 0, "empty");
    }

    private static void battleStartLineDropsTheStats() {
        check("the battle-start line lists names only",
                "Flea, Beaver".equals(BoardView.names("Flea 3/2,Beaver 2/2")),
                BoardView.names("Flea 3/2,Beaver 2/2"));
        check("an empty team says nothing", BoardView.names("").isEmpty(), BoardView.names(""));
    }

    private static void slotHurtCoversDamageFaintsAndSwaps() {
        String[] before = BoardView.entries("Flea 3/5,Beaver 2/2");
        check("lost health is damage",
                BoardView.slotHurt(before, BoardView.entries("Flea 3/2,Beaver 2/2"), 0),
                "5 -> 2");
        check("a buff is not damage",
                !BoardView.slotHurt(before, BoardView.entries("Flea 3/7,Beaver 2/2"), 0),
                "5 -> 7");
        check("a faint is damage",
                BoardView.slotHurt(before, BoardView.entries("Beaver 2/2"), 0), "fainted");
        check("a summon in the slot is a change",
                BoardView.slotHurt(before, BoardView.entries("Zombie Cricket 1/1,Beaver 2/2"), 0),
                "swapped");
        check("an empty slot cannot take damage",
                !BoardView.slotHurt(BoardView.entries("Flea 3/5"), BoardView.entries("Beaver 2/2"), 3),
                "out of range");
    }

    // -------------------------------------------------------- board ordering

    private static void zoneNamesKeepSlotOrderAndEmpties() {
        String json = "{\"ready\":true,\"nodes\":["
                + node(1, "button", "", "Otter, tier 3, level 1", "petshop", 300, 700)
                + "," + node(2, "button", "Pet shop slot", "", "petshop", 440, 700)
                + "," + node(3, "button", "", "Fish, tier 1, level 1", "petshop", 580, 700)
                + "," + node(4, "button", "", "Ant, tier 1, level 2", "team", 200, 1500)
                + "," + node(5, "button", "Team slot 2", "", "team", 350, 1500)
                + "],\"shop\":{}}";
        List<SapNode> nodes = SapNode.parseList(json);
        List<String> shop = ShopView.zoneNames(nodes, "petshop");
        check("the pet shop reads in slot order", shop.size() == 3, "" + shop.size());
        check("a card is named from its detail line", "Otter".equals(shop.get(0)), shop.get(0));
        check("an empty slot keeps its place", "empty".equals(shop.get(1)), shop.get(1));
        check("the third card keeps its own name", "Fish".equals(shop.get(2)), shop.get(2));
        List<String> team = ShopView.zoneNames(nodes, "team");
        check("the board is named in slot order", team.size() == 2, "" + team.size());
        check("board slot 1 is the pet", "Ant".equals(team.get(0)), team.get(0));
        check("board slot 2 is empty", "empty".equals(team.get(1)), team.get(1));
    }

    private static void emptySlotsAreRecognisedByPlaceholder() {
        SapNode emptyPet = new SapNode(1, "button", "Pet shop slot", "", true, false, true,
                300, 700, 130, 180, "", "petshop");
        SapNode emptyFood = new SapNode(2, "button", "Food shop slot", "", true, false, true,
                300, 950, 130, 120, "", "foodshop");
        SapNode pet = new SapNode(3, "button", "", "", true, false, true,
                440, 700, 130, 180, "Otter, tier 3, level 1", "petshop");
        check("an empty pet slot is empty", ShopView.isEmptySlot(emptyPet), emptyPet.label);
        check("an empty food slot is empty", ShopView.isEmptySlot(emptyFood), emptyFood.label);
        check("a card is not empty", !ShopView.isEmptySlot(pet), pet.detail);
        check("null is empty", ShopView.isEmptySlot(null), "null");
    }

    // ------------------------------------------------------------- status line

    private static void statusLineFollowsThePcOrder() {
        String line = ShopView.statusLine(3, 10, 2, 2, 5, 5, 2, 0, 13);
        check("the status line is the PC mod's order and join",
                "Turn 3, 10 gold, 2 free rolls, tier 2, 5 of 5 lives, 2 wins, 0 losses, 13 moustaches"
                        .equals(line), line);
    }

    private static void statusLineNeverStartsWithASeparator() {
        String onlyFreeRolls = ShopView.statusLine(-1, -1, 1, -1, -1, -1, -1, -1, -1);
        check("free rolls alone do not start with a comma",
                "1 free roll".equals(onlyFreeRolls), onlyFreeRolls);
        check("unknown everything is an empty line",
                ShopView.statusLine(-1, -1, 0, -1, -1, -1, -1, -1, -1).isEmpty(),
                ShopView.statusLine(-1, -1, 0, -1, -1, -1, -1, -1, -1));
        check("one win is singular",
                "1 win".equals(ShopView.statusLine(-1, -1, 0, -1, -1, -1, 1, -1, -1)),
                ShopView.statusLine(-1, -1, 0, -1, -1, -1, 1, -1, -1));
        check("a single life without a maximum still reads",
                "1 life".equals(ShopView.statusLine(-1, -1, 0, -1, 1, -1, -1, -1, -1))
                        || ShopView.statusLine(-1, -1, 0, -1, 1, -1, -1, -1, -1).contains("1"),
                ShopView.statusLine(-1, -1, 0, -1, 1, -1, -1, -1, -1));
    }

    // ----------------------------------------------------------- affordability

    private static void affordabilityFollowsTheFlatPrice() {
        SapNode pet = new SapNode(1, "button", "", "", true, false, true,
                300, 700, 130, 180, "Otter, tier 3, level 1", "petshop");
        SapNode empty = new SapNode(2, "button", "Pet shop slot", "", true, false, true,
                440, 700, 130, 180, "", "petshop");
        SapNode food = new SapNode(3, "button", "", "", true, false, true,
                300, 950, 130, 120, "Apple, +1 health", "foodshop");
        check("a pet costs 3 gold", ShopView.ITEM_PRICE == 3, "" + ShopView.ITEM_PRICE);
        check("three gold can afford a pet",
                ", costs 3 gold, affordable".equals(ShopView.affordability(pet, 3)),
                ShopView.affordability(pet, 3));
        check("two gold cannot",
                ", costs 3 gold, not enough gold".equals(ShopView.affordability(pet, 2)),
                ShopView.affordability(pet, 2));
        check("an empty slot costs nothing",
                ShopView.affordability(empty, 5).isEmpty(), ShopView.affordability(empty, 5));
        check("unknown gold says nothing", ShopView.affordability(pet, -1).isEmpty(),
                ShopView.affordability(pet, -1));
        check("food with no gold warns",
                ", not enough gold".equals(ShopView.affordability(food, 0)),
                ShopView.affordability(food, 0));
        check("food with gold reports the purse",
                ", 2 gold left".equals(ShopView.affordability(food, 2)),
                ShopView.affordability(food, 2));
        SapNode button = new SapNode(4, "button", "Roll", "", true, false, true,
                900, 2100, 200, 100, "", "actions");
        check("a non-shop control has no price line",
                ShopView.affordability(button, 5).isEmpty(), ShopView.affordability(button, 5));
    }

    // ItemModel.Price (dump.cs:555387) and ItemModel.Frozen (dump.cs:555389)
    // are what the shop speaks; the flat 3 is reached only when the bridge
    // could not resolve a price, which is what -1 means.
    private static void priceAndFrozenComeFromTheModel() {
        SapNode discounted = new SapNode(1, "button", "", "", true, false, true,
                300, 700, 130, 180, "Apple, +1 health", "petshop", 2, -1, -1);
        check("the game's price is spoken",
                ", costs 2 gold, affordable".equals(ShopView.affordability(discounted, 2)),
                ShopView.affordability(discounted, 2));
        check("the game's price decides affordability too",
                ", costs 2 gold, not enough gold".equals(ShopView.affordability(discounted, 1)),
                ShopView.affordability(discounted, 1));
        SapNode unreadable = new SapNode(2, "button", "", "", true, false, true,
                300, 700, 130, 180, "Otter", "petshop", -1, -1, -1);
        check("an unresolved price falls back to the PC default of 3",
                ", costs 3 gold, affordable".equals(ShopView.affordability(unreadable, 3)),
                ShopView.affordability(unreadable, 3));
        SapNode frozen = new SapNode(3, "button", "", "", true, false, true,
                300, 700, 130, 180, "Otter", "petshop", 3, 1, -1);
        check("a frozen item says so",
                ", costs 3 gold, affordable, frozen".equals(ShopView.affordability(frozen, 3)),
                ShopView.affordability(frozen, 3));
        SapNode thawed = new SapNode(4, "button", "", "", true, false, true,
                300, 700, 130, 180, "Otter", "petshop", 3, 0, -1);
        check("a thawed item says nothing about freezing",
                ", costs 3 gold, affordable".equals(ShopView.affordability(thawed, 3)),
                ShopView.affordability(thawed, 3));
        SapNode unknownFrozen = new SapNode(5, "button", "", "", true, false, true,
                300, 700, 130, 180, "Otter", "petshop", 3, -1, -1);
        check("an unresolved frozen state is never reported as frozen",
                ", costs 3 gold, affordable".equals(ShopView.affordability(unknownFrozen, 3)),
                ShopView.affordability(unknownFrozen, 3));
        SapNode food = new SapNode(6, "button", "", "", true, false, true,
                300, 950, 130, 120, "Bread", "foodshop", 2, -1, -1);
        check("food uses the game's price",
                ", costs 2 gold, affordable".equals(ShopView.affordability(food, 2)),
                ShopView.affordability(food, 2));
        check("food affordability follows the game's price",
                ", costs 2 gold, not enough gold".equals(ShopView.affordability(food, 1)),
                ShopView.affordability(food, 1));
    }

    // MinionModel.SellValue (dump.cs:551320), unwrapped from Nullable<int> by
    // the bridge; -1 means the member did not resolve.
    private static void sellValueComesFromTheModel() {
        SapNode pet = new SapNode(1, "button", "", "", true, false, true,
                300, 1200, 130, 180, "Pig, level 2", "team", -1, -1, 4);
        check("a team pet speaks its own sell value",
                ", sells for 4 gold".equals(ShopView.affordability(pet, -1)),
                ShopView.affordability(pet, -1));
        check("the sell value is spoken even with unknown gold",
                ", sells for 4 gold".equals(ShopView.affordability(pet, 0)),
                ShopView.affordability(pet, 0));
        SapNode unreadable = new SapNode(2, "button", "", "", true, false, true,
                300, 1200, 130, 180, "Pig, level 2", "team", -1, -1, -1);
        check("an unresolved sell value says nothing instead of guessing",
                ShopView.affordability(unreadable, 5).isEmpty(),
                ShopView.affordability(unreadable, 5));
    }

    // The bridge omits price/frozen/sell when a member fails to resolve, so an
    // absent key must stay unknown rather than collapse to 0 or false.
    private static void itemFactsAreParsedFromTheSnapshot() {
        String json = "{\"nodes\":[{\"id\":7,\"role\":\"button\",\"label\":\"\","
                + "\"detail\":\"Otter\",\"zone\":\"petshop\",\"price\":5,"
                + "\"frozen\":true,\"sell\":2}]}";
        List<SapNode> nodes = SapNode.parseList(json);
        check("one node parsed", nodes.size() == 1, "" + nodes.size());
        if (nodes.size() == 1) {
            SapNode node = nodes.get(0);
            check("price is parsed from the snapshot", node.price == 5, "" + node.price);
            check("a frozen boolean parses as frozen", node.frozen == 1, "" + node.frozen);
            check("sell is parsed from the snapshot", node.sell == 2, "" + node.sell);
            check("withDetail keeps the item facts", node.withDetail(", x").price == 5,
                    "" + node.withDetail(", x").price);
        }
        String falsy = "{\"nodes\":[{\"id\":8,\"frozen\":false}]}";
        List<SapNode> thawed = SapNode.parseList(falsy);
        check("not-frozen parses as 0", !thawed.isEmpty() && thawed.get(0).frozen == 0,
                thawed.isEmpty() ? "no node" : "" + thawed.get(0).frozen);
        String bare = "{\"nodes\":[{\"id\":9}]}";
        List<SapNode> unknown = SapNode.parseList(bare);
        check("an absent price stays unknown",
                !unknown.isEmpty() && unknown.get(0).price == -1,
                unknown.isEmpty() ? "no node" : "" + unknown.get(0).price);
        check("an absent frozen state stays unknown",
                !unknown.isEmpty() && unknown.get(0).frozen == -1,
                unknown.isEmpty() ? "no node" : "" + unknown.get(0).frozen);
        check("an absent sell value stays unknown",
                !unknown.isEmpty() && unknown.get(0).sell == -1,
                unknown.isEmpty() ? "no node" : "" + unknown.get(0).sell);
    }

    private static void summaryNumbersSlotsInTheOrderGiven() {
        SapNode first = new SapNode(1, "button", "", "", true, false, true,
                300, 700, 130, 180, "Otter, tier 3, level 1", "petshop");
        SapNode second = new SapNode(2, "button", "", "", true, false, true,
                440, 700, 130, 180, "Fish, tier 1, level 1", "petshop");
        String summary = ShopView.summary(java.util.Arrays.asList(first, second), "petshop");
        check("slot numbers follow the list order", "Slot 1: Otter. Slot 2: Fish.".equals(summary),
                summary);
        String reversed = ShopView.summary(java.util.Arrays.asList(second, first), "petshop");
        check("and change with it", "Slot 1: Fish. Slot 2: Otter.".equals(reversed), reversed);
    }

    // ---------------------------------------------------------------- helpers

    private static String node(int id, String role, String label, String detail, String zone,
                               int x, int y) {
        return "{\"id\":" + id + ",\"role\":\"" + role + "\",\"label\":\"" + label
                + "\",\"value\":\"\",\"detail\":\"" + detail + "\",\"zone\":\"" + zone
                + "\",\"enabled\":true,\"checked\":false,\"focusable\":true,\"x\":" + x
                + ",\"y\":" + y + ",\"w\":130,\"h\":180}";
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add(what + " (" + detail + ")");
        }
    }
}
