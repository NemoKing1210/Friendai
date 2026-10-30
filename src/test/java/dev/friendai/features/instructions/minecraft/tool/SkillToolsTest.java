package dev.friendai.features.instructions.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SkillToolsTest {
    private static final UUID OWNER = UUID.fromString("c2f1e3bb-85ac-4bdd-8e8d-7de431643fd2");
    private static final Caller OWNER_CALLER = new Caller(OWNER, "Owner", AccessLevel.OWNER);

    @Test
    void registersOwnerOnlySkillToolsWithTheExistingSchemasAndCatalogMetadata() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions = new FeatureToolRegistry(tools, SkillToolsTest::taskTool);
        new SkillTools(new FakeAccess()).register(contributions);

        assertEquals(Set.of("use_skill", "list_skills", "skill_help", "manage_skill"), names(tools.specs()));
        assertEquals(
                Set.of("use_skill", "list_skills", "skill_help", "manage_skill"),
                names(tools.specs(AccessLevel.OWNER)));
        assertEquals(Set.of(), names(tools.specs(AccessLevel.TRUSTED)));
        assertEquals(Set.of(), names(tools.specs(AccessLevel.GUEST)));
        assertEquals(
                Set.of("use_skill", "list_skills", "skill_help", "manage_skill"),
                contributions.catalogMetadata().stream()
                        .map(meta -> meta.name())
                        .collect(Collectors.toSet()));
        contributions
                .catalogMetadata()
                .forEach(metadata -> assertEquals(
                        ToolCatalog.defaults().meta(metadata.name()).orElseThrow(), metadata));

        ToolSpec useSkill = tools.specs().stream()
                .filter(spec -> spec.name().equals("use_skill"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Set.of("id"),
                useSkill.parametersSchema().getAsJsonObject("properties").keySet());
        assertEquals(
                List.of("id"),
                useSkill.parametersSchema().getAsJsonArray("required").asList().stream()
                        .map(value -> value.getAsString())
                        .toList());

        ToolSpec listSkills = tools.specs().stream()
                .filter(spec -> spec.name().equals("list_skills"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Set.of("page"),
                listSkills.parametersSchema().getAsJsonObject("properties").keySet());
        JsonObject page =
                listSkills.parametersSchema().getAsJsonObject("properties").getAsJsonObject("page");
        assertEquals(0, page.get("minimum").getAsInt());
        assertEquals(64, page.get("maximum").getAsInt());

        ToolSpec skillHelp = tools.specs().stream()
                .filter(spec -> spec.name().equals("skill_help"))
                .findFirst()
                .orElseThrow();
        assertTrue(skillHelp.parametersSchema().getAsJsonObject("properties").isEmpty());

        ToolSpec manageSkill = tools.specs().stream()
                .filter(spec -> spec.name().equals("manage_skill"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                Set.of(
                        "action",
                        "id",
                        "expectedVersion",
                        "name",
                        "description",
                        "body",
                        "turnKinds",
                        "triggers",
                        "tags"),
                manageSkill.parametersSchema().getAsJsonObject("properties").keySet());
        assertEquals(
                List.of("action"),
                manageSkill.parametersSchema().getAsJsonArray("required").asList().stream()
                        .map(value -> value.getAsString())
                        .toList());
        JsonObject properties = manageSkill.parametersSchema().getAsJsonObject("properties");
        assertEquals(
                List.of("create", "update", "disable", "delete"),
                properties.getAsJsonObject("action").getAsJsonArray("enum").asList().stream()
                        .map(value -> value.getAsString())
                        .toList());
        assertEquals(0, properties.getAsJsonObject("turnKinds").get("minItems").getAsInt());
        assertEquals(6, properties.getAsJsonObject("turnKinds").get("maxItems").getAsInt());
        assertEquals(12, properties.getAsJsonObject("triggers").get("maxItems").getAsInt());
        assertEquals(12, properties.getAsJsonObject("tags").get("maxItems").getAsInt());
    }

    @Test
    void requestAndTeachForwardOnlyForTheOwnerOnTheServer() {
        FakeAccess access = new FakeAccess();
        SkillTools tools = new SkillTools(access);
        JsonObject teachingArgs = new JsonObject();
        teachingArgs.addProperty("action", "create");

        ToolResult requested = tools.requestSkill(true, OWNER, OWNER_CALLER, "planning");
        ToolResult taught = tools.teachSkill(true, OWNER, OWNER_CALLER, teachingArgs);

        assertEquals("requested", requested.content());
        assertEquals(OWNER, access.requestOwner);
        assertEquals("planning", access.requestedId);
        assertEquals("taught", taught.content());
        assertEquals(OWNER, access.teachOwner);
        assertEquals(OWNER_CALLER, access.teachingCaller);
        assertEquals(teachingArgs, access.teachingArgs);

        assertTrue(tools.requestSkill(false, OWNER, OWNER_CALLER, "planning").error());
        assertTrue(
                tools.requestSkill(true, OWNER, new Caller(UUID.randomUUID(), "Guest", AccessLevel.GUEST), "planning")
                        .error());
        assertTrue(tools.requestSkill(true, OWNER, new Caller(OWNER, "Owner", AccessLevel.TRUSTED), "planning")
                .error());
        assertTrue(tools.teachSkill(true, UUID.randomUUID(), OWNER_CALLER, teachingArgs)
                .error());
        assertEquals(1, access.requestCalls);
        assertEquals(1, access.teachCalls);

        ToolResult listed = tools.listSkills(true, OWNER, OWNER_CALLER, 2);
        assertEquals("listed:2", listed.content());
        assertEquals(OWNER, access.listOwner);
        assertEquals(2, access.listPage);
        assertEquals("help", tools.helpSkills(true, OWNER, OWNER_CALLER).content());
        assertEquals(1, access.helpCalls);

        assertTrue(tools.listSkills(false, OWNER, OWNER_CALLER, 0).error());
        assertTrue(tools.listSkills(true, OWNER, new Caller(OWNER, "Owner", AccessLevel.TRUSTED), 0)
                .error());
        assertTrue(tools.helpSkills(true, UUID.randomUUID(), OWNER_CALLER).error());
        assertEquals(1, access.listCalls);
    }

    private static Set<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).collect(Collectors.toSet());
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
            public AccessLevel minAccess() {
                return definition.minAccess();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return ToolResult.ok(definition.name());
            }
        };
    }

    private static final class FakeAccess implements SkillToolAccess {
        private UUID requestOwner;
        private String requestedId;
        private UUID teachOwner;
        private Caller teachingCaller;
        private JsonObject teachingArgs;
        private int requestCalls;
        private int teachCalls;

        @Override
        public ToolResult request(UUID owner, String id) {
            requestCalls++;
            requestOwner = owner;
            requestedId = id;
            return ToolResult.ok("requested");
        }

        private UUID listOwner;
        private int listPage;
        private int listCalls;
        private int helpCalls;

        @Override
        public ToolResult list(UUID owner, int page) {
            listCalls++;
            listOwner = owner;
            listPage = page;
            return ToolResult.ok("listed:" + page);
        }

        @Override
        public ToolResult help() {
            helpCalls++;
            return ToolResult.ok("help");
        }

        @Override
        public ToolResult teach(UUID owner, Caller caller, JsonObject args) {
            teachCalls++;
            teachOwner = owner;
            teachingCaller = caller;
            teachingArgs = args.deepCopy();
            return ToolResult.ok("taught");
        }
    }
}
