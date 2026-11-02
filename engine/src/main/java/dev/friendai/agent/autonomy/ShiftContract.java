package dev.friendai.agent.autonomy;

/**
 * The three facts every autonomous shift states while it runs: what it is for, what it may spend and what ends it.
 * The purpose is one phrase per reason {@link NeedsAssessor} already produces, so no second agenda exists to drift
 * from this one; the budget is the shift's own allowance, counted the way free time already counts it.
 */
public final class ShiftContract {
    /** The purpose used when a shift names no reason; the assessor always names one. */
    public static final String UNNAMED = "keeping busy";

    private ShiftContract() {}

    /** One short English phrase for the need the assessor chose. Exhaustive: a new need must name its purpose. */
    public static String purpose(NeedKind need) {
        if (need == null) {
            return UNNAMED;
        }
        return switch (need) {
            case SURVIVE -> "staying alive";
            case HUNGER -> "eating";
            case SHELTER -> "finding shelter";
            case SLEEP -> "sleeping";
            case UNLOAD -> "storing the haul";
            case COLLECT -> "collecting the smelted batch";
            case FOOD -> "keeping food in stock";
            case PROCESS -> "processing materials";
            case GEAR -> "gearing up";
            case REPAIR -> "repairing gear";
            case FARM -> "tending the farm";
            case FARM_CREATE -> "starting a farm";
            case ANIMALS -> "tending the animals";
            case LIGHT -> "lighting the place";
            case BUILD -> "building";
            case STORAGE -> "making storage";
            case TRADE -> "trading";
            case ENCHANT -> "enchanting";
            case NETHER -> "preparing for the nether";
            case STOCK -> "restocking";
            case EXPLORE -> "exploring";
        };
    }

    /** What the shift may spend: the minutes its deadline already allows. */
    public static String budget(long nowMillis, long deadlineMillis) {
        long span = deadlineMillis - nowMillis;
        long minutes = span <= 0 ? 1L : (span - 1) / 60_000L + 1L;
        return minutes + " min";
    }
}
