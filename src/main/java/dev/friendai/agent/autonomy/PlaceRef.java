package dev.friendai.agent.autonomy;

/** A remembered place as free time sees it: its name for tool arguments and how far away it is. */
public record PlaceRef(long id, String name, double distance, boolean sameDimension) {}
