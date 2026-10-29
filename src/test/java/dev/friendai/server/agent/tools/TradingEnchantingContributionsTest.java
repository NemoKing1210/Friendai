package dev.friendai.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.enchanting.minecraft.tool.EnchantTools;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import dev.friendai.features.trading.minecraft.tool.TradingTools;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import dev.friendai.server.execution.tools.TaskToolDefinition;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TradingEnchantingContributionsTest {
    private static final Set<String> TRADING_NAMES = Set.of(
            "find_traders",
            "inspect_trader",
            "refresh_trades",
            "wait_for_restock",
            "known_trades",
            "plan_trade",
            "trade_item",
            "level_trader",
            "barter");
    private static final Set<String> EXPECTED_NAMES = Set.of(
            "find_traders",
            "inspect_trader",
            "refresh_trades",
            "wait_for_restock",
            "known_trades",
            "plan_trade",
            "trade_item",
            "level_trader",
            "barter",
            "enchant_item",
            "anvil_item",
            "grindstone_item");

    @Test
    void featureContributionsKeepRuntimeTasksAndCatalogMetadataAligned() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry contributions =
                new FeatureToolRegistry(tools, TradingEnchantingContributionsTest::taskTool);
        new TradingTools().register(contributions);
        new EnchantTools().register(contributions);

        Set<String> runtimeNames = tools.specs().stream().map(ToolSpec::name).collect(Collectors.toSet());
        Set<String> metadataNames =
                contributions.catalogMetadata().stream().map(ToolMeta::name).collect(Collectors.toSet());
        Set<String> taskNames = contributions.taskDefinitions().keySet();

        assertEquals(EXPECTED_NAMES, runtimeNames);
        assertEquals(EXPECTED_NAMES, metadataNames);
        assertEquals(
                Set.of(
                        "inspect_trader",
                        "refresh_trades",
                        "wait_for_restock",
                        "trade_item",
                        "level_trader",
                        "barter",
                        "enchant_item",
                        "anvil_item",
                        "grindstone_item"),
                taskNames);
        assertEquals(taskNames, contributions.restorableTaskNames());
        assertTrue(contributions.taskDefinitions().values().stream()
                .filter(def -> def.catalogMeta().group() == ToolGroup.TRADING)
                .allMatch(def -> def.minAccess() == AccessLevel.OWNER));
        assertTrue(contributions.taskDefinitions().values().stream()
                .filter(def -> def.catalogMeta().group() == ToolGroup.ENCHANT)
                .allMatch(def -> def.minAccess() == AccessLevel.TRUSTED));
        TaskToolDefinition trade = contributions.taskDefinitions().get("trade_item");
        assertTrue(trade.queueTraits().requiresTradingScope());
        assertTrue(trade.callerFactory() != null);

        ToolCatalog catalog = ToolCatalog.defaults();
        for (ToolMeta metadata : contributions.catalogMetadata()) {
            assertEquals(catalog.meta(metadata.name()).orElseThrow(), metadata);
            ToolGroup expectedGroup = TRADING_NAMES.contains(metadata.name()) ? ToolGroup.TRADING : ToolGroup.ENCHANT;
            assertEquals(expectedGroup, metadata.group(), metadata.name());
        }
    }

    @Test
    void sharedBootstrapPreservesOwnerOnlyAccessAndCallerAwareTradeRegistration() {
        FriendaiTools.Builtins builtins = FriendaiTools.build(() -> 8, MemoryAccess.DISABLED);
        List<String> owner = builtins.registry().specs(AccessLevel.OWNER).stream()
                .map(ToolSpec::name)
                .toList();
        List<String> trusted = builtins.registry().specs(AccessLevel.TRUSTED).stream()
                .map(ToolSpec::name)
                .toList();

        assertTrue(
                owner.containsAll(List.of("find_traders", "inspect_trader", "trade_item", "barter", "enchant_item")));
        assertFalse(trusted.contains("find_traders"));
        assertFalse(trusted.contains("trade_item"));
        assertTrue(trusted.containsAll(List.of("enchant_item", "anvil_item", "grindstone_item")));
        assertTrue(builtins.taskTools().get("trade_item").queueTraits().requiresTradingScope());
        assertTrue(builtins.taskTools().get("trade_item").callerFactory() != null);
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
