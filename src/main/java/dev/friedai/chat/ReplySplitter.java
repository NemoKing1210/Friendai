package dev.friedai.chat;

import java.util.ArrayList;
import java.util.List;

/** Cleans an LLM reply for chat and splits it into lines of at most {@code maxChars}. */
public final class ReplySplitter {
    private ReplySplitter() {}

    public static List<String> split(String text, int maxChars) {
        String clean = text
                .replace("*", "")
                .replace("`", "")
                .replaceAll("(?m)^\\s*#+\\s*", "")
                .replaceAll("\\s*\\R\\s*", " ")
                .strip();
        List<String> out = new ArrayList<>();
        if (clean.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : clean.split("(?<=[.!?…])\\s+")) {
            String rest = sentence;
            if (rest.length() > maxChars) {
                flush(out, current);
                while (rest.length() > maxChars) {
                    int cut = rest.lastIndexOf(' ', maxChars);
                    if (cut > 0) {
                        out.add(rest.substring(0, cut));
                        rest = rest.substring(cut + 1).stripLeading();
                    } else {
                        out.add(rest.substring(0, maxChars));
                        rest = rest.substring(maxChars);
                    }
                }
            }
            if (rest.isEmpty()) {
                continue;
            }
            if (current.length() == 0) {
                current.append(rest);
            } else if (current.length() + 1 + rest.length() <= maxChars) {
                current.append(' ').append(rest);
            } else {
                flush(out, current);
                current.append(rest);
            }
        }
        flush(out, current);
        return out;
    }

    private static void flush(List<String> out, StringBuilder current) {
        if (current.length() > 0) {
            out.add(current.toString());
            current.setLength(0);
        }
    }
}
