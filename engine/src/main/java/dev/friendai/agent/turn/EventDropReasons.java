package dev.friendai.agent.turn;

import dev.friendai.foundation.diagnostics.LogLevel;

/**
 * Why the brain dropped an event, and how loudly each reason belongs in the log.
 *
 * <p>A cancellation the companion itself caused is already recorded in task history, so dropping its event needs no
 * turn and is routine; a refusal (a guest cooldown, an authority question still pending, a backlog) is something an
 * owner may need to see and stays a warning. One table keeps the dropping brain and the logging observer in step.
 */
final class EventDropReasons {
    static final String CANCELLATION = "cancellation retained in task history";
    static final String GUEST_COOLDOWN = "guest cooldown";
    static final String AUTHORITY_COOLDOWN = "authority blocker is still pending; question cooldown";
    static final String GUEST_BACKLOG = "too many pending messages";

    private EventDropReasons() {}

    /** INFO for a routine cancellation; WARN for every refusal and for a reason this build does not know. */
    static LogLevel level(String reason) {
        return CANCELLATION.equals(reason) ? LogLevel.INFO : LogLevel.WARN;
    }
}
