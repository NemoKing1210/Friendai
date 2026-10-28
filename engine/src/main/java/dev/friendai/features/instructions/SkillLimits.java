package dev.friendai.features.instructions;

/** Hard upper bounds for instruction data, shared by disk, tools and menu. */
public record SkillLimits(int ownerCount, int serverCount, int totalChars) {
    public static final int NAME = 80, DESCRIPTION = 200, BODY = 4000, TERMS = 12, TERM = 40;
    public static final int FILE_BYTES = 1 << 20, PAGE_SIZE = 16;

    public static SkillLimits defaults() {
        return new SkillLimits(64, 32, 96_000);
    }

    public SkillLimits {
        if (ownerCount < 1
                || ownerCount > 64
                || serverCount < 1
                || serverCount > 32
                || totalChars < 4000
                || totalChars > 96_000) {
            throw new IllegalArgumentException("Invalid skill limits.");
        }
    }
}
