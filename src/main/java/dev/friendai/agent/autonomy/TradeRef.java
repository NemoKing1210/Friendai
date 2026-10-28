package dev.friendai.agent.autonomy;

/**
 * A remembered trading place, with how long ago free time visited it.
 *
 * @param millisSinceVisit world time since the place was last visited or created
 */
public record TradeRef(long id, String name, double distance, boolean sameDimension, long millisSinceVisit) {
    public TradeRef {
        name = name == null ? "" : name;
        millisSinceVisit = Math.max(0, millisSinceVisit);
    }
}
