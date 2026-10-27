package dev.friendai.agent.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.tools.ToolGroup;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The words the router recognizes, English and Russian, loaded from the {@code agent/intents_*.json} resources.
 * Stems match a token when equal, or as a prefix (four letters or more) or substring (five or more).
 */
public final class IntentVocabulary {
    private final List<String> questionMarkers;
    private final Map<String, Set<ToolGroup>> actionVerbs;
    private final Map<String, Set<ToolGroup>> topicWords;
    private final List<String> complexMarkers;
    private final List<String> planMarkers;

    IntentVocabulary(
            List<String> questionMarkers,
            Map<String, Set<ToolGroup>> actionVerbs,
            Map<String, Set<ToolGroup>> topicWords,
            List<String> complexMarkers,
            List<String> planMarkers) {
        this.questionMarkers = questionMarkers;
        this.actionVerbs = actionVerbs;
        this.topicWords = topicWords;
        this.complexMarkers = complexMarkers;
        this.planMarkers = planMarkers;
    }

    /** Both languages merged. */
    public static IntentVocabulary load() {
        List<String> questions = new ArrayList<>();
        Map<String, Set<ToolGroup>> verbs = new LinkedHashMap<>();
        Map<String, Set<ToolGroup>> topics = new LinkedHashMap<>();
        List<String> complex = new ArrayList<>();
        List<String> plans = new ArrayList<>();
        for (String file : List.of("/agent/intents_en.json", "/agent/intents_ru.json")) {
            JsonObject json = read(file);
            strings(json, "question_markers", questions);
            groups(json, "action_verbs", verbs);
            groups(json, "topic_words", topics);
            strings(json, "complex_markers", complex);
            strings(json, "plan_request_markers", plans);
        }
        return new IntentVocabulary(questions, verbs, topics, complex, plans);
    }

    public boolean startsWithQuestionMarker(String lowerText, List<String> tokens) {
        for (String marker : questionMarkers) {
            if (marker.contains(" ") ? lowerText.startsWith(marker) : (!tokens.isEmpty() && tokens.get(0).equals(marker))) {
                return true;
            }
        }
        return false;
    }

    /** The groups of every action verb in the tokens, keyed by verb stem, in first-seen order. */
    public Map<String, Set<ToolGroup>> verbsIn(List<String> tokens) {
        return match(actionVerbs, tokens);
    }

    public Set<ToolGroup> topicGroupsIn(List<String> tokens) {
        Set<ToolGroup> groups = EnumSet.noneOf(ToolGroup.class);
        match(topicWords, tokens).values().forEach(groups::addAll);
        return groups;
    }

    public boolean hasComplexMarker(String lowerText) {
        return complexMarkers.stream().anyMatch(lowerText::contains);
    }

    public boolean hasPlanRequest(String lowerText) {
        return planMarkers.stream().anyMatch(lowerText::contains);
    }

    private static Map<String, Set<ToolGroup>> match(Map<String, Set<ToolGroup>> table, List<String> tokens) {
        Map<String, Set<ToolGroup>> found = new LinkedHashMap<>();
        for (String token : tokens) {
            for (Map.Entry<String, Set<ToolGroup>> entry : table.entrySet()) {
                if (matches(token, entry.getKey())) {
                    found.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        return found;
    }

    static boolean matches(String token, String stem) {
        if (token.equals(stem)) {
            return true;
        }
        if (stem.length() >= 4 && token.startsWith(stem)) {
            return true;
        }
        return stem.length() >= 5 && token.contains(stem);
    }

    private static JsonObject read(String resource) {
        try (InputStream in = IntentVocabulary.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing vocabulary resource " + resource);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    private static void strings(JsonObject json, String key, List<String> into) {
        if (json.has(key)) {
            for (JsonElement e : json.getAsJsonArray(key)) {
                into.add(e.getAsString().toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    private static void groups(JsonObject json, String key, Map<String, Set<ToolGroup>> into) {
        if (!json.has(key)) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(key).entrySet()) {
            Set<ToolGroup> groups = EnumSet.noneOf(ToolGroup.class);
            JsonArray array = entry.getValue().getAsJsonArray();
            for (JsonElement g : array) {
                ToolGroup.parse(g.getAsString()).ifPresent(groups::add);
            }
            into.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), groups);
        }
    }
}
