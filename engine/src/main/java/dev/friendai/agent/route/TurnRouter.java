package dev.friendai.agent.route;

import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.turn.TurnKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides, without a model call, what a turn is for. The rules are deliberately blunt: anything unclear is an
 * ordinary action with every group unlocked, so a wrong guess costs tokens but never capability. Planning is a
 * second, equally blunt decision from {@link PlanningPolicy}, made from the mode and score this class resolved.
 */
public final class TurnRouter {
    /** Longest player text looked at; the rest is ignored. */
    static final int MAX_TEXT = 2_000;
    /** Distinct kinds of work that make a request an objective rather than a single action. */
    static final int GOAL_SCORE = 2;

    private final IntentVocabulary vocabulary;
    private final PlanningPolicy planningPolicy;

    public TurnRouter(IntentVocabulary vocabulary) {
        this(vocabulary, new PlanningPolicy());
    }

    public TurnRouter(IntentVocabulary vocabulary, PlanningPolicy planningPolicy) {
        this.vocabulary = vocabulary;
        this.planningPolicy = planningPolicy;
    }

    /** True only when the text contains a recognized trading action verb, not merely a trading topic. */
    public boolean explicitlyRequestsTrading(String playerText) {
        if (playerText == null || playerText.isBlank()) return false;
        String lower = playerText.length() > MAX_TEXT ? playerText.substring(0, MAX_TEXT) : playerText;
        return vocabulary.verbsIn(tokens(lower.toLowerCase(Locale.ROOT))).values().stream()
                .anyMatch(groups -> groups.contains(ToolGroup.TRADING));
    }

    public RouteDecision route(RouteSignals s) {
        return route(s, PlannerMode.AUTO);
    }

    /** Routes the turn and applies the configured planner eagerness to its planning decision. */
    public RouteDecision route(RouteSignals s, PlannerMode plannerMode) {
        PlannerMode mode = plannerMode == null ? PlannerMode.AUTO : plannerMode;
        // Read the player's words before the plan branch: a failed plan can arrive beside a new order, and the
        // order is what the planner should plan. Empty text keeps the recovery turn's subject the troubled plan.
        String text = s.playerText().length() > MAX_TEXT ? s.playerText().substring(0, MAX_TEXT) : s.playerText();
        String lower = text.toLowerCase(Locale.ROOT).strip();
        List<String> tokens = tokens(lower);
        Map<String, Set<ToolGroup>> verbs = vocabulary.verbsIn(tokens);
        int kinds = kinds(vocabulary.verbGroupsPerWord(tokens));
        boolean complex = vocabulary.hasComplexMarker(lower);
        boolean planRequest = vocabulary.hasPlanRequest(lower);
        if (s.planBlockedOrFailed()) {
            return new RouteDecision(
                    TurnMode.RECOVERY,
                    all(),
                    plan(mode, TurnMode.RECOVERY, s, text, kinds, complex, planRequest),
                    "a plan failed or needs a decision");
        }
        if (s.turn() == TurnKind.EVENT || lower.isEmpty()) {
            TurnMode turnMode = s.turn() == TurnKind.EVENT ? TurnMode.EVENT : TurnMode.ACTION;
            return new RouteDecision(
                    turnMode,
                    all(),
                    plan(mode, turnMode, s, text, 0, false, false),
                    s.turn() == TurnKind.EVENT ? "no player message" : "empty player message");
        }
        if (vocabulary.isConversation(tokens)) {
            return new RouteDecision(
                    TurnMode.CHAT,
                    EnumSet.of(ToolGroup.INFO, ToolGroup.MEMORY, ToolGroup.BODY),
                    PlanningDecision.none("conversation"),
                    "a greeting or acknowledgement");
        }
        Set<ToolGroup> groups = EnumSet.noneOf(ToolGroup.class);
        verbs.values().forEach(groups::addAll);
        groups.addAll(vocabulary.topicGroupsIn(tokens));

        boolean question = lower.endsWith("?") || vocabulary.startsWithQuestionMarker(lower, tokens);
        if (question && vocabulary.isAdviceQuestion(lower)) {
            return new RouteDecision(
                    TurnMode.CHAT,
                    EnumSet.of(ToolGroup.INFO, ToolGroup.MEMORY, ToolGroup.INVENTORY),
                    plan(mode, TurnMode.CHAT, s, text, 0, false, false),
                    "a shopping advice question, not an order");
        }
        if (question && groups.contains(ToolGroup.TRADING) && vocabulary.startsWithQuestionMarker(lower, tokens)) {
            Set<ToolGroup> chatGroups =
                    EnumSet.of(ToolGroup.INFO, ToolGroup.MEMORY, ToolGroup.INVENTORY, ToolGroup.TRADING);
            return new RouteDecision(
                    TurnMode.CHAT,
                    chatGroups,
                    plan(mode, TurnMode.CHAT, s, text, 0, false, false),
                    "a question about remembered market facts, not an order");
        }
        if (question && verbs.isEmpty()) {
            Set<ToolGroup> chatGroups = EnumSet.of(ToolGroup.INFO, ToolGroup.MEMORY, ToolGroup.INVENTORY);
            if (groups.contains(ToolGroup.TRADING)) chatGroups.add(ToolGroup.TRADING);
            return new RouteDecision(
                    TurnMode.CHAT,
                    chatGroups,
                    plan(mode, TurnMode.CHAT, s, text, 0, false, false),
                    "a question with no action in it");
        }
        int score = kinds + (complex ? 1 : 0) + (planRequest ? 1 : 0);
        if (!s.hasActiveGoal() && score >= GOAL_SCORE) {
            groups.add(ToolGroup.MOVEMENT);
            groups.add(ToolGroup.INFO);
            return new RouteDecision(
                    TurnMode.GOAL,
                    groups.isEmpty() ? all() : groups,
                    plan(mode, TurnMode.GOAL, s, text, kinds, complex, planRequest),
                    "several kinds of work (score " + score + ")");
        }
        if (groups.isEmpty()) {
            return new RouteDecision(
                    TurnMode.ACTION,
                    all(),
                    plan(mode, TurnMode.ACTION, s, text, kinds, complex, planRequest),
                    "no recognized words");
        }
        // Movement and the read-only info tools (find_trees, find_blocks, player_view) help every action.
        groups.add(ToolGroup.MOVEMENT);
        groups.add(ToolGroup.INFO);
        return new RouteDecision(
                TurnMode.ACTION,
                groups,
                plan(mode, TurnMode.ACTION, s, text, kinds, complex, planRequest),
                "matched " + groups);
    }

    private PlanningDecision plan(
            PlannerMode plannerMode,
            TurnMode mode,
            RouteSignals s,
            String text,
            int kinds,
            boolean complex,
            boolean planRequest) {
        return planningPolicy.beforeTurn(planSignals(mode, s, text, kinds, complex, planRequest), plannerMode);
    }

    private static PlanSignals planSignals(
            TurnMode mode, RouteSignals s, String text, int kinds, boolean complex, boolean planRequest) {
        return new PlanSignals(
                mode,
                text,
                kinds,
                complex,
                planRequest,
                s.goalHasNoQueuedWork(),
                s.goalUnmet(),
                s.troubledPlanQueued(),
                s.freeTimeShift(),
                s.ownerCaller(),
                s.budgetSpent(),
                s.troubledPlanId(),
                s.failure(),
                s.goalId(),
                s.unmet(),
                s.shiftId());
    }

    private static Set<ToolGroup> all() {
        return EnumSet.allOf(ToolGroup.class);
    }

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static List<String> tokens(String lower) {
        List<String> tokens = new ArrayList<>();
        for (String token : NON_WORD.split(lower)) {
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
