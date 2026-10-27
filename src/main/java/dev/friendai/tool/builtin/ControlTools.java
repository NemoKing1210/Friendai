package dev.friendai.tool.builtin;

import com.google.gson.JsonObject;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionRenamer;
import dev.friendai.memory.CompanionName;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.task.Task;
import dev.friendai.task.combat.CombatController;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.InventoryAccess;
import dev.friendai.tool.SchemaBuilder;
import dev.friendai.tool.StopScope;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** Control over what the companion is doing. */
final class ControlTools {
    private static final String STOP_REASON = "stopped by the LLM";

    private ControlTools() {}

    private static ToolResult stop(CompanionEntity c, String what) {
        return switch (StopScope.parse(what)) {
            case StopScope.Current ignored -> {
                c.taskRunner().stop(STOP_REASON);
                yield ToolResult.ok("stopped");
            }
            case StopScope.All ignored -> stopEverything(c);
            case StopScope.Named named -> {
                // The model writes "mine blocks" as often as "mine_blocks".
                String name = named.name().replaceAll("\\s+", "_");
                Optional<String> stopped = c.taskRunner().stopNamed(name, STOP_REASON);
                if (stopped.isPresent()) {
                    yield ToolResult.ok("stopped " + stopped.get());
                }
                String current = c.taskRunner().current().map(Task::describe).orElse("none");
                yield ToolResult.error("not running: " + name + "; current task: " + current);
            }
        };
    }

    private static ToolResult stopEverything(CompanionEntity c) {
        String task = c.taskRunner().current().map(Task::describe).orElse("no task");
        c.taskRunner().stopAll(STOP_REASON);
        StringBuilder text = new StringBuilder("stopped everything: ").append(task);
        CombatController.State before = c.combatController().state();
        c.combatController().stopFight();
        if (c.combatController().state() != before) {
            text.append("; ended the fight");
        }
        int jobs = c.furnaceJobs().clear();
        if (jobs > 0) {
            text.append("; dropped ").append(jobs).append(jobs == 1 ? " furnace job" : " furnace jobs");
        }
        return ToolResult.ok(text.toString());
    }

    private static ToolResult showInventory(CompanionEntity c) {
        ServerPlayer owner = c.owner().orElse(null);
        if (owner == null) {
            return ToolResult.error("the owner is offline");
        }
        InventoryAccess.Denial denial = FriendaiMenus.open(owner, c);
        return switch (denial) {
            case NONE -> ToolResult.ok("opened my inventory for " + owner.getName().getString());
            case NOT_OWNER -> ToolResult.error("only the owner can open my inventory");
            case OTHER_DIMENSION -> ToolResult.error("can't open it: the owner is in another dimension");
            case TOO_FAR -> ToolResult.error("can't open it: the owner is too far away");
            case GONE -> ToolResult.error("can't open it: I'm not there");
        };
    }

    /** Renames this companion for its owner; the name is stored in the owner's book and survives a dismiss. */
    private static ToolResult setName(CompanionEntity c, String raw) {
        CompanionRenamer.Result result = CompanionRenamer.rename(c, raw);
        return switch (result.outcome()) {
            case INVALID_NAME -> ToolResult.error("Invalid name. " + CompanionName.PROBLEM);
            case NO_OWNER -> ToolResult.error("I don't have an owner.");
            case RENAMED -> ToolResult.ok("my name is now " + result.name());
        };
    }

    static void register(ToolRegistry<CompanionEntity> registry, List<TaskToolDef> defs) {
        registry.register(ToolKit.instantTool(
                "show_inventory",
                "Open your inventory and equipment for the owner, so they can take or put items themselves.",
                AccessLevel.OWNER,
                (c, a) -> showInventory(c)));

        registry.register(ToolKit.instantTool(
                "set_name",
                "Change your own name. Use it when the owner tells you what your name is, or what they will call you. "
                        + "The name is shown above your head and used in chat; only the owner can change it.",
                SchemaBuilder.object()
                        .string("name", "The new name, 1-32 characters")
                        .required("name")
                        .build(),
                AccessLevel.OWNER,
                (c, a) -> setName(c, a.get("name").getAsString())));

        registry.register(ToolKit.instantTool("set_follow_mode",
                "Turn walking to the owner on or off. Use mode=off when the owner says not to follow them: it only "
                        + "stops your idle walk and never cancels work, so keep working. Use mode=on when they ask you "
                        + "to follow them again.",
                SchemaBuilder.object()
                        .enumeration("mode", "Follow mode", "on", "off")
                        .required("mode")
                        .build(),
                AccessLevel.OWNER,
                (c, a) -> {
                    boolean on = "on".equals(a.get("mode").getAsString());
                    c.setFollowMode(on);
                    return ToolResult.ok(on ? "following the owner again" : "not following the owner; work continues");
                }));

        registry.register(ToolKit.instantTool("stop",
                "Cancel current work and go back to the default activity. Pass a tool name to stop one action, or all to clear queued work, end a fight and cancel furnace jobs. To stop walking to the owner on your own, use set_follow_mode with mode=off instead; that leaves the work running.",
                SchemaBuilder.object()
                        .string("what", "current (default), a tool name like mine_blocks, or all")
                        .build(),
                (c, a) -> stop(c, a.has("what") && !a.get("what").isJsonNull() ? a.get("what").getAsString() : null)));

    }
}
