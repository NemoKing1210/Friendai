package dev.friendai.foundation.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guesses the language of a message deterministically from its script and a few common words.
 *
 * <p>Only the languages this mod can reasonably meet are named: Russian and Ukrainian on Cyrillic, English,
 * German, French and Spanish on Latin. Everything else — a mixed message, a short one, a Latin sentence with no
 * stopword evidence — is unknown, because a wrong guess is worse than no guess: it must not flip the owner's
 * stored language. Quoted spans and {@code /commands} are ignored, so a quoted command is not evidence.
 */
public final class LanguageDetector {
    /** Below this many letters a message is too short to name a language. */
    public static final int MIN_LETTERS = 8;
    /** One script must own at least this share of the letters. */
    private static final double SCRIPT_SHARE = 0.8;
    /** A Latin language needs at least this many stopword hits. */
    private static final int MIN_STOPWORDS = 2;

    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|`[^`]*`");
    private static final Pattern COMMAND = Pattern.compile("(?<!\\S)/\\S+");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private static final Set<String> ENGLISH = Set.of(
            "the", "and", "you", "is", "are", "to", "of", "it", "that", "this", "for", "with", "have", "what", "do",
            "not", "my", "me", "a", "i", "can", "could", "would", "please", "from", "there", "bring");
    private static final Set<String> GERMAN = Set.of(
            "der", "die", "das", "und", "ist", "nicht", "ich", "du", "ein", "eine", "mit", "auf", "fur", "zu", "es",
            "wir", "mir", "bitte", "kannst", "kann", "aus", "holen");
    private static final Set<String> FRENCH = Set.of(
            "le", "la", "les", "et", "est", "pas", "je", "tu", "il", "elle", "un", "une", "pour", "avec", "que", "qui",
            "dans", "du", "des", "au", "mon", "ma", "ce", "cette", "coffre");
    private static final Set<String> SPANISH = Set.of(
            "el", "la", "los", "las", "y", "es", "no", "que", "un", "una", "para", "con", "por", "como", "muy", "esta",
            "puedes", "traerme", "pico", "cofre");
    private static final Set<String> PORTUGUESE = Set.of(
            "o", "a", "os", "as", "e", "nao", "que", "um", "uma", "para", "com", "por", "como", "muito", "esta",
            "voce", "pode", "trazer", "picareta", "bau");

    private LanguageDetector() {}

    /** The English name of the message's language, or empty when it is too short, mixed or unknown. */
    public static Optional<String> detect(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String cleaned = COMMAND.matcher(QUOTED.matcher(text).replaceAll(" ")).replaceAll(" ");
        int cyrillic = 0;
        int latin = 0;
        int letters = 0;
        for (int i = 0; i < cleaned.length(); ) {
            int codePoint = cleaned.codePointAt(i);
            i += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            letters++;
            switch (Character.UnicodeScript.of(codePoint)) {
                case CYRILLIC -> cyrillic++;
                case LATIN -> latin++;
                default -> {
                    // A third script makes the message mixed, not a sample of either side.
                }
            }
        }
        if (letters < MIN_LETTERS) {
            return Optional.empty();
        }
        if (cyrillic >= letters * SCRIPT_SHARE) {
            return Optional.of(cyrillicLanguage(cleaned));
        }
        if (latin >= letters * SCRIPT_SHARE) {
            return latinLanguage(cleaned);
        }
        return Optional.empty();
    }

    /** Ukrainian has letters Russian does not; without them Cyrillic here is Russian, the mod's second language. */
    private static String cyrillicLanguage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.indexOf('і') >= 0 || lower.indexOf('ї') >= 0 || lower.indexOf('є') >= 0 || lower.indexOf('ґ') >= 0
                ? "Ukrainian"
                : "Russian";
    }

    private static Optional<String> latinLanguage(String text) {
        int english = 0;
        int german = 0;
        int french = 0;
        int spanish = 0;
        int portuguese = 0;
        var words = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (words.find()) {
            String word = unaccented(words.group());
            if (ENGLISH.contains(word)) english++;
            if (GERMAN.contains(word)) german++;
            if (FRENCH.contains(word)) french++;
            if (SPANISH.contains(word)) spanish++;
            if (PORTUGUESE.contains(word)) portuguese++;
        }
        int best = Math.max(Math.max(english, german), Math.max(Math.max(french, spanish), portuguese));
        if (best < MIN_STOPWORDS) {
            return Optional.empty();
        }
        int winners = 0;
        String name = null;
        int[] scores = {english, german, french, spanish, portuguese};
        String[] names = {"English", "German", "French", "Spanish", "Portuguese"};
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] == best) {
                winners++;
                name = names[i];
            }
        }
        return winners == 1 ? Optional.of(name) : Optional.empty();
    }

    private static String unaccented(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
