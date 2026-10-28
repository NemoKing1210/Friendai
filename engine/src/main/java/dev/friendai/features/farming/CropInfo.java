package dev.friendai.features.farming;

/**
 * One plantable crop: the block, the item that plants it, what it yields and where it grows.
 * Yields are planning estimates only.
 */
public record CropInfo(
        String crop,
        String seed,
        String produce,
        int maxAge,
        Soil soil,
        int foodValue,
        double seedYield,
        double produceYield) {
    public CropInfo {
        if (crop == null || crop.isEmpty() || seed == null || seed.isEmpty() || produce == null || produce.isEmpty()) {
            throw new IllegalArgumentException("crop, seed and produce ids must not be empty");
        }
        if (maxAge < 1) {
            throw new IllegalArgumentException("maxAge must be at least 1: " + maxAge);
        }
        if (soil == null) {
            throw new IllegalArgumentException("soil must not be null");
        }
        if (foodValue < 0 || seedYield < 0 || produceYield < 0) {
            throw new IllegalArgumentException("food value and yields must not be negative");
        }
    }
}
