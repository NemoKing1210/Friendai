package dev.friendai.agent.goal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.QueuedStep;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks that a goal's criteria and a queued plan cover every measurable requirement of the owner's order. Pure:
 * the caller supplies the parsed order, the criteria the model chose, and the plan it wants to queue.
 */
public final class GoalCoverage {
    /** One requirement that neither the criteria nor the plan proves. */
    public record Missing(String item, int ownerCount, int companionCount, String reason) {
        public String describe() {
            StringBuilder text = new StringBuilder(item);
            if (ownerCount > 0) text.append(": owner ").append(ownerCount);
            if (companionCount > 0) {
                if (ownerCount > 0) text.append(", ");
                text.append("keep ").append(companionCount);
            }
            if (reason != null && !reason.isBlank())
                text.append(" (").append(reason).append(')');
            return text.toString();
        }
    }

    /** The requirements left uncovered and the free-form criteria that cannot prove anything. */
    public record Result(List<Missing> missing, List<String> unresolved) {
        public Result {
            missing = List.copyOf(missing);
            unresolved = List.copyOf(unresolved);
        }

        public boolean covered() {
            return missing.isEmpty();
        }

        public String describeMissing() {
            return String.join("; ", missing.stream().map(Missing::describe).toList());
        }
    }

    private GoalCoverage() {}

    /**
     * Whether a plan is about to do every measurable part of the order. This is an admission check: a planned step
     * is intent, not evidence, so it never answers {@link #checkProof}.
     */
    public static Result checkPlan(OrderIntent intent, PlanDefinition plan) {
        List<Missing> missing = new ArrayList<>();
        List<QueuedStep> steps = plan == null ? List.of() : actions(plan.nodes());
        for (OrderIntent.ItemNeed need : intent.items()) {
            int provenOwner = 0;
            int obtained = 0;
            for (QueuedStep step : steps) {
                JsonObject args = step.args();
                if (step.tool().equals("give_items") && inBatch(args, need.item())) {
                    provenOwner += ownerCountIn(args, need.item());
                } else if (isObtain(step.tool())) {
                    obtained += plannedCount(args, need.item());
                }
            }
            int plannedCompanion = 0;
            for (QueuedStep step : steps) {
                if (step.tool().equals("give_items") && inBatch(step.args(), need.item())) {
                    plannedCompanion += retainIn(step.args(), need.item());
                }
            }
            // A plan that obtains more than it hands over leaves the rest with the companion.
            int provenCompanion = Math.max(plannedCompanion, obtained - provenOwner);
            if (intent.possession()) {
                // A possession question is answered from the owner's inventory, so no plan step is required.
                continue;
            }
            if (provenOwner < need.ownerCount()) {
                missing.add(new Missing(
                        need.item(),
                        need.ownerCount(),
                        need.companionCount(),
                        "no delivery of the requested quantity"));
            } else if (provenCompanion < need.companionCount()) {
                missing.add(new Missing(
                        need.item(), need.ownerCount(), need.companionCount(), "the retained share is not planned"));
            }
        }
        for (OrderIntent.BuildNeed build : intent.builds()) {
            // A build is proven by the plan's own step; criteria alone cannot describe a structure's parts.
            if (plan == null || build.parts().isEmpty()) continue;
            Set<String> planned = new LinkedHashSet<>();
            boolean hasBuild = false;
            for (QueuedStep step : steps) {
                if (!step.tool().equals("build_structure")) continue;
                hasBuild = true;
                JsonObject args = step.args();
                if (!args.has("parts") || !args.get("parts").isJsonArray()) {
                    planned.addAll(build.parts());
                    break;
                }
                for (JsonElement part : args.getAsJsonArray("parts")) {
                    planned.add(part.getAsString().toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (!hasBuild) {
                missing.add(new Missing(build.structure(), 0, 0, "no build step plans it"));
            } else if (!planned.containsAll(build.parts())) {
                Set<String> absent = new LinkedHashSet<>(build.parts());
                absent.removeAll(planned);
                missing.add(new Missing(build.structure(), 0, 0, "the build omits " + String.join(", ", absent)));
            }
        }
        return new Result(missing, intent.unresolved());
    }

    /**
     * Whether the world has actually proved every measurable part of the order. Only evidence counts here: a
     * criterion with a guard, never a queued step that has not run. A structure's parts were admitted with its plan;
     * at completion the world proves the resulting blocks through the goal's own criteria.
     */
    public static Result checkProof(OrderIntent intent, List<Criterion> criteria) {
        List<Missing> missing = new ArrayList<>();
        List<Criterion> safeCriteria = criteria == null ? List.of() : criteria;
        for (OrderIntent.ItemNeed need : intent.items()) {
            int provenOwner = 0;
            int provenCompanion = 0;
            for (Criterion criterion : safeCriteria) {
                Guard guard = criterion.check().orElse(null);
                // Only a recorded hand-over or pickup proves a new delivery. Existing stock and a possession check
                // answer a different question and cannot complete an order to bring something.
                if (guard instanceof Guard.HandedOver handover
                        && handover.item().equals(need.item())) {
                    provenOwner = Math.max(provenOwner, handover.count());
                } else if (guard instanceof Guard.PickedUp pickup
                        && pickup.item().equals(need.item())) {
                    provenOwner = Math.max(provenOwner, pickup.count());
                } else if (guard instanceof Guard.Possesses possession
                        && intent.possession()
                        && possession.item().equals(need.item())) {
                    provenOwner = Math.max(provenOwner, possession.count());
                } else if (guard instanceof Guard.HasItem held && held.item().equals(need.item())) {
                    provenCompanion = Math.max(provenCompanion, held.count());
                }
            }
            if (provenOwner < need.ownerCount()) {
                missing.add(new Missing(
                        need.item(),
                        need.ownerCount(),
                        need.companionCount(),
                        intent.possession() ? "no possession criterion for this item" : "no proven delivery"));
            } else if (provenCompanion < need.companionCount()) {
                missing.add(new Missing(
                        need.item(), need.ownerCount(), need.companionCount(), "the retained share is not proven"));
            }
        }
        for (OrderIntent.BuildNeed build : intent.builds()) {
            // A structure is admitted with its plan, but completion still needs a world fact: one of the goal's own
            // criteria must check a block, or the order would close on the model's word alone.
            boolean provedByBlock = safeCriteria.stream()
                    .map(Criterion::check)
                    .flatMap(Optional::stream)
                    .anyMatch(guard -> guard instanceof Guard.BlockAt);
            if (!provedByBlock) {
                missing.add(new Missing(build.structure(), 0, 0, "no block criterion proves the structure"));
            }
        }
        List<String> unresolved = new ArrayList<>();
        for (Criterion criterion : safeCriteria) {
            if (criterion.check().isEmpty()) unresolved.add(criterion.text());
        }
        unresolved.addAll(intent.unresolved());
        return new Result(missing, unresolved);
    }

    /** The action steps of a plan tree, in plan order. */
    public static List<QueuedStep> actions(List<PlanNode> nodes) {
        List<QueuedStep> steps = new ArrayList<>();
        collect(nodes, steps);
        return steps;
    }

    private static void collect(List<PlanNode> nodes, List<QueuedStep> steps) {
        for (PlanNode node : nodes) {
            if (node instanceof PlanNode.Action action) {
                steps.add(action.step());
            } else if (node instanceof PlanNode.Conditional branch) {
                collect(branch.thenNodes(), steps);
                collect(branch.elseNodes(), steps);
            } else if (node instanceof PlanNode.Repeat repeat) {
                collect(repeat.body(), steps);
            }
        }
    }

    private static String itemArg(JsonObject args) {
        return args.has("item") && args.get("item").isJsonPrimitive()
                ? args.get("item").getAsString()
                : "";
    }

    /** How many of this item a step plans to obtain, including a batched {@code items} array. */
    private static int plannedCount(JsonObject args, String item) {
        if (itemArg(args).equals(item)) return intArg(args, "count", 1);
        if (!args.has("items") || !args.get("items").isJsonArray()) return 0;
        int total = 0;
        for (JsonElement element : args.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue;
            JsonObject row = element.getAsJsonObject();
            if (itemArg(row).equals(item)) total += intArg(row, "count", 1);
        }
        return total;
    }

    /** The requested owner quantity of this item in a single or batched give step. */
    private static int ownerCountIn(JsonObject args, String item) {
        if (itemArg(args).equals(item)) return intArg(args, "count", 1);
        int total = 0;
        for (JsonElement element : rows(args)) {
            JsonObject row = element.getAsJsonObject();
            if (itemArg(row).equals(item)) total += intArg(row, "count", 1);
        }
        return total;
    }

    /** The retained share of this item in a single or batched give step. */
    private static int retainIn(JsonObject args, String item) {
        if (itemArg(args).equals(item)) return intArg(args, "retain", 0);
        int total = 0;
        for (JsonElement element : rows(args)) {
            JsonObject row = element.getAsJsonObject();
            if (itemArg(row).equals(item)) total += intArg(row, "retain", 0);
        }
        return total;
    }

    private static java.util.List<JsonElement> rows(JsonObject args) {
        if (!args.has("items") || !args.get("items").isJsonArray()) return java.util.List.of();
        return args.getAsJsonArray("items").asList().stream()
                .filter(JsonElement::isJsonObject)
                .toList();
    }

    /** Whether one of the step's item rows (or its single item) names this item. */
    private static boolean inBatch(JsonObject args, String item) {
        if (itemArg(args).equals(item)) return true;
        if (!args.has("items") || !args.get("items").isJsonArray()) return false;
        for (JsonElement element : args.getAsJsonArray("items")) {
            if (element.isJsonObject() && itemArg(element.getAsJsonObject()).equals(item)) return true;
        }
        return false;
    }

    private static boolean isObtain(String tool) {
        return tool.equals("obtain_item") || tool.equals("obtain_items");
    }

    private static int intArg(JsonObject args, String name, int fallback) {
        return args.has(name) && args.get(name).isJsonPrimitive()
                ? args.get(name).getAsInt()
                : fallback;
    }

    private static boolean booleanArg(JsonObject args, String name, boolean fallback) {
        return args.has(name) && args.get(name).isJsonPrimitive()
                ? args.get(name).getAsBoolean()
                : fallback;
    }
}
