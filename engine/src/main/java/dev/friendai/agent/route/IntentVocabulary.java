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
    private final List<String> adviceQuestionMarkers;
    private final Map<String, Set<ToolGroup>> actionVerbs;
    private final Map<String, Set<ToolGroup>> topicWords;
    private final List<String> complexMarkers;
    private final List<String> planMarkers;
    private final List<String> planProposalMarkers;
    private final List<String> planApprovalMarkers;
    private final List<String> conversationMarkers;
    private final List<String> statementNegations;
    private final List<String> tradeNegationMarkers;
    private final List<String> tradeConditionalMarkers;
    private final List<String> tradeDeterminers;

    IntentVocabulary(
            List<String> questionMarkers,
            List<String> adviceQuestionMarkers,
            Map<String, Set<ToolGroup>> actionVerbs,
            Map<String, Set<ToolGroup>> topicWords,
            List<String> complexMarkers,
            List<String> planMarkers,
            List<String> planProposalMarkers,
            List<String> planApprovalMarkers,
            List<String> conversationMarkers,
            List<String> statementNegations,
            List<String> tradeNegationMarkers,
            List<String> tradeConditionalMarkers,
            List<String> tradeDeterminers) {
        this.questionMarkers = questionMarkers;
        this.adviceQuestionMarkers = adviceQuestionMarkers;
        this.actionVerbs = actionVerbs;
        this.topicWords = topicWords;
        this.complexMarkers = complexMarkers;
        this.planMarkers = planMarkers;
        this.planProposalMarkers = List.copyOf(planProposalMarkers);
        this.planApprovalMarkers = List.copyOf(planApprovalMarkers);
        this.conversationMarkers = List.copyOf(conversationMarkers);
        this.statementNegations = List.copyOf(statementNegations);
        this.tradeNegationMarkers = List.copyOf(tradeNegationMarkers);
        this.tradeConditionalMarkers = List.copyOf(tradeConditionalMarkers);
        this.tradeDeterminers = List.copyOf(tradeDeterminers);
    }

    /** Both languages merged. */
    public static IntentVocabulary load() {
        List<String> questions = new ArrayList<>();
        List<String> adviceQuestions = new ArrayList<>();
        Map<String, Set<ToolGroup>> verbs = new LinkedHashMap<>();
        Map<String, Set<ToolGroup>> topics = new LinkedHashMap<>();
        List<String> complex = new ArrayList<>();
        List<String> plans = new ArrayList<>();
        List<String> planProposals = new ArrayList<>();
        List<String> planApprovals = new ArrayList<>();
        List<String> conversation = new ArrayList<>();
        List<String> negations = new ArrayList<>();
        List<String> tradeNegations = new ArrayList<>();
        List<String> tradeConditionals = new ArrayList<>();
        List<String> tradeDeterminers = new ArrayList<>();
        for (String file : List.of("/agent/intents_en.json", "/agent/intents_ru.json")) {
            JsonObject json = read(file);
            strings(json, "question_markers", questions);
            strings(json, "advice_question_markers", adviceQuestions);
            groups(json, "action_verbs", verbs);
            groups(json, "topic_words", topics);
            strings(json, "complex_markers", complex);
            strings(json, "plan_request_markers", plans);
            strings(json, "plan_proposal_markers", planProposals);
            strings(json, "plan_approval_markers", planApprovals);
            strings(json, "conversation_markers", conversation);
            strings(json, "statement_negations", negations);
            strings(json, "trade_negation_markers", tradeNegations);
            strings(json, "trade_conditional_markers", tradeConditionals);
            strings(json, "trade_determiners", tradeDeterminers);
        }
        return new IntentVocabulary(
                questions,
                adviceQuestions,
                verbs,
                topics,
                complex,
                plans,
                planProposals,
                planApprovals,
                conversation,
                negations,
                tradeNegations,
                tradeConditionals,
                tradeDeterminers);
    }

    /** Exact whole-utterance matches only: a greeting beside an order must never hide that order's tools. */
    public boolean isConversation(List<String> tokens) {
        return conversationMarkers.contains(String.join(" ", tokens));
    }

    /**
     * True when the message opens with a marker that revises the order it answers. Only the opening counts: "no,
     * build it out of stone" replaces the pending plan, while "the wall is fine, no need to change it" is ordinary
     * conversation and "and also bring coal" is an independent order.
     */
    public boolean marksCorrection(String text) {
        if (text == null) return false;
        String lower = text.length() > TurnRouter.MAX_TEXT
                ? text.substring(0, TurnRouter.MAX_TEXT).toLowerCase(java.util.Locale.ROOT)
                : text.toLowerCase(java.util.Locale.ROOT);
        List<String> words = words(lower);
        if (words.isEmpty()) return false;
        String normalized = String.join(" ", words);
        for (String marker : statementNegations) {
            if (normalized.equals(marker) || normalized.startsWith(marker + " ")) {
                return true;
            }
        }
        return false;
    }

    public boolean startsWithQuestionMarker(String lowerText, List<String> tokens) {
        for (String marker : questionMarkers) {
            if (marker.contains(" ")
                    ? lowerText.startsWith(marker)
                    : (!tokens.isEmpty() && tokens.get(0).equals(marker))) {
                return true;
            }
        }
        return false;
    }

    /** Questions asking for a recommendation should not expose tools that can spend items. */
    public boolean isAdviceQuestion(String lowerText) {
        return adviceQuestionMarkers.stream().anyMatch(lowerText::contains);
    }

    /** The groups of every action verb in the tokens, keyed by verb stem, in first-seen order. */
    public Map<String, Set<ToolGroup>> verbsIn(List<String> tokens) {
        return match(actionVerbs, tokens);
    }

    /**
     * The action groups each word names, one entry per word that names any. Counting kinds of work by word, not by
     * stem, keeps one verb that two stems match ("добудь" is both a gathering stem and an obtain verb) one kind.
     */
    public List<Set<ToolGroup>> verbGroupsPerWord(List<String> tokens) {
        List<Set<ToolGroup>> words = new ArrayList<>();
        for (String token : tokens) {
            Set<ToolGroup> groups = EnumSet.noneOf(ToolGroup.class);
            for (Map.Entry<String, Set<ToolGroup>> entry : actionVerbs.entrySet()) {
                if (matches(token, entry.getKey())) groups.addAll(entry.getValue());
            }
            if (!groups.isEmpty()) words.add(groups);
        }
        return words;
    }

    public Set<ToolGroup> topicGroupsIn(List<String> tokens) {
        Set<ToolGroup> groups = EnumSet.noneOf(ToolGroup.class);
        match(topicWords, tokens).values().forEach(groups::addAll);
        return groups;
    }

    public boolean hasComplexMarker(String lowerText) {
        return containsWords(complexMarkers, lowerText);
    }

    public boolean hasPlanRequest(String lowerText) {
        return containsWords(planMarkers, lowerText);
    }

    /**
     * True when the owner asks to see a plan before any work starts: "show me the plan", "just the plan", "do
     * nothing yet". The wording is what withholds execution; the plan request itself is {@link #hasPlanRequest}.
     */
    public boolean hasPlanProposalRequest(String lowerText) {
        return containsWords(planProposalMarkers, lowerText);
    }

    /**
     * True only for an unambiguous approval of the plan just shown: the whole message is an approval phrase such as
     * "go ahead", "do it" or "давай". An approval beside new conditions is a changed request, not consent, so it is
     * never read as one.
     */
    public boolean isPlanApproval(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.length() > TurnRouter.MAX_TEXT
                ? text.substring(0, TurnRouter.MAX_TEXT).toLowerCase(java.util.Locale.ROOT)
                : text.toLowerCase(java.util.Locale.ROOT);
        String words = String.join(" ", words(lower));
        return planApprovalMarkers.contains(words);
    }

    /**
     * True only when the message gives a trade as an instruction: a trading verb used in the imperative, outside
     * quotes, not negated in its clause, and not part of a question, advice request or conditional. The words alone
     * decide, so a recovery, fallback or proposal turn cannot turn a discussion about trading into permission to
     * spend the owner's items.
     */
    public boolean isAffirmativeTradeInstruction(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = (text.length() > TurnRouter.MAX_TEXT ? text.substring(0, TurnRouter.MAX_TEXT) : text)
                .toLowerCase(java.util.Locale.ROOT);
        String unquoted =
                SINGLE_QUOTED.matcher(QUOTED.matcher(lower).replaceAll(" ")).replaceAll(" ");
        String stripped = unquoted.strip();
        List<String> tokens = words(stripped);
        if (tokens.isEmpty()) return false;
        if (isAdviceQuestion(stripped)) return false;
        if (containsWords(tradeConditionalMarkers, stripped)) return false;
        if (stripped.endsWith("?") || startsWithQuestionMarker(stripped, tokens)) return false;
        for (String clause : CLAUSE_BREAK.split(unquoted)) {
            List<String> clauseWords = words(clause);
            if (!clauseWords.isEmpty() && clauseIsTradeInstruction(clauseWords)) return true;
        }
        return false;
    }

    private boolean clauseIsTradeInstruction(List<String> clause) {
        int since = 0;
        for (int i = 0; i < clause.size(); i++) {
            String word = clause.get(i);
            if (CONTRAST.contains(word)) {
                since = i + 1;
                continue;
            }
            for (Map.Entry<String, Set<ToolGroup>> entry : actionVerbs.entrySet()) {
                if (!entry.getValue().contains(ToolGroup.TRADING) || !matches(word, entry.getKey())) continue;
                if (!instructionForm(word, entry.getKey())) continue;
                if (i > 0 && tradeDeterminers.contains(clause.get(i - 1))) continue;
                if (negatedBetween(clause, since, i)) continue;
                return true;
            }
        }
        return false;
    }

    /** A negation marker between the clause start and the verb negates it: "do not buy", "не покупай". */
    private boolean negatedBetween(List<String> clause, int since, int verbIndex) {
        if (verbIndex <= since) return false;
        String before = " " + String.join(" ", clause.subList(since, verbIndex)) + " ";
        for (String marker : tradeNegationMarkers) {
            List<String> markerWords = words(marker);
            if (!markerWords.isEmpty() && before.contains(" " + String.join(" ", markerWords) + " ")) return true;
        }
        return false;
    }

    /**
     * A trading word counts as an order only in its base form: a Latin-script word must equal its stem ("buy", not
     * "purchased"); a Cyrillic word must end in an imperative or infinitive ending rather than a past or present-tense
     * one ("купи", "продай", "пополнить", not "купил" or "покупает").
     */
    private static boolean instructionForm(String word, String stem) {
        boolean cyrillic =
                word.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC);
        if (!cyrillic) return word.equals(stem);
        if (word.endsWith("ли")) return false;
        return word.endsWith("й") || word.endsWith("и") || word.endsWith("ь") || word.endsWith("те");
    }

    /** Quoted spans are reported speech or a dictation, never the owner's own instruction. */
    private static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile("\"[^\"]*\"|«[^»]*»|“[^”]*”");
    /** A single-quoted span; the apostrophe inside a word ("don't") does not open one. */
    private static final java.util.regex.Pattern SINGLE_QUOTED =
            java.util.regex.Pattern.compile("(?<![\\p{L}])'[^']+'(?![\\p{L}])");
    /** Sentence punctuation ends a clause; a contrastive conjunction restarts the negation window inside one. */
    private static final java.util.regex.Pattern CLAUSE_BREAK = java.util.regex.Pattern.compile("[.!?;:,]+");
    /** Conjunctions that start a fresh statement, so "don't sell it, but buy a map" still orders the purchase. */
    private static final Set<String> CONTRAST = Set.of("but", "however", "но", "однако", "а");

    /**
     * True when one marker appears as whole words: "plan" is not found in "planks" or "plant", nor "then" in
     * "strengthen". An inflected form is listed as its own marker.
     */
    private static boolean containsWords(List<String> markers, String lowerText) {
        String words = " " + String.join(" ", words(lowerText)) + " ";
        for (String marker : markers) {
            List<String> markerWords = words(marker);
            if (!markerWords.isEmpty() && words.contains(" " + String.join(" ", markerWords) + " ")) return true;
        }
        return false;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String word : NON_WORD.split(text)) {
            if (!word.isEmpty()) words.add(word);
        }
        return words;
    }

    private static final java.util.regex.Pattern NON_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");

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
        // "farmer" is a noun/topic, not an inflection of the action verb "farm".
        if (stem.equals("farm") && (token.equals("farmer") || token.equals("farmers"))) {
            return false;
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
