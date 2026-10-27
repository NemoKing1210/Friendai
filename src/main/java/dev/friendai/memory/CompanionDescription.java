package dev.friendai.memory;

/** Owner-authored public summary and on-demand private detail. */
public record CompanionDescription(String summary, String details) {
    public static final CompanionDescription EMPTY = new CompanionDescription("", "");

    public CompanionDescription {
        summary = plain(summary, 300, false);
        details = plain(details, 4000, true);
    }

    public static String plain(String raw, int max, boolean multiline) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() > max) throw new IllegalArgumentException("Text exceeds its character limit.");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u00a7' || (Character.isISOControl(c) && !(multiline && c == '\n'))) {
                throw new IllegalArgumentException("Use plain text without formatting or control characters.");
            }
        }
        return multiline ? value : value.replaceAll("\\s+", " ");
    }
}
