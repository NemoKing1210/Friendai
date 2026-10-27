package dev.friendai.agent.route;

import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.brain.TurnKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides, without a model call, what a turn is for. The rules are deliberately blunt: anything unclear is an
 * ordinary action with every group unlocked, so a wrong guess costs tokens but never capability.
 */
public final class TurnRouter {
    /** Longest player text looked at; the rest is ignored. */
    static final int MAX_TEXT = 2_000;
    /** Distinct kinds of work that make a request an objective rather than a single action. */
    static final int GOAL_SCORE = 2;

    private final IntentVocabulary vocabulary;

    public TurnRouter(IntentVocabulary vocabulary) {
        this.vocabulary = vocabulary;
    }

    /** True only when the text contains a recognized trading action verb, not merely a trading topic. */
    public boolean explicitlyRequestsTrading(String playerText) {
        if (playerText == null || playerText.isBlank()) return false;
        String lower = playerText.length() > MAX_TEXT ? playerText.substring(0, MAX_TEXT) : playerText;
        return vocabulary.verbsIn(tokens(lower.toLowerCase(Locale.ROOT))).values().stream()
                .anyMatch(groups -> groups.contains(ToolGroup.TRADING));
    }

    public RouteDecision route(RouteSignals s) {
        if (s.planBlockedOrFailed()) {
            return new RouteDecision(TurnMode.RECOVERY, all(), s.hasActiveGoal(), "a plan failed or needs a decision");
        }
        String text = s.playerText().length() > MAX_TEXT ? s.playerText().substring(0, MAX_TEXT) : s.playerText();
        String lower = text.toLowerCase(Locale.ROOT).strip();
        if (s.turn() == TurnKind.EVENT || lower.isEmpty()) {
            return s.turn() == TurnKind.EVENT
                    ? new RouteDecision(TurnMode.EVENT, all(), false, "no player message")
                    : new RouteDecision(TurnMode.ACTION, all(), false, "empty player message");
        }
        List<String> tokens = tokens(lower);
        Map<String, Set<ToolGroup>> verbs = vocabulary.verbsIn(tokens);
        Set<ToolGroup> groups = EnumSet.noneOf(ToolGroup.class);
        verbs.values().forEach(groups::addAll);
        groups.addAll(vocabulary.topicGroupsIn(tokens));

        boolean question = lower.endsWith("?") || vocabulary.startsWithQuestionMarker(lower, tokens);
        if (question && vocabulary.isAdviceQuestion(lower)) {
            return new RouteDecision(TurnMode.CHAT, EnumSet.of(ToolGroup.INFO, ToolGroup.MEMORY, ToolGroup.INVENTORY),
                    false, "a shopping advice question, not an order");
        }
        if (question && groups.contains(ToolGroup.TRADING)
                && vocabulary.startsWithQuestionMarker(lower, tokens)) {
            Set<ToolGroup> chatGroups = EnumSet.of(ToolGroup.INFO, ToolGroup.MEMORY, ToolGroup.INVENTORY,
                    ToolGroup.TRADING);
            return new RouteDecision(TurnMode.CHAT, chatGroups, false,
                    "a question about remembered market facts, not an order");
        }
        if (question && verbs.isEmpty()) {
            Set<ToolGroup> chatGroups = EnumSet.of(ToolGroup.INFO, ToolGroup.MEMORY, ToolGroup.INVENTORY);
            if (groups.contains(ToolGroup.TRADING)) chatGroups.add(ToolGroup.TRADING);
            return new RouteDecision(TurnMode.CHAT, chatGroups,
                    false, "a question with no action in it");
        }
        int score = kinds(verbs.values())
                + (vocabulary.hasComplexMarker(lower) ? 1 : 0)
                + (vocabulary.hasPlanRequest(lower) ? 1 : 0);
        if (!s.hasActiveGoal() && score >= GOAL_SCORE) {
            groups.add(ToolGroup.MOVEMENT);
            groups.add(ToolGroup.INFO);
            return new RouteDecision(TurnMode.GOAL, groups.isEmpty() ? all() : groups, true,
                    "several kinds of work (score " + score + ")");
        }
        if (groups.isEmpty()) {
            return new RouteDecision(TurnMode.ACTION, all(), false, "no recognized words");
        }
        // Movement and the read-only info tools (find_trees, find_blocks, player_view) help every action.
        groups.add(ToolGroup.MOVEMENT);
        groups.add(ToolGroup.INFO);
        return new RouteDecision(TurnMode.ACTION, groups, false, "matched " + groups);
    }

    private static Set<ToolGroup> all() {
        return EnumSet.allOf(ToolGroup.class);
    }

    private static List<String> tokens(String lower) {
        List<String> tokens = new ArrayList<>();
        for (String token : lower.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Verbs whose groups overlap are one kind of work ("build" and "place" are both building). */
    private static int kinds(Iterable<Set<ToolGroup>> verbGroups) {
        List<Set<ToolGroup>> clusters = new ArrayList<>();
        for (Set<ToolGroup> groups : verbGroups) {
            Set<ToolGroup> merged = EnumSet.noneOf(ToolGroup.class);
            merged.addAll(groups);
            List<Set<ToolGroup>> overlapping = new ArrayList<>();
            for (Set<ToolGroup> cluster : clusters) {
                if (!java.util.Collections.disjoint(cluster, merged)) {
                    overlapping.add(cluster);
                    merged.addAll(cluster);
                }
            }
            clusters.removeAll(overlapping);
            clusters.add(merged);
        }
        return clusters.size();
    }
}
