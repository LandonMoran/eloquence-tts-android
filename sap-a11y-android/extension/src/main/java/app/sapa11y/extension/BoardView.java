package app.sapa11y.extension;

/**
 * The battle board lines the native bridge writes, read back without Android.
 *
 * `libsapa11y.so` sends one team as "Flea 3/2,Beaver 2/2" (team_line in
 * native/src/sapa11y.cpp): each pet's localized name, a space, then
 * "attack/health". The controller diffs two of these between polls to narrate a
 * battle, which is what the PC mod does event by event in BattleNarrator.
 *
 * The parsing lives here, not in the controller, so the numbers it reads can be
 * checked without a device: a stat read wrong is announced as wrong damage.
 */
public final class BoardView {
    private BoardView() {}

    /** The pets on one side, in order, with empty entries dropped. */
    public static String[] entries(String line) {
        if (line == null || line.isEmpty()) return new String[0];
        String[] raw = line.split(",");
        String[] kept = new String[raw.length];
        int count = 0;
        for (String entry : raw) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) kept[count++] = trimmed;
        }
        String[] out = new String[count];
        System.arraycopy(kept, 0, out, 0, count);
        return out;
    }

    /**
     * The pet's name: everything before the stats. A pet name may contain
     * spaces ("Golden Retriever"), so the split is at the last one, not the
     * first. An entry without stats is all name.
     */
    public static String name(String entry) {
        if (entry == null) return "";
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) return "";
        int cut = trimmed.lastIndexOf(' ');
        return cut > 0 ? trimmed.substring(0, cut) : trimmed;
    }

    /** Attack from the "attack/health" tail, or -1 when the tail is not two numbers. */
    public static int attack(String entry) {
        int[] stats = stats(entry);
        return stats == null ? -1 : stats[0];
    }

    /** Health from the "attack/health" tail, or -1 when the tail is not two numbers. */
    public static int health(String entry) {
        int[] stats = stats(entry);
        return stats == null ? -1 : stats[1];
    }

    private static int[] stats(String entry) {
        if (entry == null) return null;
        String trimmed = entry.trim();
        int cut = trimmed.lastIndexOf(' ');
        if (cut <= 0) return null;
        String[] parts = trimmed.substring(cut + 1).split("/");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "Flea 3/2,Beaver 2/2" -> "Flea, Beaver", for the battle-start line. */
    public static String names(String line) {
        StringBuilder sb = new StringBuilder();
        for (String entry : entries(line)) {
            String name = name(entry);
            if (name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    /**
     * True when the slot at this index took damage or fainted between the two
     * lines. An entry that disappeared, or that now holds a different pet,
     * counts: a summon in the same slot is a change worth announcing.
     */
    public static boolean slotHurt(String[] before, String[] after, int index) {
        if (before == null || index < 0 || index >= before.length) return false;
        String was = before[index];
        if (was == null || was.isEmpty()) return false;
        String now = after != null && index < after.length ? after[index] : "";
        if (now == null || now.isEmpty()) return true;
        if (!name(was).equals(name(now))) return true;
        return health(now) < health(was);
    }
}
