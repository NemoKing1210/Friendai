package dev.friendai.features.brewing.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.brewing.BrewPlanner;
import dev.friendai.features.brewing.BrewRecipe;
import dev.friendai.features.brewing.BrewRecipes;
import dev.friendai.features.brewing.minecraft.task.BrewCheckpoints;
import dev.friendai.features.brewing.minecraft.task.BrewTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Brewing: a real resumable task at a brewing stand, and a side-effect-free plan of the same chain. */
public final class BrewTools implements FeatureToolContributor {
    private static final String BREW = "brew_potion";
    private static final String PLAN = "plan_brew";

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                        BREW,
                        "Brew a supported potion at a brewing stand using water bottles, the recipe ingredient and blaze "
                                + "powder as fuel. It walks to the nearest stand, or the given coordinates, keeps the "
                                + "intermediate potions in the stand, and reports the potion count and every ingredient spent. "
                                + "A missing bottle, ingredient, fuel or stand is one refusal naming the missing piece. Use "
                                + "plan_brew first when the recipe or amounts are uncertain.",
                        SchemaBuilder.object()
                                .enumeration(
                                        "potion",
                                        "Target potion",
                                        BrewRecipes.targets().toArray(String[]::new))
                                .integer("count", "How many bottles to brew", 1, BrewRecipe.BOTTLES_PER_OPERATION)
                                .integer("x", "Brewing stand X coordinate; omit for the nearest stand")
                                .integer("y", "Brewing stand Y coordinate")
                                .integer("z", "Brewing stand Z coordinate")
                                .required("potion", "count")
                                .build(),
                        (companion, args) -> new BrewTask(
                                args.get("potion").getAsString(),
                                args.get("count").getAsInt(),
                                pos(args)),
                        true,
                        new TaskToolDefinition.QueueTraits(false, "count"),
                        metadata(BREW, "Use brew_potion only for a stand you can reach; it does not place one."))
                .withCheckpoint(BREW, BrewCheckpoints::brew));
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return PLAN;
                    }

                    @Override
                    public String description() {
                        return "Show the steps, operations, fuel uses and ingredients for a supported potion without "
                                + "moving, opening a stand or consuming anything.";
                    }

                    @Override
                    public JsonObject schema() {
                        return SchemaBuilder.object()
                                .enumeration(
                                        "potion",
                                        "Target potion",
                                        BrewRecipes.targets().toArray(String[]::new))
                                .integer(
                                        "count",
                                        "How many bottles the plan is for",
                                        1,
                                        BrewRecipe.BOTTLES_PER_OPERATION)
                                .required("potion")
                                .build();
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        String target = args.get("potion").getAsString();
                        int count = args.has("count") ? args.get("count").getAsInt() : 1;
                        return BrewPlanner.plan(target)
                                .map(plan -> ToolResult.ok(render(plan, count)))
                                .orElseGet(() -> ToolResult.error("unsupported potion " + target + "; supported: "
                                        + String.join(", ", BrewRecipes.targets())));
                    }
                },
                metadata(PLAN, "- Use plan_brew before brew_potion for a potion or count you have not brewed yet."));
    }

    private static String render(BrewRecipe plan, int bottles) {
        StringBuilder text = new StringBuilder("Brew ")
                .append(bottles)
                .append(bottles == 1 ? " bottle" : " bottles")
                .append(" of ")
                .append(plan.target())
                .append(": ")
                .append(plan.operationCount(bottles))
                .append(" stand operation(s), ")
                .append(plan.fuelUses(bottles))
                .append(" fuel use(s). Steps:");
        int index = 1;
        for (var step : plan.steps()) {
            text.append(' ')
                    .append(index++)
                    .append(") ")
                    .append(step.fromPotion())
                    .append(" + ")
                    .append(step.ingredient())
                    .append(" -> ")
                    .append(step.toPotion())
                    .append(';');
        }
        return text.toString();
    }

    private static @Nullable BlockPos pos(JsonObject args) {
        int present = 0;
        for (String name : List.of("x", "y", "z")) {
            if (args.has(name) && !args.get(name).isJsonNull()) {
                present++;
            }
        }
        if (present == 0) {
            return null;
        }
        if (present != 3) {
            throw new IllegalArgumentException("give all of x, y, z or none");
        }
        return new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt());
    }

    private static ToolMeta metadata(String name, String hint) {
        return new ToolMeta(name, ToolGroup.CRAFTING, false, hint, List.of(), Cost.MEDIUM);
    }
}
