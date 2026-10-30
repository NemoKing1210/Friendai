package dev.friendai.features.memory.transcript;

import dev.friendai.features.memory.MenuStatus;

/** The memory tab's one transcript action: clear the owner's conversation log. */
public final class TranscriptMenuActions {
    private TranscriptMenuActions() {}

    public static MenuStatus clear(TranscriptBook book) {
        if (book == null) {
            return MenuStatus.error("friendai.menu.transcript.error.unavailable");
        }
        return MenuStatus.ok("friendai.menu.transcript.status.cleared", book.clearAll());
    }
}
