package dev.friendai.agent.autonomy;

/**
 * One agenda item.
 *
 * @param urgency 0 to 100, higher first
 * @param text an English line for the director's prompt
 */
public record Need(NeedKind kind, int urgency, Executor executor, String text) {}
