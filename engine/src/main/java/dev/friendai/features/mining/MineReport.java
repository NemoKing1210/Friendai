package dev.friendai.features.mining;

import java.util.List;

/**
 * The one sentence a pointed mine reports: what was broken, what was taken, and what was left where. The formatter is
 * pure so the wording can be pinned without a world; the task feeds it receipts and leftovers from the drop sweep.
 */
public final class MineReport {
    /** One item type the round moved into the inventory. */
    public record Taken(String item, int count) {
        public Taken {
            count = Math.max(0, count);
        }
    }

    /** One drop the round meant to take and left on the ground, with where it was last seen. */
    public record Untaken(String item, int count, int x, int y, int z) {}

    private MineReport() {}

    /**
     * @param block the block id that was mined
     * @param mined how many blocks of it were broken (one, or the whole vein)
     * @param taken the stacks that reached the inventory
     * @param left the drops still on the ground
     * @param sawAnyDrop whether the sweep ever saw a drop it meant to take
     * @param stop an extra cause when the vein stopped early, or empty
     */
    public static String success(
            String block,
            int mined,
            int x,
            int y,
            int z,
            List<Taken> taken,
            List<Untaken> left,
            boolean sawAnyDrop,
            String stop) {
        StringBuilder text = new StringBuilder("mined ");
        if (mined > 1) {
            text.append(mined).append(' ');
        }
        text.append(block)
                .append(" at ")
                .append(x)
                .append(' ')
                .append(y)
                .append(' ')
                .append(z);
        if (!taken.isEmpty()) {
            text.append(" and took ").append(list(taken));
        } else if (!left.isEmpty()) {
            text.append(" but took nothing");
        } else if (sawAnyDrop) {
            text.append("; its drop is gone (destroyed or despawned)");
        } else {
            text.append("; it dropped nothing I could see");
        }
        if (!left.isEmpty()) {
            text.append("; ").append(describe(left)).append(" (use pickup_items)");
        }
        if (stop != null && !stop.isBlank()) {
            text.append("; ").append(stop);
        }
        return text.toString();
    }

    /** The refusal before any block is touched, so the model does not have to infer it from a missing drop. */
    public static String notMined(String block, int x, int y, int z, String reason) {
        return "I did not mine " + block + " at " + x + " " + y + " " + z + ": " + reason;
    }

    private static String list(List<Taken> taken) {
        StringBuilder text = new StringBuilder();
        for (Taken entry : taken) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.count()).append(' ').append(entry.item());
        }
        return text.toString();
    }

    private static String describe(List<Untaken> left) {
        StringBuilder text = new StringBuilder();
        Untaken first = left.get(0);
        if (left.size() == 1) {
            text.append(first.count())
                    .append(' ')
                    .append(first.item())
                    .append(" is still at ")
                    .append(first.x())
                    .append(' ')
                    .append(first.y())
                    .append(' ')
                    .append(first.z());
            return text.toString();
        }
        Untaken second = left.get(1);
        text.append(left.size())
                .append(" drops are still at ")
                .append(first.x())
                .append(' ')
                .append(first.y())
                .append(' ')
                .append(first.z())
                .append(" and ")
                .append(second.x())
                .append(' ')
                .append(second.y())
                .append(' ')
                .append(second.z());
        if (left.size() > 2) {
            text.append(" and ").append(left.size() - 2).append(" more");
        }
        return text.toString();
    }
}
