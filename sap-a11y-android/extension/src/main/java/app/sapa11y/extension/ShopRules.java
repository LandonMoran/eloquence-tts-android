package app.sapa11y.extension;

/**
 * The shop rules the port itself applies.
 *
 * The game owns most of the shop: buying, combining and applying food all run
 * inside it. What the port decides is when an action is worth sending and what
 * to say afterwards, and those decisions are the rules below — a roll costs one
 * gold and spends a banked free roll first, and a sell refunds the pet's own
 * SellValue, falling back to one gold per level only when that member cannot be
 * read.
 */
public final class ShopRules {
    private ShopRules() {}

    /** One roll costs one gold; a banked free roll is spent before gold. */
    public static final int ROLL_COST = 1;

    /**
     * Whether the Roll button will do anything. Unknown gold (-1, before the
     * first snapshot) is never a reason to refuse.
     */
    public static boolean canRoll(int gold, int freeRolls) {
        if (freeRolls > 0) return true;
        return gold < 0 || gold >= ROLL_COST;
    }

    /** Gold left after a roll, from the snapshot that shows the result. */
    public static int goldAfterRoll(int gold, int freeRolls) {
        if (freeRolls > 0) return gold;
        return gold < 0 ? gold : Math.max(0, gold - ROLL_COST);
    }

    /**
     * Gold a sale returns: one gold per level, not per tier.
     *
     * "A pet's sell value usually equals their level" (Super Auto Pets wiki,
     * Gold: "All Pets cost 3 Gold. A pets sell value usually equals their
     * level"), and the PC mod reads the model's own `SellValue` with a default
     * of 1 (ShopNavigationManager.cs:2214-2222). This port used to return the
     * pet's tier, which told a user selling a tier 6 level 1 pet that six gold
     * were coming back. Abilities that add gold on a sale (Pig, Rice, Grapes)
     * are paid by the game on top; the announcement takes the real change from
     * the snapshot's gold instead of this number.
     */
    public static int sellRefund(int level) {
        return level <= 0 ? 0 : level;
    }

    /**
     * The game's own sell value when the bridge resolved it, and the level rule
     * only when it did not.
     *
     * MinionModel.SellValue is a Nullable&lt;int&gt; field (dump.cs:551320), so an
     * absent value reaches here as -1 and the level figure is the fallback; a
     * resolved value is authoritative even when it disagrees with the level
     * (abilities can change a pet's sell price).
     */
    public static int sellRefund(int level, int modelSellValue) {
        return modelSellValue >= 0 ? modelSellValue : sellRefund(level);
    }

    /**
     * Total copies of a pet needed to reach the given level, this pet included:
     * three for level 2 and six for level 3.
     *
     * A pet starts at level 1 with no experience; each merged copy gives one
     * experience, level 2 needs 2 and level 3 needs 3 more (Super Auto Pets
     * wiki, Experience: "require 2 experience points for level 2, and then 3 for
     * level 3"). The PC mod reads the same table at runtime:
     * PetStatsReader.GetExpForNextLevel (PetStatsReader.cs:876-883) indexes
     * `BoardConstants.LevelRequirements` by the current level.
     */
    public static int copiesForLevel(int level) {
        switch (level) {
            case 1:
                return 3;
            case 2:
                return 6;
            default:
                return Integer.MAX_VALUE;
        }
    }

    /**
     * Whether the food being aimed covers the whole team rather than one pet.
     *
     * The game marks that by a spell template with no Aim target
     * (`Spell.Aim` is null, dump.cs:554403); the PC mod reads the same field
     * (`IsZoneTargetFood`: `template.Aim == null`). Such a food is confirmed by
     * clicking the board-sized SpellZone, and a pet click is ignored — so an
     * unknown state (-1) must never take the whole-team path, or a targetless
     * click would be sent while a single-target food is being aimed.
     */
    public static boolean spellCoversTeam(int aimless) {
        return aimless == 1;
    }

    /** Additional copies still needed to reach the next level: 2, then 3. */
    public static int copiesNeeded(int level) {
        switch (level) {
            case 1:
                return 2;
            case 2:
                return 3;
            default:
                return Integer.MAX_VALUE;
        }
    }
}
