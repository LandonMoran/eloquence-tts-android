import app.sapa11y.extension.ShopRules;

import java.util.ArrayList;
import java.util.List;

/** The shop rules the port applies, as the game and the PC mod define them. */
public final class ShopRulesTest {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        rollCosts();
        freeRollsAreSpentFirst();
        unknownGoldIsNotARefusal();
        sellRefundsMatchLevel();
        sellValueWinsOverLevel();
        mergeThresholds();
        wholeTeamFood();

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) System.out.println("FAILED: " + failure);
        if (!failures.isEmpty()) System.exit(1);
    }

    private static void rollCosts() {
        check("a roll needs one gold", !ShopRules.canRoll(0, 0), "canRoll(0,0)");
        check("one gold is enough to roll", ShopRules.canRoll(1, 0), "canRoll(1,0)");
        check("gold above the cost rolls", ShopRules.canRoll(5, 0), "canRoll(5,0)");
        check("a roll spends exactly one gold", ShopRules.goldAfterRoll(1, 0) == 0,
                "" + ShopRules.goldAfterRoll(1, 0));
        check("rolling keeps the rest", ShopRules.goldAfterRoll(4, 0) == 3,
                "" + ShopRules.goldAfterRoll(4, 0));
    }

    private static void freeRollsAreSpentFirst() {
        check("a free roll works with no gold", ShopRules.canRoll(0, 1), "canRoll(0,1)");
        check("a free roll keeps the gold", ShopRules.goldAfterRoll(3, 1) == 3,
                "" + ShopRules.goldAfterRoll(3, 1));
        check("several free rolls still keep the gold", ShopRules.goldAfterRoll(0, 3) == 0,
                "" + ShopRules.goldAfterRoll(0, 3));
    }

    private static void unknownGoldIsNotARefusal() {
        check("unknown gold does not block a roll", ShopRules.canRoll(-1, 0), "canRoll(-1,0)");
        check("unknown gold stays unknown after a roll",
                ShopRules.goldAfterRoll(-1, 0) == -1, "" + ShopRules.goldAfterRoll(-1, 0));
    }

    /**
     * "A pet's sell value usually equals their level" (Super Auto Pets wiki,
     * Gold), and the PC mod reads the model's SellValue with a default of 1
     * (ShopNavigationManager.cs:2214-2222). The tier is not the refund: a
     * tier 6 level 1 pet sells for one gold, which is what this used to get
     * wrong.
     */
    private static void sellRefundsMatchLevel() {
        for (int level = 1; level <= 3; level++) {
            check("level " + level + " pet refunds " + level + " gold",
                    ShopRules.sellRefund(level) == level, "" + ShopRules.sellRefund(level));
        }
        check("an empty slot refunds nothing", ShopRules.sellRefund(0) == 0,
                "" + ShopRules.sellRefund(0));
        check("a pet without a level refunds nothing", ShopRules.sellRefund(-1) == 0,
                "" + ShopRules.sellRefund(-1));
    }

    /**
     * Level 2 needs 2 experience and level 3 needs 3 more (wiki, Experience:
     * "require 2 experience points for level 2, and then 3 for level 3"), one
     * experience per merged copy and none to start, so 3 and 6 copies in total
     * and 2 then 3 more. The PC mod reads the same table from the game:
     * PetStatsReader.cs:876-883 indexes BoardConstants.LevelRequirements.
     */
    // MinionModel.SellValue is a Nullable<int> field (dump.cs:551320): the
    // bridge publishes it only when it has a value, so the level rule is the
    // fallback for an unresolved member and nothing else.
    private static void sellValueWinsOverLevel() {
        check("the game's sell value wins over the level", ShopRules.sellRefund(1, 4) == 4,
                "" + ShopRules.sellRefund(1, 4));
        check("a resolved zero refund is kept, not replaced by the level",
                ShopRules.sellRefund(3, 0) == 0, "" + ShopRules.sellRefund(3, 0));
        check("an unreadable sell value falls back to the level",
                ShopRules.sellRefund(2, -1) == 2, "" + ShopRules.sellRefund(2, -1));
        check("an unreadable sell value with no level refunds nothing",
                ShopRules.sellRefund(0, -1) == 0, "" + ShopRules.sellRefund(0, -1));
    }

    private static void mergeThresholds() {
        check("three copies in total make level 2", ShopRules.copiesForLevel(1) == 3,
                "" + ShopRules.copiesForLevel(1));
        check("six copies in total make level 3", ShopRules.copiesForLevel(2) == 6,
                "" + ShopRules.copiesForLevel(2));
        check("level 3 is the maximum", ShopRules.copiesForLevel(3) == Integer.MAX_VALUE,
                "" + ShopRules.copiesForLevel(3));
        check("two more copies reach level 2", ShopRules.copiesNeeded(1) == 2,
                "" + ShopRules.copiesNeeded(1));
        check("three more copies reach level 3", ShopRules.copiesNeeded(2) == 3,
                "" + ShopRules.copiesNeeded(2));
        check("nothing more reaches past level 3", ShopRules.copiesNeeded(3) == Integer.MAX_VALUE,
                "" + ShopRules.copiesNeeded(3));
    }

    /** A spell with no aim target is confirmed on the team, not on a pet. */
    private static void wholeTeamFood() {
        check("a spell with no Aim target covers the team", ShopRules.spellCoversTeam(1), "1");
        check("a single-target spell does not", !ShopRules.spellCoversTeam(0), "0");
        check("an unknown aim state never fires a whole-team spell",
                !ShopRules.spellCoversTeam(-1), "-1");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
        } else {
            failures.add(what + " (" + detail + ")");
        }
    }
}
