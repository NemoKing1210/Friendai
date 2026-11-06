package dev.friendai.foundation.spatial;

/** Where a dropped item lies, as the item entity reports its own position (the floor of its box). */
public record DropPoint(double x, double y, double z) {}
