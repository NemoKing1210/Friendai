package dev.friendai.tool.builtin;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.ToolRegistry;
import dev.friendai.tool.ToolResult;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class ToolKitTest {
    @Test
    void spendingDefinitionsRequireCallerAwareRegistrationAndAreDetectedForRestore() {
        var spending = new TaskToolDef(
                "trade",
                "trade",
                new JsonObject(),
                (companion, args) -> null,
                true,
                AccessLevel.OWNER,
                new TaskToolDef.QueueTraits(false, null, true),
                (companion, caller, args) -> null);
        var ordinary = new TaskToolDef(
                "stay",
                "stay",
                new JsonObject(),
                (companion, args) -> null,
                true,
                AccessLevel.TRUSTED,
                TaskToolDef.QueueTraits.DEFAULT);

        assertThrows(IllegalArgumentException.class, () -> ToolKit.validateRegistration(spending, false));
        assertThrows(IllegalArgumentException.class, () -> ToolKit.taskTool(spending));
        var definitions = new ArrayList<TaskToolDef>();
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolKit.add(new ToolRegistry<CompanionEntity>(), definitions, spending));
        assertTrue(definitions.isEmpty());
        assertDoesNotThrow(() -> ToolKit.validateRegistration(spending, true));
        assertTrue(ToolKit.requiresFreshAuthority(new JsonObject(), spending));
        assertFalse(ToolKit.requiresFreshAuthority(new JsonObject(), ordinary));

        var spendingWithoutCallerFactory = new TaskToolDef(
                "unsafe_trade",
                "trade",
                new JsonObject(),
                (companion, args) -> null,
                true,
                AccessLevel.OWNER,
                new TaskToolDef.QueueTraits(true, null, true));
        assertThrows(
                IllegalArgumentException.class, () -> ToolKit.validateRegistration(spendingWithoutCallerFactory, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolKit.addCaller(
                        new ToolRegistry<CompanionEntity>(),
                        new ArrayList<>(),
                        spendingWithoutCallerFactory,
                        (companion, caller, args) -> ToolResult.ok("unsafe")));
    }
}
