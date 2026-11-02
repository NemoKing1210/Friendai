package dev.friendai.execution.queue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure prerequisite analysis for a blocked plan action. The adapter classifies the blocker and names the remedy tool
 * from feature-owned metadata; this class only decides whether the prerequisite is satisfied, missing, unknown or
 * unsupported, and whether a bounded repair may be inserted.
 *
 * <p>Unknown world evidence is never treated as missing: only a definite false (no harvest tool, no station, low food,
 * no space) becomes a repair. An offline owner, a protected target, spend, trade and creative authority are
 * unsupported on purpose: those are owner decisions, not prerequisites a plan may satisfy for itself.
 *
 * <p>Bounds: at most {@link #MAX_PER_BLOCKER} steps per blocker and {@link #MAX_PER_PLAN} inserted steps in one plan,
 * deduplicated by tool and arguments. Exhaustion returns a named blocker rather than a silent partial repair.
 */
public final class PlanRepair {
    public static final int MAX_PER_BLOCKER = 3;
    public static final int MAX_PER_PLAN = 16;

    public enum Outcome {
        SATISFIED,
        MISSING,
        UNKNOWN,
        UNSUPPORTED;

        /** One short English explanation for a finding. */
        public String reason() {
            return switch (this) {
                case SATISFIED -> "";
                case MISSING -> "missing";
                case UNKNOWN -> "the world cannot be read";
                case UNSUPPORTED -> "an owner decision";
            };
        }
    }

    /** One prerequisite the adapter knows how to check and, when missing, how to obtain. */
    public sealed interface Prerequisite permits Item, Harvest, Station, Food, Space, OwnerOnline {
        String describe();
    }

    /** A carried item count; the inventory is known, so a short count is a definite missing. */
    public record Item(String item, int count, String remedyTool) implements Prerequisite {
        public Item {
            item = id(item);
            if (count < 1) throw new IllegalArgumentException("count");
            remedyTool = remedyTool == null ? "obtain_item" : remedyTool.strip();
        }

        @Override
        public String describe() {
            return count + " " + item;
        }
    }

    /** Whether a carried tool can harvest a block, through the vanilla harvest requirement. */
    public record Harvest(String block) implements Prerequisite {
        public Harvest {
            block = id(block);
        }

        @Override
        public String describe() {
            return "a tool for " + block;
        }
    }

    /** A usable station within a radius. */
    public record Station(String station, int radius) implements Prerequisite {
        public Station {
            station = id(station);
            if (radius < 1 || radius > 32) throw new IllegalArgumentException("radius");
        }

        @Override
        public String describe() {
            return station + " within " + radius + " blocks";
        }
    }

    /** Food points; a negative fact means the caller cannot know them. */
    public record Food(int points, String remedyTool) implements Prerequisite {
        public Food {
            if (points < 0 || points > 20) throw new IllegalArgumentException("points");
            remedyTool = remedyTool == null ? "get_food" : remedyTool.strip();
        }

        @Override
        public String describe() {
            return "food at least " + points;
        }
    }

    /** Empty inventory slots. */
    public record Space(int slots, String remedyTool) implements Prerequisite {
        public Space {
            if (slots < 1) throw new IllegalArgumentException("slots");
            remedyTool = remedyTool == null ? "store_items" : remedyTool.strip();
        }

        @Override
        public String describe() {
            return slots + " empty slots";
        }
    }

    /** The owner being online is a wait, not a repair. */
    public record OwnerOnline() implements Prerequisite {
        @Override
        public String describe() {
            return "owner online";
        }
    }

    /** One prerequisite verdict; a missing one carries the remedy tool the adapter proposed. */
    public record Finding(Prerequisite prerequisite, Outcome outcome, String remedyTool, String reason) {
        public Finding {
            reason = reason == null ? "" : reason;
        }
    }

    /** One bounded proposed insertion. */
    public record Step(String tool, JsonObject args, String reason) {
        public Step {
            tool = id(tool);
            args = args == null ? new JsonObject() : args.deepCopy();
            reason = reason == null ? "" : reason;
        }
    }

    /** The complete analysis: findings, accepted steps, named blockers and whether a cap cut the repair. */
    public record Advice(List<Finding> findings, List<Step> steps, List<String> blockers, boolean truncated) {
        public Advice {
            findings = List.copyOf(findings);
            steps = List.copyOf(steps);
            blockers = List.copyOf(blockers);
        }
    }

    private PlanRepair() {}

    /**
     * @param existingActions actions already in the plan, so the per-plan cap counts the whole plan
     * @param availableTools  tools the requesting principal may call now
     * @param autoRepair      false guarantees no step is proposed
     */
    public static Advice advise(
            List<Prerequisite> prerequisites,
            GuardFacts facts,
            Set<String> availableTools,
            boolean autoRepair,
            int existingActions) {
        if (prerequisites == null || prerequisites.isEmpty()) {
            return new Advice(List.of(), List.of(), List.of(), false);
        }
        List<Finding> findings = new ArrayList<>(prerequisites.size());
        List<Step> steps = new ArrayList<>();
        Set<String> blockers = new LinkedHashSet<>();
        Map<String, Integer> perBlocker = new HashMap<>();
        Map<String, Boolean> seenSteps = new LinkedHashMap<>();
        boolean truncated = false;
        for (Prerequisite prerequisite : prerequisites) {
            if (prerequisite == null) {
                continue;
            }
            Outcome outcome = evaluate(prerequisite, facts);
            Finding finding = new Finding(prerequisite, outcome, remedyTool(prerequisite), outcome.reason());
            findings.add(finding);
            if (outcome != Outcome.MISSING) {
                if (outcome == Outcome.UNSUPPORTED) {
                    blockers.add(prerequisite.describe() + ": " + finding.reason());
                }
                continue;
            }
            if (!autoRepair) {
                findings.set(
                        findings.size() - 1,
                        new Finding(prerequisite, outcome, finding.remedyTool(), "auto repair is off"));
                continue;
            }
            String tool = finding.remedyTool();
            if (tool == null || tool.isBlank() || !availableTools.contains(tool)) {
                findings.set(
                        findings.size() - 1,
                        new Finding(prerequisite, Outcome.UNSUPPORTED, tool, "remedy tool is not available"));
                blockers.add(prerequisite.describe() + ": remedy tool is not available");
                continue;
            }
            int used = perBlocker.getOrDefault(prerequisite.describe(), 0);
            if (used >= MAX_PER_BLOCKER) {
                truncated = true;
                blockers.add(prerequisite.describe() + ": repair cap " + MAX_PER_BLOCKER);
                continue;
            }
            if (existingActions + steps.size() >= MAX_PER_PLAN) {
                truncated = true;
                blockers.add(prerequisite.describe() + ": plan repair cap " + MAX_PER_PLAN);
                continue;
            }
            JsonObject args = remedyArgs(prerequisite);
            String key = tool + "\u0000" + args;
            if (seenSteps.putIfAbsent(key, Boolean.TRUE) != null) {
                continue;
            }
            perBlocker.merge(prerequisite.describe(), 1, Integer::sum);
            steps.add(new Step(tool, args, prerequisite.describe()));
        }
        return new Advice(findings, steps, List.copyOf(blockers), truncated);
    }

    /** The verdict for one prerequisite; a null fact is unknown, never false. */
    public static Outcome evaluate(Prerequisite prerequisite, GuardFacts facts) {
        if (prerequisite == null) {
            return Outcome.UNKNOWN;
        }
        if (prerequisite instanceof Item item) {
            return facts.countItems(item.item()) >= item.count() ? Outcome.SATISFIED : Outcome.MISSING;
        }
        if (prerequisite instanceof Harvest harvest) {
            Boolean can = facts.canHarvest(harvest.block());
            return can == null ? Outcome.UNKNOWN : can ? Outcome.SATISFIED : Outcome.MISSING;
        }
        if (prerequisite instanceof Station station) {
            Boolean there = facts.stationWithin(station.station(), station.radius());
            return there == null ? Outcome.UNKNOWN : there ? Outcome.SATISFIED : Outcome.MISSING;
        }
        if (prerequisite instanceof Food food) {
            int level = facts.foodLevel();
            if (level < 0) return Outcome.UNKNOWN;
            return level >= food.points() ? Outcome.SATISFIED : Outcome.MISSING;
        }
        if (prerequisite instanceof Space space) {
            int slots = facts.emptySlots();
            if (slots < 0) return Outcome.UNKNOWN;
            return slots >= space.slots() ? Outcome.SATISFIED : Outcome.MISSING;
        }
        if (prerequisite instanceof OwnerOnline) {
            Boolean online = facts.ownerOnline();
            return online == null ? Outcome.UNKNOWN : online ? Outcome.SATISFIED : Outcome.UNSUPPORTED;
        }
        return Outcome.UNKNOWN;
    }

    private static String remedyTool(Prerequisite prerequisite) {
        return switch (prerequisite) {
            case Item item -> item.remedyTool();
            case Food food -> food.remedyTool();
            case Space space -> space.remedyTool();
            case Harvest ignored -> "obtain_item";
            case Station ignored -> "craft_item";
            case OwnerOnline ignored -> "";
        };
    }

    private static JsonObject remedyArgs(Prerequisite prerequisite) {
        JsonObject args = new JsonObject();
        switch (prerequisite) {
            case Item item -> {
                args.addProperty("item", item.item());
                args.addProperty("count", item.count());
            }
            case Harvest harvest -> args.addProperty("block", harvest.block());
            case Station station -> args.addProperty("block", station.station());
            case Food food -> args.addProperty("points", food.points());
            case Space space -> args.addProperty("slots", space.slots());
            case OwnerOnline ignored -> {}
        }
        return args;
    }

    private static String id(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("blank id");
        return value.strip();
    }
}
