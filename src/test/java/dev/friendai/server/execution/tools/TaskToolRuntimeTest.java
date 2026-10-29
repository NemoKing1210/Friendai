package dev.friendai.server.execution.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.execution.tool.AccessLevel;
import org.junit.jupiter.api.Test;

class TaskToolRuntimeTest {
    @Test
    void spendingDefinitionsRequireCallerAwareFactoriesBeforeRegistration() {
        var spending = new TaskToolDefinition(
                "trade_item",
                "trade",
                new JsonObject(),
                (companion, args) -> null,
                (companion, caller, args) -> null,
                true,
                AccessLevel.OWNER,
                new TaskToolDefinition.QueueTraits(false, null, true),
                true,
                true,
                ToolCatalog.defaults().meta("trade_item").orElseThrow());
        var ordinary = new TaskToolDefinition(
                "stay",
                "stay",
                new JsonObject(),
                (companion, args) -> null,
                true,
                ToolCatalog.defaults().meta("stay").orElseThrow());

        assertDoesNotThrow(() -> TaskToolRuntime.validateRegistration(spending));
        assertDoesNotThrow(() -> TaskToolRuntime.validateRegistration(ordinary));
        assertTrue(TaskToolRuntime.requiresFreshAuthority(new JsonObject(), spending));
        assertFalse(TaskToolRuntime.requiresFreshAuthority(new JsonObject(), ordinary));

        var spendingWithoutCallerFactory = new TaskToolDefinition(
                "trade_item",
                "trade",
                new JsonObject(),
                (companion, args) -> null,
                null,
                true,
                AccessLevel.OWNER,
                new TaskToolDefinition.QueueTraits(true, null, true),
                true,
                true,
                ToolCatalog.defaults().meta("trade_item").orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskToolRuntime.validateRegistration(spendingWithoutCallerFactory));
    }
}
