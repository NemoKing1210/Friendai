package dev.friedai.crafting.obtain;

import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/** LLM-facing texts for obtaining items; the formats live here so they stay unit-tested. */
public final class ObtainText {
    private ObtainText() {}

    /** {@code to get 1 minecraft:iron_pickaxe: mine …, craft …; about 9 steps}. */
    public static String planText(ObtainPlan plan, String target, int count) {
        return planText(plan, target, count, Map.of());
    }

    /**
     * The plan text, knowing what the companion carries: a tool it already has gets no "will craft one"
     * annotation on the steps that need it.
     */
    public static String planText(ObtainPlan plan, String target, int count, Map<String, Integer> inventory) {
        if (!plan.ok()) {
            return "can't get " + count + " " + target + " here: " + missingText(plan.missing(), false);
        }
        String header = "to get " + count + " " + target + ": ";
        if (plan.steps().isEmpty()) {
            return header + "nothing to do; about 0 steps";
        }
        StringJoiner steps = new StringJoiner(", ");
        for (ObtainStep step : plan.steps()) {
            String text = stepText(step);
            if (step instanceof ObtainStep.Mine mine && annotateTool(mine.tool(), plan.steps(), inventory)) {
                text += " (needs a " + mine.tool().words() + ": will craft one)";
            }
            steps.add(text);
        }
        return header + steps + "; about " + plan.estimatedSteps() + " steps";
    }

    /** {@code missing: 1 × minecraft:diamond (exploring is off)}. */
    public static String missingText(Map<String, Integer> missing, boolean exploreOff) {
        if (missing == null || missing.isEmpty()) {
            return exploreOff ? "missing: nothing (exploring is off)" : "missing: nothing";
        }
        String list = missing.entrySet().stream()
                .map(entry -> entry.getValue() + " × " + entry.getKey())
                .collect(Collectors.joining(", "));
        return "missing: " + list + (exploreOff ? " (exploring is off)" : "");
    }

    /** One step in words, for notices and summaries. */
    public static String stepText(ObtainStep step) {
        return switch (step) {
            case ObtainStep.Take take -> "take " + take.count() + " × " + take.item();
            case ObtainStep.Craft craft -> craft.count() == 1
                    ? "craft " + craft.item()
                    : "craft " + craft.count() + " × " + craft.item();
            case ObtainStep.Smelt smelt -> "smelt " + smelt.count() + " × " + smelt.input();
            case ObtainStep.Mine mine -> "mine " + mine.count() + " × " + mine.block();
            case ObtainStep.Hunt hunt -> "hunt " + hunt.count() + " × " + hunt.mob();
            case ObtainStep.Harvest harvest -> "harvest " + harvest.count() + " × " + harvest.crop();
            case ObtainStep.Shear shear -> "shear " + shear.count() + " × " + shear.mob();
            case ObtainStep.Milk milk -> "milk " + milk.count() + (milk.count() == 1 ? " bucket" : " buckets");
            case ObtainStep.Explore explore -> explore.down()
                    ? "explore down to Y " + explore.depthY() + " for " + explore.block()
                    : "explore up to 64 blocks around for " + explore.block();
        };
    }

    /**
     * True when the tool is neither carried nor listed as its own craft step, so the step's text has to say
     * that the tool will be made on the way.
     */
    private static boolean annotateTool(ToolNeed tool, java.util.List<ObtainStep> steps, Map<String, Integer> inventory) {
        String item = tool.vanillaItem();
        if (item == null) {
            return false;
        }
        if (inventory != null && inventory.getOrDefault(item, 0) > 0) {
            return false;
        }
        for (ObtainStep step : steps) {
            if (step instanceof ObtainStep.Craft craft && craft.item().equals(item)) {
                return false;
            }
        }
        return true;
    }
}
