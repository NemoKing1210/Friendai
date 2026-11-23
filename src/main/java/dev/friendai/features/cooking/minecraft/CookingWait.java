package dev.friendai.features.cooking.minecraft;

/**
 * What a cooking task does with a batch that outlasts its wait limit. The tool's {@code wait} argument maps to it:
 * omitted is {@link #AUTO}, true is {@link #WAIT}, false is {@link #LEAVE}.
 */
public enum CookingWait {
    /** Short batches are waited out; a long batch is left unless the plan behind the task needs the result. */
    AUTO,
    /** Stay by the station until every finished item is collected; success means verified collection. */
    WAIT,
    /** Load what fits and leave it cooking, because the caller asked to come back later. */
    LEAVE
}
