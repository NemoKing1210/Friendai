package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.acquisition.minecraft.tool.ObtainTools;
import dev.friendai.features.construction.minecraft.tool.ConstructionTools;
import dev.friendai.features.cooking.minecraft.tool.CookingTools;
import dev.friendai.features.crafting.minecraft.tool.CraftingTools;
import dev.friendai.features.farming.minecraft.tool.FarmingTools;
import dev.friendai.features.interaction.minecraft.tool.BodyTools;
import dev.friendai.features.interaction.minecraft.tool.InteractionTools;
import dev.friendai.features.inventory.minecraft.tool.InventoryTools;
import dev.friendai.features.inventory.minecraft.tool.StorageTools;
import dev.friendai.features.knowledge.minecraft.tool.ResourceTools;
import dev.friendai.features.mining.minecraft.tool.GatheringTools;
import dev.friendai.features.navigation.minecraft.tool.MovementTools;
import dev.friendai.features.navigation.minecraft.tool.RelativeMovementTools;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeatureToolRegistryTest {
    @Test
    void navigationContributionsKeepRuntimeTaskAndCatalogNamesAligned() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, FeatureToolRegistryTest::taskTool);
        new MovementTools().register(registry);
        new RelativeMovementTools().register(registry);

        Set<String> expectedNames =
                Set.of("follow_player", "stay", "go_to", "jump", "set_pace", "step", "back_away", "approach", "circle");
        Set<String> runtimeNames =
                tools.specs().stream().map(spec -> spec.name()).collect(java.util.stream.Collectors.toSet());
        Set<String> metadataNames = registry.catalogMetadata().stream()
                .map(meta -> meta.name())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedNames, runtimeNames);
        assertEquals(expectedNames, metadataNames);
        assertEquals(
                expectedNames.stream()
                        .filter(name -> !name.equals("set_pace"))
                        .collect(java.util.stream.Collectors.toSet()),
                registry.taskDefinitions().keySet());
        assertEquals(registry.taskDefinitions().keySet(), registry.restorableTaskNames());
        assertEquals(
                Set.of("go_to", "jump", "step", "back_away", "approach", "circle"),
                registry.taskDefinitions().values().stream()
                        .filter(TaskToolDefinition::plannable)
                        .map(TaskToolDefinition::name)
                        .collect(java.util.stream.Collectors.toSet()));

        ToolCatalog catalog = ToolCatalog.defaults();
        for (var metadata : registry.catalogMetadata()) {
            assertEquals(catalog.meta(metadata.name()).orElseThrow(), metadata);
        }
    }

    @Test
    void interactionContributionsKeepRuntimeTaskAndCatalogNamesAligned() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, FeatureToolRegistryTest::taskTool);
        new BodyTools().register(registry);
        new InteractionTools().register(registry);

        Set<String> expectedNames = Set.of(
                "look_at",
                "point_at",
                "turn",
                "sneak",
                "emote",
                "sit",
                "stand",
                "wait",
                "swap_hands",
                "set_idle_behavior",
                "set_follow_distance",
                "interact_entity",
                "mount",
                "dismount",
                "use_block");
        Set<String> taskNames = Set.of("turn", "emote", "sit", "wait", "interact_entity", "mount", "use_block");
        Set<String> plannableNames = Set.of("turn", "emote", "wait", "interact_entity", "mount", "use_block");
        Set<String> runtimeNames =
                tools.specs().stream().map(spec -> spec.name()).collect(java.util.stream.Collectors.toSet());
        Set<String> metadataNames = registry.catalogMetadata().stream()
                .map(meta -> meta.name())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedNames, runtimeNames);
        assertEquals(expectedNames, metadataNames);
        assertEquals(taskNames, registry.taskDefinitions().keySet());
        assertEquals(taskNames, registry.restorableTaskNames());
        assertEquals(
                plannableNames,
                registry.taskDefinitions().values().stream()
                        .filter(TaskToolDefinition::plannable)
                        .map(TaskToolDefinition::name)
                        .collect(java.util.stream.Collectors.toSet()));

        ToolCatalog catalog = ToolCatalog.defaults();
        for (var metadata : registry.catalogMetadata()) {
            assertEquals(catalog.meta(metadata.name()).orElseThrow(), metadata);
        }
    }

    @Test
    void inventoryContributionsKeepRuntimeTaskAndCatalogNamesAligned() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, FeatureToolRegistryTest::taskTool);
        new InventoryTools().register(registry);
        new StorageTools().register(registry);

        Set<String> expectedNames = Set.of(
                "get_inventory",
                "give_items",
                "drop_items",
                "pickup_items",
                "equip_item",
                "throw_item",
                "store_items",
                "take_items",
                "check_container",
                "deposit_items");
        Set<String> taskNames = Set.of(
                "give_items",
                "drop_items",
                "pickup_items",
                "equip_item",
                "throw_item",
                "store_items",
                "take_items",
                "check_container",
                "deposit_items");
        Set<String> runtimeNames =
                tools.specs().stream().map(spec -> spec.name()).collect(java.util.stream.Collectors.toSet());
        Set<String> metadataNames = registry.catalogMetadata().stream()
                .map(meta -> meta.name())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedNames, runtimeNames);
        assertEquals(expectedNames, metadataNames);
        assertEquals(taskNames, registry.taskDefinitions().keySet());
        assertEquals(taskNames, registry.restorableTaskNames());
        assertEquals(
                taskNames,
                registry.taskDefinitions().values().stream()
                        .filter(TaskToolDefinition::plannable)
                        .map(TaskToolDefinition::name)
                        .collect(java.util.stream.Collectors.toSet()));

        ToolCatalog catalog = ToolCatalog.defaults();
        for (var metadata : registry.catalogMetadata()) {
            assertEquals(catalog.meta(metadata.name()).orElseThrow(), metadata);
        }
    }

    @Test
    void resourceConstructionCraftingCookingAndFarmingContributionsStayAligned() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, FeatureToolRegistryTest::taskTool);
        var subtaskFactory = new MinecraftObtainSubtaskFactory();
        List.of(
                        new GatheringTools(() -> 16, () -> 16),
                        new ObtainTools(subtaskFactory),
                        new ResourceTools(subtaskFactory),
                        new ConstructionTools(subtaskFactory::createForBuild),
                        new CraftingTools(),
                        new CookingTools(subtaskFactory),
                        new FarmingTools())
                .forEach(contributor -> contributor.register(registry));

        Set<String> expectedNames = Set.of(
                "fell_tree",
                "mine_blocks",
                "mine_block_at",
                "obtain_item",
                "plan_obtain",
                "search_resource",
                "find_target",
                "search_hint",
                "known_resources",
                "place_block",
                "light_area",
                "clear_area",
                "blueprint_help",
                "preview_structure",
                "build_structure",
                "save_blueprint",
                "list_blueprints",
                "build_special",
                "plan_special",
                "craft_item",
                "get_recipe",
                "smelt_items",
                "cook_food",
                "collect_smelted",
                "farm",
                "plan_farm",
                "tend_farm",
                "use_bonemeal");
        Set<String> runtimeNames =
                tools.specs().stream().map(spec -> spec.name()).collect(java.util.stream.Collectors.toSet());
        Set<String> metadataNames = registry.catalogMetadata().stream()
                .map(meta -> meta.name())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedNames, runtimeNames);
        assertEquals(expectedNames, metadataNames);
        assertEquals(
                registry.taskDefinitions().values().stream()
                        .filter(TaskToolDefinition::restorable)
                        .map(TaskToolDefinition::name)
                        .collect(java.util.stream.Collectors.toSet()),
                registry.restorableTaskNames());
        assertEquals(
                AccessLevel.TRUSTED,
                registry.taskDefinitions().get("obtain_item").minAccess());

        ToolCatalog catalog = ToolCatalog.defaults();
        for (var metadata : registry.catalogMetadata()) {
            assertEquals(catalog.meta(metadata.name()).orElseThrow(), metadata);
        }
    }

    @Test
    void duplicateFeatureTaskNameIsRejected() {
        FeatureToolRegistry registry = new FeatureToolRegistry(new ToolRegistry<>(), FeatureToolRegistryTest::taskTool);
        MovementTools contributor = new MovementTools();
        contributor.register(registry);

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> {
            TaskToolDefinition definition = registry.taskDefinitions().get("go_to");
            registry.registerTask(definition);
        });

        assertEquals("Tool already registered: go_to", duplicate.getMessage());
        assertTrue(registry.taskDefinitions().containsKey("go_to"));
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
}
