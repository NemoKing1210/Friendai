package dev.friendai.server.execution.tools;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionRenamer;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.InventoryAccess;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.StopScope;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.combat.minecraft.CombatController;
import dev.friendai.features.memory.CompanionName;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.task.Task;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** Owner controls and task cancellation for the companion. */
public final class ControlTools implements FeatureToolContributor {
    private static final String STOP_REASON = "stopped by the LLM";
    private final CompanionProfileRepository profiles;

    public ControlTools(CompanionProfileRepository profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registerInstant(
                registry,
                "show_inventory",
                "Open your inventory and equipment for the owner, so they can take or put items themselves.",
                SchemaBuilder.object().build(),
                AccessLevel.OWNER,
                (companion, args) -> showInventory(companion));
        registerInstant(
                registry,
                "set_name",
                "Change your own name. Use it when the owner tells you what your name is, or what they will call you. "
                        + "The name is shown above your head and used in chat; only the owner can change it.",
                SchemaBuilder.object()
                        .string("name", "The new name, 1-32 characters")
                        .required("name")
                        .build(),
                AccessLevel.OWNER,
                (companion, args) ->
                        setName(profiles, companion, args.get("name").getAsString()));
        registerInstant(
                registry,
                "set_follow_mode",
                "Turn walking to the owner on or off. Use mode=off when the owner says not to follow them: it only "
                        + "stops your idle walk and never cancels work, so keep working. Use mode=on when they ask you "
                        + "to follow them again.",
                SchemaBuilder.object()
                        .enumeration("mode", "Follow mode", "on", "off")
                        .required("mode")
                        .build(),
                AccessLevel.OWNER,
                (companion, args) -> {
                    boolean on = "on".equals(args.get("mode").getAsString());
                    companion.setFollowMode(on);
                    return ToolResult.ok(on ? "following the owner again" : "not following the owner; work continues");
                });
        registerInstant(
                registry,
                "stop",
                "Cancel current work and go back to the default activity. Pass a tool name to stop one action, or all "
                        + "to clear queued work, end a fight and cancel furnace jobs. To stop walking to the owner on "
                        + "your own, use set_follow_mode with mode=off instead; that leaves the work running.",
                SchemaBuilder.object()
                        .string("what", "current (default), a tool name like mine_blocks, or all")
                        .build(),
                AccessLevel.TRUSTED,
                (companion, args) -> stop(
                        companion,
                        args.has("what") && !args.get("what").isJsonNull()
                                ? args.get("what").getAsString()
                                : null));
    }

    private static void registerInstant(
            FeatureToolRegistry registry,
            String name,
            String description,
            JsonObject schema,
            AccessLevel minAccess,
            Action action) {
        registry.registerTool(
                new Tool<>() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description() {
                        return description;
                    }

                    @Override
                    public JsonObject schema() {
                        return schema;
                    }

                    @Override
                    public AccessLevel minAccess() {
                        return minAccess;
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, JsonObject args) {
                        return action.run(companion, args);
                    }
                },
                metadata(name));
    }

    private static ToolResult stop(CompanionEntity companion, String what) {
        return switch (StopScope.parse(what)) {
            case StopScope.Current ignored -> {
                companion.executionCoordinator().stop(STOP_REASON);
                yield ToolResult.ok("stopped");
            }
            case StopScope.All ignored -> stopEverything(companion);
            case StopScope.Named named -> {
                String name = named.name().replaceAll("\\s+", "_");
                Optional<String> stopped = companion.executionCoordinator().stopNamed(name, STOP_REASON);
                if (stopped.isPresent()) {
                    yield ToolResult.ok("stopped " + stopped.get());
                }
                String current = companion
                        .executionCoordinator()
                        .current()
                        .map(Task::describe)
                        .orElse("none");
                yield ToolResult.error("not running: " + name + "; current task: " + current);
            }
        };
    }

    private static ToolResult stopEverything(CompanionEntity companion) {
        String task =
                companion.executionCoordinator().current().map(Task::describe).orElse("no task");
        companion.executionCoordinator().stopAll(STOP_REASON);
        StringBuilder text = new StringBuilder("stopped everything: ").append(task);
        CombatController.State before = companion.combatController().state();
        companion.combatController().stopFight();
        if (companion.combatController().state() != before) {
            text.append("; ended the fight");
        }
        int jobs = companion.furnaceJobs().clear();
        if (jobs > 0) {
            text.append("; dropped ").append(jobs).append(jobs == 1 ? " furnace job" : " furnace jobs");
        }
        return ToolResult.ok(text.toString());
    }

    private static ToolResult showInventory(CompanionEntity companion) {
        ServerPlayer owner = companion.owner().orElse(null);
        if (owner == null) {
            return ToolResult.error("the owner is offline");
        }
        InventoryAccess.Denial denial = FriendaiMenus.open(owner, companion);
        return switch (denial) {
            case NONE ->
                ToolResult.ok("opened my inventory for " + owner.getName().getString());
            case NOT_OWNER -> ToolResult.error("only the owner can open my inventory");
            case OTHER_DIMENSION -> ToolResult.error("can't open it: the owner is in another dimension");
            case TOO_FAR -> ToolResult.error("can't open it: the owner is too far away");
            case GONE -> ToolResult.error("can't open it: I'm not there");
        };
    }

    private static ToolResult setName(CompanionProfileRepository profiles, CompanionEntity companion, String raw) {
        CompanionRenamer.Result result = CompanionRenamer.rename(profiles, companion, raw);
        return switch (result.outcome()) {
            case INVALID_NAME -> ToolResult.error("Invalid name. " + CompanionName.PROBLEM);
            case NO_OWNER -> ToolResult.error("I don't have an owner.");
            case RENAMED -> ToolResult.ok("my name is now " + result.name());
        };
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case "show_inventory" -> new ToolMeta(name, ToolGroup.INVENTORY, false, "", List.of(), Cost.LOW);
            case "set_name" -> new ToolMeta(name, ToolGroup.CONTROL, false, "", List.of(), Cost.LOW);
            case "set_follow_mode" ->
                new ToolMeta(
                        name,
                        ToolGroup.MOVEMENT,
                        false,
                        "- \"Don't follow me\" means set_follow_mode with mode=off: it stops your idle walk and your work keeps running. Use stay only to wait in one place for a few minutes, and end_free_time only when the owner stops your free time.",
                        List.of(),
                        Cost.MEDIUM);
            case "stop" -> new ToolMeta(name, ToolGroup.CORE, true, "", List.of(), Cost.LOW);
            default -> throw new IllegalArgumentException("No metadata declared for control tool: " + name);
        };
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, JsonObject args);
    }
}
