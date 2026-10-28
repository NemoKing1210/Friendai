package dev.friendai.brain;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the model's assistant text into the text the player should see. Pure Java: no Minecraft
 * types, and it never throws.
 */
public final class ReplyGuard {
    /** The model's control token for "say nothing this turn". */
    public static final String SILENT = "[silent]";

    private static final Pattern SENTINEL = Pattern.compile("(?i)[`*]*\\[\\s*silent\\s*\\][`*]*");
    private static final Pattern TASK_ID = Pattern.compile("(?i)\\btask\\s*#?\\s*\\d+\\b");
    private static final Pattern NAMESPACE = Pattern.compile("\\bminecraft:");
    private static final Pattern PAREN_TRIPLE = Pattern.compile("\\(\\s*-?\\d+(?:[ ,]+-?\\d+){2}\\s*\\)");
    private static final Pattern CONTEXT_TRIPLE =
            Pattern.compile("(?iu)(?<![\\p{L}\\d])(?:at|near|around|в|на|около)\\s+-?\\d+(?:[ ,]+-?\\d+){2}(?![\\d])");
    private static final Pattern XYZ_TRIPLE =
            Pattern.compile("(?iu)\\bx\\s*=\\s*-?\\d+\\s*[,;]?\\s*y\\s*=\\s*-?\\d+\\s*[,;]?\\s*z\\s*=\\s*-?\\d+");
    private static final Pattern LABEL_TRIPLE = Pattern.compile("(?iu)(?<![\\p{L}])"
            + "(?:(?:your|my|тво\\p{L}*|мо\\p{L}*)\\s+)?"
            + "(?:coordinates?|coords?|position|location|координат\\p{L}*|позици\\p{L}*)"
            + "(?:\\s+\\p{L}{1,12}){0,3}\\s*:?\\s*"
            + "-?\\d+(?:[ ,]+-?\\d+){2}[.!?]?(?![\\d])");
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\(\\s*\\)");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,.!?;:])");
    private static final Pattern LEADING_PUNCT = Pattern.compile("^[\\s,;:.!?]+");
    private static final Pattern PUNCT_ONLY = Pattern.compile("[\\s\\p{Punct}]*");
    private static final Pattern COORDINATE_CUES =
            Pattern.compile("(?iu)(?<![\\p{L}])(?:where|coords|coordinates|location|locate|position)(?![\\p{L}])"
                    + "|(?<![\\p{L}])где(?![\\p{L}])|координат|местоположен");

    private ReplyGuard() {}

    /**
     * @param reply              the model's assistant text
     * @param coordinatesAllowed whether this turn may mention coordinates
     * @param lastSpoken         the last text spoken in this conversation, or null
     * @param internalNames      tool names available in this turn; only underscored ones are removed
     * @return the text to speak, or empty for silence
     */
    public static Optional<String> filter(
            String reply, boolean coordinatesAllowed, String lastSpoken, Set<String> internalNames) {
        return filter(reply, coordinatesAllowed, lastSpoken == null ? List.of() : List.of(lastSpoken), internalNames);
    }

    /**
     * @param recentSpoken the lines the player just heard, newest first; a reply that only restates one of
     *                     them is dropped
     */
    public static Optional<String> filter(
            String reply, boolean coordinatesAllowed, List<String> recentSpoken, Set<String> internalNames) {
        try {
            if (reply == null) {
                return Optional.empty();
            }
            String text = reply.strip();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            text = SENTINEL.matcher(text).replaceAll(" ").strip();
            if (text.isEmpty()) {
                return Optional.empty();
            }

            text = TASK_ID.matcher(text).replaceAll(" ");
            text = NAMESPACE.matcher(text).replaceAll("");
            if (internalNames != null) {
                for (String name : internalNames) {
                    if (name == null || !name.contains("_")) {
                        continue;
                    }
                    Pattern namePattern = Pattern.compile("(?<![\\w:])" + Pattern.quote(name) + "(?![\\w:])");
                    text = namePattern.matcher(text).replaceAll(" ");
                }
            }

            if (!coordinatesAllowed) {
                text = PAREN_TRIPLE.matcher(text).replaceAll(" ");
                text = CONTEXT_TRIPLE.matcher(text).replaceAll(" ");
                text = LABEL_TRIPLE.matcher(text).replaceAll(" ");
                text = XYZ_TRIPLE.matcher(text).replaceAll(" ");
            }

            text = EMPTY_BRACKETS.matcher(text).replaceAll(" ");
            text = MULTI_SPACE.matcher(text).replaceAll(" ");
            text = SPACE_BEFORE_PUNCT.matcher(text).replaceAll("$1");
            text = LEADING_PUNCT.matcher(text).replaceAll("");
            text = text.strip();

            if (text.isEmpty() || PUNCT_ONLY.matcher(text).matches()) {
                return Optional.empty();
            }
            if (recentSpoken != null && SpeechRepeat.repeatsAny(text, recentSpoken)) {
                return Optional.empty();
            }
            return Optional.of(text);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** True when the player's message asks where something is. */
    public static boolean playerAskedForCoordinates(String message) {
        return message != null
                && !message.isBlank()
                && COORDINATE_CUES.matcher(message).find();
    }
}
