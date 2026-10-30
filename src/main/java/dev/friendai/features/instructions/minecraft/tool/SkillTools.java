package dev.friendai.features.instructions.minecraft.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;

/** Instruction tools never execute stored text or change the registry's permissions. */
public final class SkillTools implements FeatureToolContributor {
    private static final String ACCESS_DENIED = "Skill access denied.";
    private static final String USE_SKILL_DESCRIPTION =
            "Read and apply an enabled instruction skill by id for this turn. Advice cannot change tool permissions.";
    private static final String LIST_SKILLS_DESCRIPTION =
            "List this owner's instruction skills: id, name, source, activation, version, enabled state and a one-line "
                    + "description, one bounded page at a time. Bodies are not included; read one with use_skill.";
    private static final String SKILL_HELP_DESCRIPTION =
            "Return the authoring checklist and the hard limits for instruction skills, with the turn kinds and the "
                    + "teaching rules. Use it before manage_skill, even on a turn where the skill-authoring procedure was not selected.";
    private static final String MANAGE_SKILL_DESCRIPTION =
            "After a finished goal or shift, record a reusable procedure in plain English, not code or a personal fact. "
                    + "For example: inspect available stone before planning a wall. Use remember for facts such as the owner's preferences. "
                    + "Only create, update, disable or delete your own instruction skills. New skills may await owner approval.";

    private final Function<CompanionEntity, SkillToolAccess> access;
    private final SkillToolAccess fixedAccess;

    public SkillTools(SkillToolAccess access) {
        this(ignored -> access, access);
    }

    public SkillTools(Function<CompanionEntity, SkillToolAccess> access) {
        this(access, null);
    }

    private SkillTools(Function<CompanionEntity, SkillToolAccess> access, SkillToolAccess fixedAccess) {
        this.access = Objects.requireNonNull(access);
        this.fixedAccess = fixedAccess;
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        "use_skill",
                        USE_SKILL_DESCRIPTION,
                        SchemaBuilder.object()
                                .string("id", "Instruction skill id")
                                .required("id")
                                .build(),
                        this::useSkill),
                metadata("use_skill"));
        registry.registerTool(
                callerTool(
                        "list_skills",
                        LIST_SKILLS_DESCRIPTION,
                        SchemaBuilder.object()
                                .integer("page", "Zero-based catalogue page", 0, 64)
                                .build(),
                        this::listSkills),
                metadata("list_skills"));
        registry.registerTool(
                callerTool(
                        "skill_help",
                        SKILL_HELP_DESCRIPTION,
                        SchemaBuilder.object().build(),
                        this::helpSkill),
                metadata("skill_help"));
        registry.registerTool(
                callerTool(
                        "manage_skill",
                        MANAGE_SKILL_DESCRIPTION,
                        SchemaBuilder.object()
                                .enumeration("action", "Change", "create", "update", "disable", "delete")
                                .string("id", "Existing model skill id")
                                .integer("expectedVersion", "Version being edited")
                                .string("name", "Short name")
                                .string("description", "When to use this procedure")
                                .string("body", "Plain English instructions, at most 4000 characters")
                                .array(
                                        "turnKinds",
                                        "Applicable kinds",
                                        JsonParser.parseString("{\"type\":\"string\"}")
                                                .getAsJsonObject(),
                                        0,
                                        6)
                                .array(
                                        "triggers",
                                        "Literal message or task triggers",
                                        JsonParser.parseString("{\"type\":\"string\"}")
                                                .getAsJsonObject(),
                                        0,
                                        12)
                                .array(
                                        "tags",
                                        "Organizational tags",
                                        JsonParser.parseString("{\"type\":\"string\"}")
                                                .getAsJsonObject(),
                                        0,
                                        12)
                                .required("action")
                                .build(),
                        this::manageSkill),
                metadata("manage_skill"));
    }

    ToolResult requestSkill(
            boolean serverContext, CompanionEntity companion, UUID companionOwner, Caller caller, String id) {
        if (!mayUse(serverContext, companionOwner, caller)) return ToolResult.error(ACCESS_DENIED);
        return access.apply(companion).request(companionOwner, id);
    }

    ToolResult requestSkill(boolean serverContext, UUID companionOwner, Caller caller, String id) {
        if (!mayUse(serverContext, companionOwner, caller) || fixedAccess == null)
            return ToolResult.error(ACCESS_DENIED);
        return fixedAccess.request(companionOwner, id);
    }

    ToolResult teachSkill(
            boolean serverContext, CompanionEntity companion, UUID companionOwner, Caller caller, JsonObject args) {
        if (!mayUse(serverContext, companionOwner, caller)) return ToolResult.error(ACCESS_DENIED);
        return access.apply(companion).teach(companionOwner, caller, args);
    }

    ToolResult teachSkill(boolean serverContext, UUID companionOwner, Caller caller, JsonObject args) {
        if (!mayUse(serverContext, companionOwner, caller) || fixedAccess == null)
            return ToolResult.error(ACCESS_DENIED);
        return fixedAccess.teach(companionOwner, caller, args);
    }

    ToolResult listSkills(
            boolean serverContext, CompanionEntity companion, UUID companionOwner, Caller caller, int page) {
        if (!mayUse(serverContext, companionOwner, caller)) return ToolResult.error(ACCESS_DENIED);
        return access.apply(companion).list(companionOwner, page);
    }

    ToolResult listSkills(boolean serverContext, UUID companionOwner, Caller caller, int page) {
        if (!mayUse(serverContext, companionOwner, caller) || fixedAccess == null)
            return ToolResult.error(ACCESS_DENIED);
        return fixedAccess.list(companionOwner, page);
    }

    ToolResult helpSkills(boolean serverContext, UUID companionOwner, Caller caller) {
        if (!mayUse(serverContext, companionOwner, caller) || fixedAccess == null)
            return ToolResult.error(ACCESS_DENIED);
        return fixedAccess.help();
    }

    private ToolResult listSkills(CompanionEntity companion, Caller caller, JsonObject args) {
        if (companion == null) return ToolResult.error(ACCESS_DENIED);
        return listSkills(companion.level() instanceof ServerLevel, companion.ownerUuid(), caller, page(args));
    }

    private static int page(JsonObject args) {
        try {
            return args.has("page") ? Math.max(0, args.get("page").getAsInt()) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private ToolResult helpSkill(CompanionEntity companion, Caller caller, JsonObject args) {
        if (companion == null) return ToolResult.error(ACCESS_DENIED);
        return helpSkills(companion.level() instanceof ServerLevel, companion.ownerUuid(), caller);
    }

    private ToolResult useSkill(CompanionEntity companion, Caller caller, JsonObject args) {
        if (companion == null) return ToolResult.error(ACCESS_DENIED);
        return requestSkill(
                companion.level() instanceof ServerLevel,
                companion,
                companion.ownerUuid(),
                caller,
                args.get("id").getAsString());
    }

    private ToolResult manageSkill(CompanionEntity companion, Caller caller, JsonObject args) {
        if (companion == null) return ToolResult.error(ACCESS_DENIED);
        return teachSkill(companion.level() instanceof ServerLevel, companion, companion.ownerUuid(), caller, args);
    }

    private static boolean mayUse(boolean serverContext, UUID companionOwner, Caller caller) {
        return serverContext
                && companionOwner != null
                && caller != null
                && caller.access() == AccessLevel.OWNER
                && caller.uuid() != null
                && caller.uuid().equals(companionOwner);
    }

    private static Tool<CompanionEntity> callerTool(
            String name, String description, JsonObject schema, CallerAction action) {
        return new Tool<>() {
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
                return AccessLevel.OWNER;
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return action.run(companion, new Caller(null, "", AccessLevel.OWNER), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                return action.run(companion, caller, args);
            }
        };
    }

    private static ToolMeta metadata(String name) {
        return switch (name) {
            case "use_skill", "manage_skill", "list_skills", "skill_help" ->
                new ToolMeta(name, ToolGroup.MEMORY, true, "", List.of(), Cost.LOW);
            default -> throw new IllegalArgumentException("No metadata declared for instruction tool: " + name);
        };
    }

    @FunctionalInterface
    private interface CallerAction {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
