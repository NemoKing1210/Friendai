package dev.friendai.features.conversation;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matches a whole chat or voice line against fixed yes/no word lists, so a confirmation never needs the
 * model. A leading companion name and trailing punctuation are ignored; longer sentences are {@code NONE}.
 */
public final class ConfirmationMatcher {
    public enum Answer {
        YES,
        NO,
        NONE
    }

    private static final Set<String> YES = Set.of(
            "yes",
            "yeah",
            "yep",
            "sure",
            "ok",
            "okay",
            "confirm",
            "confirmed",
            "do it",
            "да",
            "ага",
            "угу",
            "конечно",
            "подтверждаю",
            "подтвердить",
            "давай",
            "хорошо",
            "ок");
    private static final Set<String> NO =
            Set.of("no", "nope", "cancel", "don't", "do not", "нет", "не надо", "отмена", "отменить", "не");
    private static final String WORD_CHARS = "[\\p{L}\\p{N}_]";

    private ConfirmationMatcher() {}

    public static Answer match(String text, String companionName) {
        return match(text, companionName, null);
    }

    /**
     * Matches against the chat form too: a configured prefix ("@") and then the companion's name are
     * removed, like {@link AddressDetector} does, so "@ да" and "@Friendai, да" confirm.
     */
    public static Answer match(String text, String companionName, String prefix) {
        if (text == null) {
            return Answer.NONE;
        }
        String stripped = text.strip();
        if (prefix != null && !prefix.isEmpty() && stripped.startsWith(prefix)) {
            stripped = stripped.substring(prefix.length()).stripLeading();
        }
        String normalized = stripTrailing(stripped.toLowerCase(Locale.ROOT));
        if (companionName != null && !companionName.isBlank()) {
            normalized = stripTrailing(stripLeadingName(normalized, companionName.toLowerCase(Locale.ROOT)));
        }
        if (YES.contains(normalized)) {
            return Answer.YES;
        }
        return NO.contains(normalized) ? Answer.NO : Answer.NONE;
    }

    /** "Friendai, да" → "да"; leaves the text alone when the name is not the first word. */
    private static String stripLeadingName(String text, String name) {
        Pattern leadingName = Pattern.compile("^" + Pattern.quote(name) + "(?!" + WORD_CHARS + ")[\\s,:!]*");
        return leadingName.matcher(text).replaceFirst("");
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0) {
            char last = text.charAt(end - 1);
            if (Character.isWhitespace(last) || ".!?,".indexOf(last) >= 0) {
                end--;
            } else {
                break;
            }
        }
        return text.substring(0, end);
    }
}
