package dev.friendai.features.construction.special;

/** The box a structure needs, from its minimum corner, grown by {@code clearance} blocks on every side. */
public record Footprint(int width, int height, int depth, int clearance) {}
