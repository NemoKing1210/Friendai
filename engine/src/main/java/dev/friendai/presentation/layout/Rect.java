package dev.friendai.presentation.layout;

public record Rect(int x, int y, int width, int height) {
    public int right() {
        return x + width;
    }
}
