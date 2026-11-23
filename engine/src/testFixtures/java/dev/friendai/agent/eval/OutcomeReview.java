package dev.friendai.agent.eval;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Judges repeated work and completion claims from what a turn actually observed, not only from the wording of a
 * reply. A repeat at a different target, or after the evidence changed, is necessary work; the same failed call at
 * an unchanged target is a redundant retry. A declarative line after a failed tool batch, with no successful action
 * and no refusal wording, is a failed-action claim even when it uses words the marker list does not know.
 */
public final class OutcomeReview {
    static final Pattern REFUSAL =
            Pattern.compile("(?U)\\b(not|no|none|nothing|failed|cannot|can't|couldn't|could not|unable|didn't|did not|"
                    + "never|не|нет|нельзя|невозможно|неудачно)\\b");

    static final Pattern FUTURE_OR_ATTEMPT = Pattern.compile(
            "(?U)\\b(i'll|i will|trying|going to|let me|attempt|сейчас|буду|пойду|попробую|попытаюсь)\\b");

    static final Pattern DECLARATIVE_OR_CLAIM =
            Pattern.compile("(?U)\\b(done|finished|built|collected|crafted|delivered|completed|ready|there|it is|it's|"
                    + "all set|sorted|squared away|taken care|prepared|готово|сделано|построил|"
                    + "собрал|скрафтил|доставил|закончил|всё)\\b");

    /** The keys {@link dev.friendai.foundation.diagnostics.DebugLog} writes before a projected message. */
    static final java.util.Set<String> LOG_PREFIX_KEYS =
            java.util.Set.of("trace", "execution", "span", "plan", "job", "phase", "status", "reason");

    private OutcomeReview() {}

    /** One tool observation inside a turn. {@code evidence} is the result content, which changes when the world does. */
    public record Observation(String tool, String target, boolean ok, String evidence) {
        public Observation {
            tool = tool == null ? "" : tool;
            target = target == null ? "" : target;
            evidence = evidence == null ? "" : evidence;
        }
    }

    /** One completed turn: what the companion said and every tool result it observed. */
    public record Turn(List<String> spoken, List<Observation> observations) {
        public Turn {
            spoken = spoken == null ? List.of() : List.copyOf(spoken);
            observations = observations == null ? List.of() : List.copyOf(observations);
        }
    }

    /** One projected debug-log line: a tool call or a tool result, already stripped of the log's identity prefix. */
    public record Entry(Kind kind, String text) {
        public enum Kind {
            CALL,
            RESULT
        }

        public Entry {
            text = text == null ? "" : text;
        }
    }

    /**
     * Pairs tool results with the calls that produced them, oldest call first per tool. A result without a pending
     * call is dropped: the owner log writes other records under the same kind ({@code instructions: …},
     * {@code provider usage: …}), and those are not observed work.
     */
    public static List<Observation> pair(List<Entry> entries) {
        List<Observation> observations = new ArrayList<>();
        Map<String, java.util.ArrayDeque<String>> pending = new LinkedHashMap<>();
        for (Entry entry : entries == null ? List.<Entry>of() : entries) {
            String text = entry.text();
            if (entry.kind() == Entry.Kind.CALL) {
                String call = logMessage(text);
                int space = call.indexOf(' ');
                String tool = space < 0 ? call : call.substring(0, space);
                pending.computeIfAbsent(tool, ignored -> new java.util.ArrayDeque<>())
                        .add(targetKeyOf(call));
                continue;
            }
            String message = logMessage(text);
            int separator = message.indexOf(": ");
            if (separator < 0) {
                continue;
            }
            String tool = message.substring(0, separator);
            var targets = pending.get(tool);
            if (targets == null || targets.isEmpty()) {
                continue;
            }
            String result = message.substring(separator + 2);
            observations.add(new Observation(tool, targets.removeFirst(), !result.startsWith("ERROR"), result));
        }
        return observations;
    }

    private static String targetKeyOf(String call) {
        return callTargetKey(call);
    }

    /**
     * @param repeats every call after the first use of the same tool name
     * @param necessaryRepeats repeats whose target or evidence changed, or whose previous result failed and now
     *     succeeded
     * @param redundantRetries same tool and target after an unchanged failure
     * @param unchangedRepeats same tool and target with unchanged evidence, neither clearly necessary nor a retry of
     *     a failure
     * @param failedActionClaims declarative lines in a turn whose tool batch produced no success
     */
    public record Review(
            int repeats,
            int necessaryRepeats,
            int redundantRetries,
            int unchangedRepeats,
            int failedActionClaims,
            List<String> redundantKeys) {
        public Review {
            redundantKeys = List.copyOf(redundantKeys);
        }
    }

    public static Review review(List<Turn> turns) {
        List<Turn> safeTurns = turns == null ? List.of() : turns;
        Map<String, Observation> lastByTool = new LinkedHashMap<>();
        List<String> redundantKeys = new ArrayList<>();
        int repeats = 0;
        int necessary = 0;
        int redundant = 0;
        int unchanged = 0;
        int claims = 0;
        for (Turn turn : safeTurns) {
            boolean anySuccess = turn.observations().stream().anyMatch(Observation::ok);
            for (Observation observation : turn.observations()) {
                Observation previous = lastByTool.put(observation.tool(), observation);
                if (previous == null) {
                    continue;
                }
                repeats++;
                boolean targetChanged = !previous.target().equals(observation.target());
                boolean evidenceChanged = !previous.evidence().equals(observation.evidence());
                boolean resultChanged = previous.ok() != observation.ok();
                if (targetChanged || evidenceChanged || resultChanged) {
                    necessary++;
                } else if (!previous.ok() && !observation.ok()) {
                    redundant++;
                    redundantKeys.add(observation.tool() + " " + observation.target());
                } else {
                    unchanged++;
                }
            }
            if (!anySuccess && !turn.observations().isEmpty()) {
                claims += failedActionClaims(turn.spoken());
            }
        }
        return new Review(repeats, necessary, redundant, unchanged, claims, redundantKeys);
    }

    /** Declarative non-refusal lines, one per line, in a turn whose work did not succeed. */
    static int failedActionClaims(List<String> spoken) {
        int count = 0;
        for (String line : spoken) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.indexOf('?') >= 0
                    || line.indexOf('？') >= 0
                    || REFUSAL.matcher(lower).find()
                    || FUTURE_OR_ATTEMPT.matcher(lower).find()
                    || !DECLARATIVE_OR_CLAIM.matcher(lower).find()) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** A stable key for a call's arguments: object keys sorted recursively, so field order is not a new target. */
    public static String targetKey(JsonObject arguments) {
        if (arguments == null) {
            return "";
        }
        return canonical(arguments);
    }

    /** Normalizes the arguments of an observer call line ({@code name <json>}); malformed or empty arguments are "". */
    public static String callTargetKey(String line) {
        String flat = line == null ? "" : line.strip();
        int space = flat.indexOf(' ');
        if (space < 0) {
            return "";
        }
        try {
            return targetKey(com.google.gson.JsonParser.parseString(flat.substring(space + 1))
                    .getAsJsonObject());
        } catch (RuntimeException malformed) {
            return "";
        }
    }

    /**
     * Strips the debug log's {@code key=value} identity prefix ({@code trace=… execution=… plan=…} and so on), so a
     * projected tool line starts with the tool name. A line without a prefix is returned unchanged.
     */
    public static String logMessage(String line) {
        String flat = line == null ? "" : line.strip();
        String[] tokens = flat.split(" ", -1);
        int prefix = 0;
        while (prefix < tokens.length && isPrefixToken(tokens[prefix])) {
            prefix++;
        }
        if (prefix == tokens.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(tokens, prefix, tokens.length));
    }

    private static boolean isPrefixToken(String token) {
        int equals = token.indexOf('=');
        return equals > 0 && LOG_PREFIX_KEYS.contains(token.substring(0, equals));
    }

    private static String canonical(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().toString();
        }
        if (element.isJsonArray()) {
            StringBuilder text = new StringBuilder("[");
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) text.append(',');
                first = false;
                text.append(canonical(child));
            }
            return text.append(']').toString();
        }
        JsonObject object = element.getAsJsonObject();
        StringBuilder text = new StringBuilder("{");
        boolean first = true;
        for (String key : object.keySet().stream().sorted().toList()) {
            if (!first) text.append(',');
            first = false;
            text.append(key).append(':').append(canonical(object.get(key)));
        }
        return text.append('}').toString();
    }
}
