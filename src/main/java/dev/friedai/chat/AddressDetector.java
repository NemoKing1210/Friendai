package dev.friedai.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides whether a chat message is addressed to the companion and extracts what it said. */
public record AddressDetector(String name, String prefix, boolean respondToName, boolean listenAll) {
    private static final String WORD_CHARS = "[\\p{L}\\p{N}_]";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** @return the text for the brain, or empty if the message is not for the companion */
    public Optional<String> match(String message) {
        String msg = message.strip();
        Pattern leadingName = Pattern.compile(
                "^" + Pattern.quote(name) + "(?!" + WORD_CHARS + ")[,:!]?\\s*", FLAGS);

        if (prefix != null && !prefix.isEmpty() && msg.startsWith(prefix)) {
            String rest = msg.substring(prefix.length()).stripLeading();
            rest = leadingName.matcher(rest).replaceFirst("");
            return Optional.of(rest.isBlank() ? message : rest.strip());
        }
        if (respondToName) {
            Pattern anywhere = Pattern.compile(
                    "(?<!" + WORD_CHARS + ")" + Pattern.quote(name) + "(?!" + WORD_CHARS + ")", FLAGS);
            Matcher m = anywhere.matcher(msg);
            if (m.find()) {
                if (m.start() == 0) {
                    String rest = leadingName.matcher(msg).replaceFirst("");
                    return Optional.of(rest.isBlank() ? message : rest.strip());
                }
                return Optional.of(message);
            }
        }
        return listenAll ? Optional.of(message) : Optional.empty();
    }
}
