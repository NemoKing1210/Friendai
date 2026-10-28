package dev.friendai.execution.queue;

public record QueueNotice(long tick, String text) {
    public QueueNotice {
        text = text == null ? "" : text;
    }
}
