package dev.friendai.server.agent.chat;

import dev.friendai.agent.chat.LocalOrders;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Runs an exact local order through the same tool registry the model uses. */
public final class LocalOrderExecutor {
    private LocalOrderExecutor() {}

    public static boolean tryRun(ServerRuntime runtime, ServerPlayer sender, UUID owner, String text) {
        Optional<ToolCall> parsed = LocalOrders.parse(text);
        if (parsed.isEmpty() || runtime.companions().find(owner).isEmpty()) {
            return false;
        }
        Caller caller = new Caller(sender.getUUID(), sender.getName().getString(), access(runtime, owner, sender));
        ToolResult result = runtime.sessions().runTool(owner, parsed.get(), caller);
        sender.sendSystemMessage(message(parsed.get().name(), result, caller));
        return true;
    }

    private static Component message(String tool, ToolResult result, Caller caller) {
        if (result.error() && caller.access() == AccessLevel.GUEST) {
            return Component.translatable("friendai.error.access");
        }
        if (result.error()) {
            return Component.translatable("friendai.local_order.failed", result.content());
        }
        return switch (tool) {
            case "follow_player" -> Component.translatable("friendai.local_order.follow");
            case "stay" -> Component.translatable("friendai.local_order.stay");
            case "go_to" -> Component.translatable("friendai.local_order.come");
            case "stop" -> Component.translatable("friendai.local_order.stop");
            default -> Component.translatable("friendai.local_order.result", result.content());
        };
    }

    private static AccessLevel access(ServerRuntime runtime, UUID owner, ServerPlayer sender) {
        if (sender.getUUID().equals(owner)) {
            return AccessLevel.OWNER;
        }
        return runtime.memory().book(owner).isTrusted(sender.getUUID()) ? AccessLevel.TRUSTED : AccessLevel.GUEST;
    }
}
