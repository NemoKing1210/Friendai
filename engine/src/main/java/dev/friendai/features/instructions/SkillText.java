package dev.friendai.features.instructions;

import dev.friendai.features.memory.CompanionDescription;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Bounded plain text, with no evaluation, addresses or known credentials. */
public final class SkillText {
    private static final Pattern UNSAFE = Pattern.compile(
            "(?im)(```|^[ \\t]*(?:/|\\$ |powershell |cmd /|bash |curl |wget )|[a-z][a-z0-9+.-]*://|www\\.|"
                    + "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|\\b(?:api[_ -]?key|authorization|password|token|secret)\\s*[:=]|"
                    + "\\b(?:[a-z0-9-]+\\.)+[a-z]{2,63}\\b|(?<![a-z0-9])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]{0,39}|"
                    + "\\b(?:sk-[A-Za-z0-9_-]{12,}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\b)");

    private SkillText() {}

    public static String body(String raw, UnaryOperator<String> redact) {
        return clean(raw, SkillLimits.BODY, true, redact);
    }

    public static String clean(String raw, int max, boolean multiline, UnaryOperator<String> redact) {
        if (raw == null || raw.length() > max || !raw.equals(redact.apply(raw))) throw invalid();
        String value = CompanionDescription.plain(raw, max, multiline);
        if (UNSAFE.matcher(value).find()) throw invalid();
        return value;
    }

    public static SkillDraft validate(SkillDraft draft, UnaryOperator<String> redact) {
        String name = clean(draft.name(), 80, false, redact);
        String body = body(draft.body(), redact);
        if (name.isBlank() || body.isBlank()) throw invalid();
        return new SkillDraft(
                name,
                clean(draft.description(), 200, false, redact),
                body,
                draft.enabled(),
                draft.alwaysOn(),
                draft.turnKinds(),
                terms(draft.triggers(), redact),
                terms(draft.tags(), redact),
                draft.includeProfile());
    }

    private static List<String> terms(List<String> values, UnaryOperator<String> redact) {
        if (values.size() > 12) throw invalid();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String text = clean(value, 40, false, redact);
            if (text.isBlank()) throw invalid();
            if (!result.contains(text)) result.add(text);
        }
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid instruction text.");
    }
}
