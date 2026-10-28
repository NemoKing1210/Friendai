package dev.friendai.features.speech.tts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Turns an LLM reply into clean utterances that a speech engine can say. */
public final class SpeechText {
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|ftp)://\\S+");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[.!?…])\\s+");

    private SpeechText() {}

    /** @return non-blank utterances of at most {@code maxChars} characters, in reading order */
    public static List<String> cleanAndSplit(String text, int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String clean = FENCED_CODE.matcher(text).replaceAll(" ");
        clean = LINK.matcher(clean).replaceAll("$1");
        clean = URL.matcher(clean).replaceAll(" ");
        clean = HEADING.matcher(clean).replaceAll("");
        clean = stripMarkdown(clean);
        clean = WHITESPACE.matcher(clean).replaceAll(" ").strip();
        if (clean.isEmpty()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : SENTENCE.split(clean)) {
            String sentence = raw.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            while (sentence.length() > maxChars) {
                int cut = sentence.lastIndexOf(' ', maxChars);
                if (cut <= 0) {
                    cut = maxChars;
                }
                add(out, current, sentence.substring(0, cut).strip(), maxChars);
                sentence = sentence.substring(cut).strip();
            }
            add(out, current, sentence, maxChars);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static void add(List<String> out, StringBuilder current, String chunk, int maxChars) {
        if (chunk.isEmpty()) {
            return;
        }
        if (current.length() > 0 && current.length() + 1 + chunk.length() > maxChars) {
            out.add(current.toString());
            current.setLength(0);
        }
        if (current.length() > 0) {
            current.append(' ');
        }
        current.append(chunk);
    }

    private static String stripMarkdown(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '*' && c != '_' && c != '`' && c != '~') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
