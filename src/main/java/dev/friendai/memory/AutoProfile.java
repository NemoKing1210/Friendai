package dev.friendai.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures the stable parts of the owner's profile from the owner's own explicit words: how they want to be
 * addressed, which language they write in, and the preferences they state. Pure Java.
 *
 * <p>Only an explicit self-statement is evidence: {@code call me X} or {@code I like X} is captured, a quoted
 * span, a command or a bare pronoun ({@code I like it}) is not. The captured profile becomes ordinary facts
 * authored by the owner, so it is visible in the Memory tab and removable with {@code forget}. One fact per
 * slot: correcting the address or flipping a like to a dislike edits the existing fact instead of adding a
 * second one.
 *
 * <p>English and Russian are understood because those are the two languages the mod's detector commits to.
 * The fact text is English like every other fact; a value the owner said in Russian stays as they said it.
 */
public final class AutoProfile {
    /** What one captured fact is about. */
    public enum Kind {
        ADDRESS,
        LANGUAGE,
        PREFERENCE
    }

    /** One profile statement and the canonical fact it becomes. */
    public record Capture(Kind kind, Category category, String slot, String text) {}

    private static final String ADDRESS_PREFIX = "Prefers to be called ";
    private static final String LANGUAGE_PREFIX = "Writes in ";
    private static final String LIKES_PREFIX = "Likes ";
    private static final String DISLIKES_PREFIX = "Dislikes ";
    private static final String PREFERS_PREFIX = "Prefers ";

    private static final int MAX_ADDRESS_CHARS = 40;
    private static final int MAX_ADDRESS_WORDS = 4;
    private static final int MAX_PREFERENCE_CHARS = 80;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|`[^`]*`");
    private static final Pattern COMMAND = Pattern.compile("(?<!\\S)/\\S+");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?;\\n]");
    private static final Pattern ADDRESS_CLAUSE_END =
            Pattern.compile("\\s+(?:and|but|и|но|а)\\s+", FLAGS);
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\s,\"'«»]+|[\\s,\"'«»]+$");
    private static final Pattern POLITENESS = Pattern.compile("\\s+(?:please|пожалуйста)$", FLAGS);
    private static final int NEGATION_LOOKBACK = 12;

    private static final Set<String> BARE_VALUES = Set.of(
            "it", "this", "that", "them", "you", "him", "her", "me", "us", "these", "those", "everything",
            "anything", "это", "этого", "этот", "эту", "его", "её", "ее", "их", "тебя", "вас", "себя", "всё",
            "все", "ничего");

    /** "Call me when you arrive" is a request, not a name. */
    private static final Set<String> CLAUSE_STARTS = Set.of(
            "when", "whenever", "if", "once", "after", "before", "later", "tomorrow", "now", "back", "again",
            "как", "когда", "если", "потом", "завтра", "сейчас", "обратно");

    private static final Pattern ADDRESS_CALL_EN = address("call me\\s+");
    private static final Pattern ADDRESS_NAME_EN = address("my name is\\s+");
    private static final Pattern ADDRESS_CALL_RU = address("(?:зови|называй) меня\\s+");
    private static final Pattern ADDRESS_NAME_RU = address("меня зовут\\s+");

    private static final Pattern DISLIKE_EN =
            preference("(?<!\\p{L})i\\s+(?:really\\s+)?(?:don'?t|do\\s+not)\\s+like\\s+");
    private static final Pattern HATE_EN = preference("(?<!\\p{L})i\\s+hate\\s+");
    private static final Pattern PREFER_EN = preference("(?<!\\p{L})i\\s+(?:really\\s+)?prefer\\s+");
    private static final Pattern LIKE_EN = preference("(?<!\\p{L})i\\s+(?:really\\s+)?(?:like|love)\\s+");
    private static final Pattern DISLIKE_RU = preference("(?<!\\p{L})мне\\s+не\\s+нравится\\s+");
    private static final Pattern LIKE_RU = preference("(?<!\\p{L})мне\\s+нравится\\s+");
    private static final Pattern NOT_LOVE_RU = preference("(?<!\\p{L})я\\s+не\\s+люблю\\s+");
    private static final Pattern PREFER_RU = preference("(?<!\\p{L})я\\s+предпочитаю\\s+");
    private static final Pattern LOVE_RU = preference("(?<!\\p{L})я\\s+(?:очень\\s+)?люблю\\s+");
    private static final Pattern HATE_RU = preference("(?<!\\p{L})ненавижу\\s+");

    private static final List<Rule> ADDRESS_RULES = List.of(
            new Rule(ADDRESS_CALL_EN, ADDRESS_PREFIX),
            new Rule(ADDRESS_NAME_EN, ADDRESS_PREFIX),
            new Rule(ADDRESS_CALL_RU, ADDRESS_PREFIX),
            new Rule(ADDRESS_NAME_RU, ADDRESS_PREFIX));
    private static final List<Rule> PREFERENCE_RULES = List.of(
            new Rule(DISLIKE_EN, DISLIKES_PREFIX),
            new Rule(HATE_EN, DISLIKES_PREFIX),
            new Rule(PREFER_EN, PREFERS_PREFIX),
            new Rule(LIKE_EN, LIKES_PREFIX),
            new Rule(DISLIKE_RU, DISLIKES_PREFIX),
            new Rule(HATE_RU, DISLIKES_PREFIX),
            new Rule(NOT_LOVE_RU, DISLIKES_PREFIX),
            new Rule(PREFER_RU, PREFERS_PREFIX),
            new Rule(LIKE_RU, LIKES_PREFIX),
            new Rule(LOVE_RU, LIKES_PREFIX));

    private AutoProfile() {}

    /** A message pattern and the canonical fact prefix its value gets. */
    private record Rule(Pattern pattern, String canonicalPrefix) {}

    /** Every profile statement in one message, in the order they appear. */
    public static List<Capture> parse(String message) {
        String cleaned = clean(message);
        if (cleaned.isBlank()) {
            return List.of();
        }
        List<Capture> captures = new ArrayList<>();
        for (Rule rule : ADDRESS_RULES) {
            Optional<String> value = match(cleaned, rule);
            if (value.isEmpty()) {
                continue;
            }
            String name = addressValue(value.get());
            if (name != null) {
                captures.add(new Capture(
                        Kind.ADDRESS, Category.PROFILE, "address", ADDRESS_PREFIX + name + "."));
                break;
            }
        }
        for (Rule rule : PREFERENCE_RULES) {
            Optional<String> value = match(cleaned, rule);
            if (value.isEmpty()) {
                continue;
            }
            String thing = preferenceValue(value.get());
            if (thing == null) {
                continue;
            }
            if (rule.canonicalPrefix().equals(PREFERS_PREFIX)
                    && thing.toLowerCase(Locale.ROOT).startsWith("to be called ")) {
                String name = addressValue(thing.substring("to be called ".length()));
                if (name != null) {
                    captures.add(new Capture(
                            Kind.ADDRESS, Category.PROFILE, "address", ADDRESS_PREFIX + name + "."));
                }
                continue;
            }
            captures.add(new Capture(
                    Kind.PREFERENCE,
                    Category.PREFERENCE,
                    preferenceSlot(thing),
                    rule.canonicalPrefix() + thing + "."));
        }
        return captures;
    }

    /** Records every explicit profile statement in an owner's message. Never throws on a full book. */
    public static void record(
            MemoryBook book,
            UUID owner,
            String ownerName,
            String message,
            long now,
            MemoryLimits limits) {
        for (Capture capture : parse(message)) {
            upsert(book, owner, ownerName, capture, now, limits);
        }
    }

    /** Keeps the language fact in step with the value learned on the record. Never throws on a full book. */
    public static void syncLanguage(
            MemoryBook book,
            UUID owner,
            String ownerName,
            String language,
            long now,
            MemoryLimits limits) {
        if (language == null || language.isBlank()) {
            return;
        }
        upsert(
                book,
                owner,
                ownerName,
                new Capture(Kind.LANGUAGE, Category.PROFILE, "language", LANGUAGE_PREFIX + language.strip() + "."),
                now,
                limits);
    }

    /** True for the fact that mirrors the owner's learned language. */
    public static boolean isLanguageFact(String text) {
        return text != null && text.startsWith(LANGUAGE_PREFIX);
    }

    private static void upsert(
            MemoryBook book,
            UUID owner,
            String ownerName,
            Capture capture,
            long now,
            MemoryLimits limits) {
        Fact existing = null;
        for (Fact fact : book.facts(Subject.player(owner))) {
            if (slotOf(fact.text()).filter(capture.slot()::equals).isPresent()) {
                existing = fact;
                break;
            }
        }
        try {
            if (existing == null) {
                book.addFact(Subject.player(owner), capture.category(), capture.text(), owner, ownerName, now, limits);
            } else if (!existing.text().equals(capture.text())) {
                book.editFact(existing.id(), capture.category(), capture.text(), owner, ownerName, now, limits);
            }
        } catch (MemoryLimitException e) {
            // Full or duplicate memory keeps the profile it already has; a turn must not fail over this.
        }
    }

    private static Optional<String> match(String text, Rule rule) {
        Matcher matcher = rule.pattern().matcher(text);
        while (matcher.find()) {
            if (rule.canonicalPrefix().equals(ADDRESS_PREFIX) && negated(text, matcher.start())) {
                continue;
            }
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    /** "Don't call me X" and "не называй меня X" say the opposite, so they are not an address. */
    private static boolean negated(String text, int start) {
        String before = text.substring(Math.max(0, start - NEGATION_LOOKBACK), start).toLowerCase(Locale.ROOT);
        return before.contains("don't") || before.contains("do not") || before.contains("не ");
    }

    private static String addressValue(String raw) {
        String value = cutAddress(raw);
        if (value.isBlank() || value.length() > MAX_ADDRESS_CHARS) {
            return null;
        }
        String[] words = value.split("\\s+");
        if (words.length > MAX_ADDRESS_WORDS) {
            return null;
        }
        if (CLAUSE_STARTS.contains(words[0].toLowerCase(Locale.ROOT))) {
            return null;
        }
        return acceptable(value) ? value : null;
    }

    private static String preferenceValue(String raw) {
        String value = cutSentence(raw);
        if (value.isBlank() || value.length() > MAX_PREFERENCE_CHARS) {
            return null;
        }
        return acceptable(value) ? value : null;
    }

    /** A bare pronoun or a value with no letters is not a fact worth keeping. */
    private static boolean acceptable(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (BARE_VALUES.contains(lower)) {
            return false;
        }
        return lower.codePoints().anyMatch(Character::isLetter);
    }

    private static String cutAddress(String raw) {
        String value = cutSentence(raw);
        Matcher clause = ADDRESS_CLAUSE_END.matcher(value);
        if (clause.find()) {
            value = value.substring(0, clause.start());
        }
        return tidy(value);
    }

    private static String cutSentence(String raw) {
        Matcher end = SENTENCE_END.matcher(raw);
        return tidy(end.find() ? raw.substring(0, end.start()) : raw);
    }

    /** Removes edge punctuation and a trailing "please" until neither is left ("Босс, пожалуйста" → "Босс"). */
    private static String tidy(String raw) {
        String value = raw;
        String previous;
        do {
            previous = value;
            value = EDGE_PUNCTUATION.matcher(value).replaceAll("");
            value = POLITENESS.matcher(value).replaceAll("");
            value = value.strip();
        } while (!value.equals(previous));
        return value;
    }

    private static String clean(String message) {
        if (message == null) {
            return "";
        }
        return COMMAND.matcher(QUOTED.matcher(message).replaceAll(" ")).replaceAll(" ");
    }

    /** The slot a canonical fact belongs to: one address, one language, one fact per preferred thing. */
    private static Optional<String> slotOf(String text) {
        if (text == null) {
            return Optional.empty();
        }
        if (text.startsWith(ADDRESS_PREFIX)) {
            return Optional.of("address");
        }
        if (text.startsWith(LANGUAGE_PREFIX)) {
            return Optional.of("language");
        }
        if (text.startsWith(LIKES_PREFIX)) {
            return Optional.of(preferenceSlot(body(text, LIKES_PREFIX)));
        }
        if (text.startsWith(DISLIKES_PREFIX)) {
            return Optional.of(preferenceSlot(body(text, DISLIKES_PREFIX)));
        }
        if (text.startsWith(PREFERS_PREFIX)) {
            return Optional.of(preferenceSlot(body(text, PREFERS_PREFIX)));
        }
        return Optional.empty();
    }

    private static String preferenceSlot(String thing) {
        return "preference:" + thing.toLowerCase(Locale.ROOT);
    }

    private static String body(String text, String prefix) {
        String rest = text.substring(prefix.length()).strip();
        return rest.endsWith(".") ? rest.substring(0, rest.length() - 1) : rest;
    }

    private static Pattern address(String prefix) {
        return Pattern.compile("(?<!\\p{L})" + prefix + "(.{1," + MAX_ADDRESS_CHARS + "})", FLAGS);
    }

    private static Pattern preference(String prefix) {
        return Pattern.compile(prefix + "(.{2," + MAX_PREFERENCE_CHARS + "})", FLAGS);
    }
}
