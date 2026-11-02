package dev.friendai.agent.autonomy;

import dev.friendai.foundation.text.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Lines saved for an owner who was not there to hear them, told once when they come back. */
public final class AwaySummary {
    private static final String MESSAGE_PREFIX = "\u0001friendai-message:";
    private static final int MAX_LINE_CHARS = 256;
    private static final String OMITTED_KEY = "friendai.freetime.away_older_omitted";
    private static final String OMITTED_LINE = MESSAGE_PREFIX + OMITTED_KEY;

    private AwaySummary() {}

    public record Taken(List<String> lines, FreeTime state) {
        public Taken {
            lines = List.copyOf(lines);
        }

        public String text() {
            return String.join("; ", lines);
        }
    }

    /** Appends a line, keeping the newest {@link FreeTime#MAX_AWAY}. */
    public static FreeTime add(FreeTime state, String line) {
        if (line == null || line.isBlank()) {
            return state;
        }
        List<String> away = new ArrayList<>(state.away());
        boolean omitted = away.remove(OMITTED_LINE);
        String bounded = line.strip();
        away.add(bounded.length() > MAX_LINE_CHARS ? bounded.substring(0, MAX_LINE_CHARS) : bounded);
        omitted |= away.size() > FreeTime.MAX_AWAY;
        if (omitted) {
            while (away.size() > FreeTime.MAX_AWAY - 1) {
                away.removeFirst();
            }
            away.addFirst(OMITTED_LINE);
        }
        return withAway(state, away);
    }

    /** Stores a translation and its primitive arguments so the owner's client language resolves it on return. */
    public static FreeTime addMessage(FreeTime state, UserMessage message) {
        if (message.args().isEmpty()) {
            return add(state, MESSAGE_PREFIX + message.key());
        }
        List<String> encoded = new ArrayList<>(message.args().size() + 2);
        encoded.add("v2");
        encoded.add(encode(message.key()));
        for (Object arg : message.args()) {
            if (arg instanceof String text) {
                encoded.add("s" + encode(text));
            } else if (arg instanceof Number number) {
                encoded.add("n" + encode(number.toString()));
            } else {
                throw new IllegalArgumentException("Away translation arguments must be strings or numbers.");
            }
        }
        return add(state, MESSAGE_PREFIX + String.join(".", encoded));
    }

    /** The translation key encoded by {@link #addMessage}, or empty for an older literal away line. */
    public static Optional<String> translationKey(String line) {
        return translationMessage(line).map(UserMessage::key);
    }

    /** Reconstructs a saved translation and its primitive arguments, including keys stored by the earlier format. */
    public static Optional<UserMessage> translationMessage(String line) {
        if (line == null || !line.startsWith(MESSAGE_PREFIX)) {
            return Optional.empty();
        }
        String encoded = line.substring(MESSAGE_PREFIX.length());
        if (!encoded.startsWith("v2.")) {
            return Optional.of(UserMessage.of(encoded));
        }
        String[] parts = encoded.split("\\.", -1);
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            String key = decode(parts[1]);
            List<Object> args = new ArrayList<>(parts.length - 2);
            for (int i = 2; i < parts.length; i++) {
                String part = parts[i];
                String value = decode(part.substring(1));
                args.add(
                        switch (part.charAt(0)) {
                            case 's' -> value;
                            case 'n' -> parseNumber(value);
                            default -> throw new IllegalArgumentException("Unknown away argument type.");
                        });
            }
            return Optional.of(new UserMessage(key, args));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static Number parseNumber(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return Double.valueOf(value);
        }
    }

    /** The saved lines joined into one, and the state without them. */
    public static Taken take(FreeTime state) {
        return new Taken(state.away(), withAway(state, List.of()));
    }

    private static FreeTime withAway(FreeTime s, List<String> away) {
        return new FreeTime(
                s.on(),
                s.origin(),
                s.wish(),
                s.sinceMillis(),
                s.shift(),
                s.awaitingChoice(),
                s.nextShiftId(),
                s.history(),
                s.lessons(),
                s.cooldownUntil(),
                away);
    }
}
