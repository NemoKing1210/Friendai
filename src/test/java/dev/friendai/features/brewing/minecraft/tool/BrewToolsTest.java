package dev.friendai.features.brewing.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.MergePolicy;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueueOptimizer;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.features.brewing.BrewRecipe;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrewToolsTest {
    private static TaskToolDefinition brewDefinition() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, BrewToolsTest::taskTool);
        new BrewTools().register(contributions);
        return contributions.taskDefinitions().get("brew_potion");
    }

    @Test
    void brewPotionStatesTheLimitItsTaskAdmits() {
        TaskToolDefinition definition = brewDefinition();

        assertEquals("count", definition.queueTraits().mergeableCountArg());
        assertEquals(BrewRecipe.BOTTLES_PER_OPERATION, definition.queueTraits().mergeLimit());
    }

    @Test
    void twoFullBrewStepsAreNeverFoldedIntoAnInvalidCount() {
        TaskToolDefinition definition = brewDefinition();
        MergePolicy policy = new MergePolicy() {
            @Override
            public String countArgument(String tool) {
                return definition.queueTraits().mergeableCountArg();
            }

            @Override
            public int mergeLimit(String tool) {
                return definition.queueTraits().mergeLimit();
            }
        };
        JobQueue q = new JobQueue(QueueLimits.DEFAULTS);
        UUID owner = UUID.randomUUID();
        q.addPlan("brew", List.of(brew(2, owner), brew(2, owner)), PlanOptions.DEFAULT);

        assertEquals("nothing to merge", QueueOptimizer.apply(q, null, policy).text());
        assertEquals(2, q.jobCount());
    }

    private static QueuedStep brew(int bottles, UUID owner) {
        JsonObject args = new JsonObject();
        args.addProperty("potion", "minecraft:swiftness");
        args.addProperty("count", bottles);
        return new QueuedStep("brew_potion", args, null, List.of(), GuardAction.WAIT, null, 1, List.of(), owner);
    }

    private static Tool<CompanionEntity> taskTool(TaskToolDefinition definition) {
        return new Tool<>() {
            @Override
            public String name() {
                return definition.name();
            }

            @Override
            public String description() {
                return definition.description();
            }

            @Override
            public JsonObject schema() {
                return definition.schema();
            }

            @Override
            public dev.friendai.execution.tool.AccessLevel minAccess() {
                return definition.minAccess();
            }

            @Override
            public dev.friendai.execution.tool.ToolResult execute(CompanionEntity companion, JsonObject args) {
                return dev.friendai.execution.tool.ToolResult.ok("ok");
            }
        };
    }
}
