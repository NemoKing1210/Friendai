package dev.friendai.queue;

public record QueueNotice(long tick, String text) {
    public QueueNotice {
        text = text == null ? "" : text;
    }
}
