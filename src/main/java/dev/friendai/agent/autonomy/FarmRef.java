package dev.friendai.agent.autonomy;

/** A remembered farm, with how long ago the companion last visited it. */
public record FarmRef(
        long id, String name, String label, double distance, boolean sameDimension, long millisSinceVisit) {}
