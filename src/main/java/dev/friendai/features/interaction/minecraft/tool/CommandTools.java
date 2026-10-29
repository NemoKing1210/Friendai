package dev.friendai.features.interaction.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.features.creative.CreativeCommandPolicy;
import dev.friendai.features.interaction.minecraft.task.ExecuteCommandTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/** Owner-only allowlisted commands executed by the companion under the owner's permissions. */
public final class CommandTools implements FeatureToolContributor {
    public CommandTools() {}

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "execute_command",
                "Run one allowlisted gameplay command using only the online owner's normal command permissions. The companion is the command entity and @s; only teleport, time, weather, give-to-owner, effect-on-self, and enchant-owner forms are accepted. execute/function/schedule and other roots are denied. Feedback is bounded.",
                SchemaBuilder.object()
                        .string("command", "One command, with or without one leading slash")
                        .required("command")
                        .build(),
                CommandTools::commandTask,
                CommandTools::commandTask,
                false,
                AccessLevel.OWNER,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                true,
                metadata("execute_command")));
    }

    private static ExecuteCommandTask commandTask(CompanionEntity companion, Caller caller, JsonObject args) {
        if (!companion.creativeMode()) {
            throw new IllegalArgumentException("creative mode must be active to run commands");
        }
        ServerPlayer owner = companion
                .owner()
                .orElseThrow(() -> new IllegalArgumentException("the owner must be online to run commands"));
        if (caller == null || !owner.getUUID().equals(caller.uuid())) {
            throw new IllegalArgumentException("only the owner may run commands");
        }
        String raw = args.has("command") ? args.get("command").getAsString() : "";
        CreativeCommandPolicy.Decision decision =
                CreativeCommandPolicy.evaluate(raw, owner.getName().getString());
        if (!decision.allowed()) throw new IllegalArgumentException(decision.reason());
        return new ExecuteCommandTask(decision.command());
    }

    private static ExecuteCommandTask commandTask(CompanionEntity companion, JsonObject args) {
        ServerPlayer owner = companion
                .owner()
                .orElseThrow(() -> new IllegalArgumentException("the owner must be online to run commands"));
        return commandTask(
                companion, new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER), args);
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(
                name,
                ToolGroup.CONTROL,
                false,
                "- In creative mode, execute_command accepts only allowlisted teleport/time/weather/item/effect/enchant forms and uses the owner's live command permissions. Use @s for yourself, give/enchant only for the owner, and describe the returned feedback without claiming a denied or failed command succeeded.",
                List.of(),
                Cost.LOW);
    }
}
