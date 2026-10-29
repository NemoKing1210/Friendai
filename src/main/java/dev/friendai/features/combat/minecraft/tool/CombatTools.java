package dev.friendai.features.combat.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.combat.CombatMode;
import dev.friendai.features.combat.CombatSettings;
import dev.friendai.features.combat.FightingStyle;
import dev.friendai.features.combat.ProtectionPolicy;
import dev.friendai.features.combat.RetreatPreference;
import dev.friendai.features.combat.minecraft.Threats;
import dev.friendai.features.combat.minecraft.task.AttackPlayerTask;
import dev.friendai.features.combat.minecraft.task.HuntTask;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import dev.friendai.task.Task;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.jetbrains.annotations.Nullable;

/** Combat tasks and owner-owned combat preferences. */
public final class CombatTools implements FeatureToolContributor {
    private static final String ATTACK_DESCRIPTION =
            "Hunt and kill mobs of a type near the companion, then collect the drops. Use attack_player for"
                    + " players. Villagers, wandering traders and golems are off limits on your own initiative;"
                    + " when the owner explicitly orders one hit or killed, that order is the permission, so"
                    + " call attack with its type and do it.";
    private static final String ATTACK_PLAYER_DESCRIPTION =
            "Attack a player by name until they are defeated. Only the owner may order it. When the owner asks you to hit or kill them, do it: their order is consent. Otherwise only where PvP is allowed.";

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTask(new TaskToolDefinition(
                "attack",
                ATTACK_DESCRIPTION,
                SchemaBuilder.object()
                        .string("target", "Entity type id like minecraft:cow, or a tag like #minecraft:skeletons")
                        .integer("count", "How many to hunt", 1, 16)
                        .integer("radius", "Search radius in blocks", 1, 32)
                        .required("target", "count")
                        .build(),
                (c, a) -> huntTask(a, false),
                (c, caller, a) -> huntTask(a, caller != null && caller.access() == AccessLevel.OWNER),
                true,
                AccessLevel.TRUSTED,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                true,
                metadata("attack")));
        registry.registerTask(new TaskToolDefinition(
                "attack_player",
                ATTACK_PLAYER_DESCRIPTION,
                SchemaBuilder.object()
                        .string("name", "Player name")
                        .required("name")
                        .build(),
                (c, a) -> attackPlayerTask(
                        c,
                        new Caller(null, "", AccessLevel.OWNER),
                        a.get("name").getAsString()),
                (c, caller, a) -> attackPlayerTask(c, caller, a.get("name").getAsString()),
                false,
                AccessLevel.OWNER,
                TaskToolDefinition.QueueTraits.DEFAULT,
                true,
                true,
                metadata("attack_player")));

        registerInstant(
                registry,
                "set_combat_mode",
                "Set how the companion fights with the legacy presets: defensive (bodyguard, balanced), guard (guard, balanced), passive (avoid combat, balanced). Owner only. Keeps potion, pursuit and retreat settings.",
                SchemaBuilder.object()
                        .enumeration("mode", "Combat mode", "defensive", "guard", "passive")
                        .required("mode")
                        .build(),
                AccessLevel.OWNER,
                (c, caller, a) -> {
                    CombatMode mode = CombatMode.parse(a.get("mode").getAsString());
                    c.setCombatMode(mode);
                    return ToolResult.ok("combat mode is now " + mode.label());
                });
        registerInstant(
                registry,
                "get_combat_settings",
                "Owner only. Show whom the companion protects, how it fights, whether it may use splash potions, how far it pursues and whether it retreats conservatively.",
                SchemaBuilder.object().build(),
                AccessLevel.OWNER,
                (c, caller, a) -> ToolResult.ok(describeCombatSettings(c)));
        registerInstant(
                registry,
                "set_combat_settings",
                "Owner only. Change persistent combat preferences. protection: self_only (defend itself only), bodyguard (self and owner), guard (also attack hostile mobs near the owner), avoid_combat (never initiate damage). style: balanced, cautious, melee, ranged. splash_potions enables safe splash potion use (off by default). pursuit_limit is 8-64 blocks. retreat: standard or conservative. An explicit attack order is a task, not a protection change; avoid_combat prevents re-engaging after it ends.",
                SchemaBuilder.object()
                        .enumeration("protection", "Whom to protect", "self_only", "bodyguard", "guard", "avoid_combat")
                        .enumeration("style", "Fighting style", "balanced", "cautious", "melee", "ranged")
                        .bool("splash_potions", "Allow safe splash potion use")
                        .integer("pursuit_limit", "Pursuit distance from the protected anchor in blocks", 8, 64)
                        .enumeration("retreat", "Retreat preference", "standard", "conservative")
                        .build(),
                AccessLevel.OWNER,
                (c, caller, a) -> setCombatSettings(c, a));
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
                        return action.run(companion, null, args);
                    }

                    @Override
                    public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                        return action.run(companion, caller, args);
                    }
                },
                metadata(name));
    }

    private static ToolMeta metadata(String name) {
        return new ToolMeta(name, ToolGroup.COMBAT, false, "", List.of(), Cost.MEDIUM);
    }

    private static Task huntTask(JsonObject args, boolean ownerOrder) {
        String target = args.get("target").getAsString();
        return new HuntTask(
                matcher(target, ownerOrder),
                targetLabel(target),
                args.get("count").getAsInt(),
                args.has("radius") && !args.get("radius").isJsonNull()
                        ? args.get("radius").getAsInt()
                        : 16,
                ownerOrder);
    }

    private static Task attackPlayerTask(CompanionEntity companion, @Nullable Caller caller, String name) {
        if (!(companion.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException(name + " is not here");
        }
        ServerPlayer target = level.getServer().getPlayerList().getPlayerByName(name);
        if (target == null || target.level() != level) {
            throw new IllegalArgumentException(name + " is not here");
        }
        ServerPlayer owner = companion.owner().orElse(null);
        if (owner != null && owner.getUUID().equals(target.getUUID())) {
            boolean ownerAsked = caller != null
                    && caller.access() == AccessLevel.OWNER
                    && (caller.uuid() == null || caller.uuid().equals(owner.getUUID()));
            if (!ownerAsked) {
                throw new IllegalArgumentException("I won't attack you");
            }
            return new AttackPlayerTask(target.getUUID(), name, true);
        }
        if (!Threats.mayFightPlayer(companion, target)) {
            throw new IllegalArgumentException(
                    level.isPvpAllowed() ? "I can't hurt " + name + " here" : "PvP is off here");
        }
        return new AttackPlayerTask(target.getUUID(), name);
    }

    private static Predicate<EntityType<?>> matcher(String text, boolean ownerOrder) {
        if (text.startsWith("#")) {
            Identifier id = Identifier.tryParse(text.substring(1));
            if (id == null) throw new IllegalArgumentException("unknown mob: " + text);
            TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE, id);
            return type -> type.builtInRegistryHolder().is(tag);
        }
        EntityType<?> type = entityType(text);
        if (type == EntityTypes.PLAYER) throw new IllegalArgumentException("use attack_player for players");
        if (type == FriendaiEntities.COMPANION
                || (!ownerOrder
                        && (type == EntityTypes.VILLAGER
                                || type == EntityTypes.WANDERING_TRADER
                                || type == EntityTypes.IRON_GOLEM
                                || type == EntityTypes.SNOW_GOLEM))) {
            throw new IllegalArgumentException("I won't attack " + BuiltInRegistries.ENTITY_TYPE.getKey(type));
        }
        return candidate -> candidate == type;
    }

    private static String targetLabel(String text) {
        if (text.startsWith("#")) {
            Identifier id = Identifier.tryParse(text.substring(1));
            return "#" + (id == null ? text.substring(1) : id);
        }
        return BuiltInRegistries.ENTITY_TYPE.getKey(entityType(text)).toString();
    }

    private static EntityType<?> entityType(String text) {
        Identifier id = Identifier.tryParse(text);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            throw new IllegalArgumentException("unknown mob: " + text);
        }
        return BuiltInRegistries.ENTITY_TYPE.getValue(id);
    }

    private static ToolResult setCombatSettings(CompanionEntity companion, JsonObject args) {
        CombatSettings settings = companion.combatSettings();
        try {
            if (has(args, "protection"))
                settings = settings.withProtection(
                        ProtectionPolicy.parseStrict(args.get("protection").getAsString()));
            if (has(args, "style"))
                settings = settings.withStyle(
                        FightingStyle.parseStrict(args.get("style").getAsString()));
            if (has(args, "splash_potions"))
                settings = settings.withSplashPotions(args.get("splash_potions").getAsBoolean());
            if (has(args, "pursuit_limit"))
                settings = settings.withPursuitLimit(args.get("pursuit_limit").getAsInt());
            if (has(args, "retreat"))
                settings = settings.withRetreatPreference(
                        RetreatPreference.parseStrict(args.get("retreat").getAsString()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        companion.setCombatSettings(settings);
        return ToolResult.ok("combat settings: " + describe(settings));
    }

    private static boolean has(JsonObject args, String name) {
        return args.has(name) && !args.get(name).isJsonNull();
    }

    private static String describeCombatSettings(CompanionEntity companion) {
        return "combat settings: " + describe(companion.combatSettings());
    }

    private static String describe(CombatSettings settings) {
        return "protection " + settings.protection().label() + ", style "
                + settings.style().label()
                + ", splash potions " + (settings.allowSplashPotions() ? "on" : "off")
                + ", pursuit " + settings.pursuitLimitBlocks() + " blocks, retreat "
                + settings.retreatPreference().label();
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, @Nullable Caller caller, JsonObject args);
    }
}
