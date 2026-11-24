package dev.friendai.features.acquisition;

/**
 * How much work a tool can still do, in blocks. One block that takes time to break wears one point, which is what
 * vanilla does, so the remaining durability of a stack is a conservative block count. The planner needs it twice: to
 * tell a nearly worn tool from a fresh one and to size a replacement when the carried tools cannot finish the job.
 *
 * <p>The numbers are the vanilla durability of one tool of that kind and material. They are estimates on purpose: a
 * batch of blocks is planned from them, and execution still stops when a tool breaks.
 */
public final class ToolCharge {
    /** Spare blocks a trip away from the base keeps in reserve, so the tool is not spent exactly on arrival. */
    public static final int APPROACH_RESERVE = 8;

    /** The most replacement tools one plan arranges at once; a larger job replans after the first batch. */
    public static final int MAX_PLANNED_TOOLS = 4;

    private ToolCharge() {}

    /** Blocks one fresh tool satisfying {@code need} can break; zero when no useful tool is known for it. */
    public static int perTool(ToolNeed need) {
        if (need == null) {
            return 0;
        }
        if (need.kind() == ToolKind.SHEARS) {
            return 238;
        }
        return switch (need.tier()) {
            case NONE -> 0;
            case WOOD -> 59;
            case STONE -> 131;
            case IRON -> 250;
            case DIAMOND -> 1_561;
            case NETHERITE -> 2_031;
        };
    }

    /** The tool blocks a plan must cover for {@code blocks} of mining, plus the reserve a trip away needs. */
    public static int required(int blocks, boolean away) {
        int wanted = Math.max(0, blocks);
        return away ? wanted + APPROACH_RESERVE : wanted;
    }
}
