package dev.friendai.brain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells whether a line repeats something the companion just said, even when the words differ.
 *
 * <p>Lexical, not semantic: it compares content words after dropping filler and stripping inflections, so
 * "I'm giving you three cutlets, I'll keep three" and "Here, three cutlets are yours" count as one
 * statement while a line about fuel running out does not. Background turns use it to stay quiet instead of
 * announcing the same result twice, which is why it errs on the side of short replies: a repeated line is
 * worth less than a missed confirmation.
 */
final class SpeechRepeat {
    /** How much of the new line must already have been said for it to count as a repeat. */
    private static final double SHARED_OVERLAP = 0.5;
    /** Below two shared words, a match is coincidence rather than a repeat. */
    private static final int MIN_SHARED_WORDS = 2;
    /** Stripping shorter words leaves fragments that collide by accident. */
    private static final int MIN_STEM_LENGTH = 3;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    /** "Not found" and "found" are different statements, whatever else they share. */
    private static final Pattern NEGATION = Pattern.compile(
            "(?iu)(?<![\\p{L}])(?:not|no|never|none|nobody|nothing|don'?t|doesn'?t|didn'?t|isn'?t"
                    + "|aren'?t|wasn'?t|won'?t|can'?t|cannot"
                    + "|не|ни|нет|никогда|ничего|никто|нельзя)(?![\\p{L}])");

    /** Words that carry no information on their own. Negations stay out: "not done" is not "done". */
    private static final Set<String> FILLER = Set.copyOf(List.of(
            // English
            "the", "a", "an", "and", "or", "but", "if", "then", "than", "is", "are", "was", "were", "be",
            "been", "being", "am", "i", "you", "he", "she", "it", "we", "they", "me", "my", "your", "our",
            "their", "us", "them", "his", "her", "its", "to", "of", "in", "on", "at", "for", "with", "from",
            "by", "as", "that", "this", "these", "those", "do", "does", "did", "will", "would", "can",
            "could", "should", "shall", "may", "might", "must", "have", "has", "had", "just", "now", "so",
            "also", "too", "up", "out", "about", "here", "there", "very", "all", "any", "some", "ok",
            "okay", "yeah", "well", "still", "already", "yet", "please", "let", "lets", "one",
            // Russian
            "и", "а", "но", "или", "я", "ты", "он", "она", "оно", "мы", "вы", "они", "меня", "мне", "тебя",
            "тебе", "его", "ему", "ей", "нас", "нам", "вас", "вам", "их", "им", "себя", "себе", "мой",
            "моя", "мое", "мои", "твой", "твоя", "твое", "твои", "свой", "своя", "свое", "свои", "наш",
            "наша", "наши", "ваш", "ваша", "ваши", "это", "этот", "эта", "эти", "то", "тот", "та", "те",
            "так", "как", "что", "чтобы", "же", "ли", "бы", "уже", "еще", "сейчас", "тут", "там", "вот",
            "только", "очень", "все", "весь", "вся", "есть", "был", "была", "было", "были", "будет",
            "буду", "будут", "в", "во", "на", "с", "со", "к", "ко", "о", "об", "обо", "от", "ото", "до",
            "по", "за", "из", "изо", "для", "при", "про", "без", "через", "над", "под", "между", "если",
            "потом", "да", "нет"));

    /** Endings worth stripping, longest first so "жареные" loses "ые" and not "е". */
    private static final List<String> ENDINGS = List.of(
            "tion", "иями", "ями", "ами", "ыми", "ого", "его", "ому", "ему", "ing", "ах", "ях", "ам", "ям",
            "ов", "ев", "ий", "ый", "ая", "ое", "ые", "ие", "ую", "юю", "ой", "ей", "ом", "ем", "ых", "их",
            "es", "ed", "ly");

    private SpeechRepeat() {}

    /** True when {@code candidate} says the same as any of the {@code earlier} lines. */
    static boolean repeatsAny(String candidate, List<String> earlier) {
        if (candidate == null || earlier == null || earlier.isEmpty()) {
            return false;
        }
        for (String line : earlier) {
            if (repeats(candidate, line)) {
                return true;
            }
        }
        return false;
    }

    /** True when the two lines carry the same statement, whatever their wording. */
    static boolean repeats(String candidate, String earlier) {
        if (candidate == null || earlier == null) {
            return false;
        }
        String nowFlat = flatten(candidate);
        if (!nowFlat.isEmpty() && nowFlat.equals(flatten(earlier))) {
            return true;
        }
        if (negated(candidate) != negated(earlier)) {
            return false;
        }
        Set<String> now = words(candidate);
        Set<String> before = words(earlier);
        if (now.isEmpty() || before.isEmpty()) {
            return false;
        }
        int shared = 0;
        for (String word : now) {
            if (before.contains(word)) {
                shared++;
            }
        }
        if (shared < MIN_SHARED_WORDS) {
            // A one-word line ("Done.") is only a repeat of the same one word.
            return now.size() == 1 && shared == 1;
        }
        return shared >= SHARED_OVERLAP * now.size();
    }

    /** True when the line says something is not the case. */
    private static boolean negated(String text) {
        return NEGATION.matcher(text).find();
    }

    /** The content words of a line, lowercased and stemmed. */
    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String token : tokens(text)) {
            if (FILLER.contains(token) || (token.length() == 1 && !Character.isDigit(token.charAt(0)))) {
                continue;
            }
            words.add(stem(token));
        }
        return words;
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /** Lowercased, punctuation and spacing dropped: catches a line repeated word for word. */
    private static String flatten(String text) {
        return String.join(" ", tokens(text));
    }

    private static String stem(String word) {
        if (word.length() <= MIN_STEM_LENGTH) {
            return word;
        }
        for (String ending : ENDINGS) {
            if (word.endsWith(ending) && word.length() - ending.length() >= MIN_STEM_LENGTH) {
                return word.substring(0, word.length() - ending.length());
            }
        }
        int end = word.length();
        while (end > MIN_STEM_LENGTH && isWeakTail(word.charAt(end - 1))) {
            end--;
        }
        return word.substring(0, end);
    }

    /** Vowels, soft signs and the plural "s" inflect words without changing their meaning. */
    private static boolean isWeakTail(char c) {
        return "аеёиоуыэюяйьъaeiouys".indexOf(Character.toLowerCase(c)) >= 0;
    }
}
