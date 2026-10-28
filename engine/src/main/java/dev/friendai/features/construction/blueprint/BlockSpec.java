package dev.friendai.features.construction.blueprint;

/**
 * One block of a blueprint: the position within the blueprint's box and the wanted block state, like
 * {@code minecraft:oak_stairs[facing=east,half=bottom]}. {@code minecraft:air} means "must be empty".
 */
public record BlockSpec(int x, int y, int z, String state) {}
