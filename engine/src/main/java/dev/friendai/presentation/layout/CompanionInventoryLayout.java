package dev.friendai.presentation.layout;

/**
 * Where the companion inventory window puts each block: the slot origins, the model box, the vitals, the experience
 * bar and the labels. The server builds the slots and the client draws around them, so the numbers live in one place
 * instead of being written twice and drifting apart.
 *
 * <p>One vertical rhythm, top to bottom: the gear block (armor column, model, hands), the vitals, the experience bar,
 * the companion grid, its label, the viewer's grid and the hotbar. Every gap is at least two pixels, and the slot
 * frames are one pixel outside the origins.
 */
public final class CompanionInventoryLayout {

    /** The panel size, its outline included. */
    public static final int WIDTH = 184;

    public static final int HEIGHT = 254;

    /** Slot pitch and slot box, the armor column and both 9-wide grids share it. */
    public static final int PITCH = 18;

    public static final int SLOT = 18;

    /** The first column of every 9-wide grid, content origin; its frame starts one pixel left of it. */
    public static final int SLOT_X = 12;

    public static final int GEAR_Y = 18;
    public static final int HANDS_X = 82;
    public static final int HANDS_Y = 62;
    public static final int ITEMS_Y = 108;
    public static final int LABEL_Y = 163;
    public static final int PLAYER_Y = 175;
    public static final int HOTBAR_Y = 231;

    /** Both labels start with the grid content, and the title above them does the same. */
    public static final int LABEL_X = SLOT_X;

    /** The companion's model, in the free space right of the armor column and left of the hands. */
    public static final int PORTRAIT_X = 30;

    public static final int PORTRAIT_Y = 18;
    public static final int PORTRAIT_WIDTH = 50;
    public static final int PORTRAIT_HEIGHT = 66;

    /** Ten hearts on the left and ten food icons on the right, one group per side of the panel's axis. */
    public static final int VITALS_Y = 91;

    public static final int VITALS_X = 8;
    public static final int ICONS = 10;
    public static final int ICON = 9;
    public static final int ICON_STEP = 8;
    /** The gap between the two vitals groups; the panel is too narrow for a wider one. */
    public static final int VITALS_GAP = 6;

    /** A bar under the vitals; the level sits in the free space above the hands. */
    public static final int XP_Y = 102;

    public static final int XP_HEIGHT = 3;
    public static final int LEVEL_X = 86;
    public static final int LEVEL_Y = 28;

    private CompanionInventoryLayout() {}

    /** The content x of grid column {@code col}. */
    public static int columnX(int col) {
        return SLOT_X + col * PITCH;
    }

    /** The content y of the {@code row}-th row of a grid that starts at {@code first}. */
    public static int rowY(int first, int row) {
        return first + row * PITCH;
    }

    /** The width of one ten-icon group. */
    public static int groupWidth() {
        return ICON + (ICONS - 1) * ICON_STEP;
    }

    /** The left edge of the hearts, and the right edge of the food group: the same margin on both sides. */
    public static int heartsX() {
        return VITALS_X;
    }

    public static int foodRight() {
        return heartsX() + 2 * groupWidth() + VITALS_GAP;
    }
}
