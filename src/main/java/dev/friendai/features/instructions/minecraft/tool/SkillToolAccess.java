package dev.friendai.features.instructions.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import java.util.UUID;

/** Server capabilities required by instruction tools, supplied by server composition. */
public interface SkillToolAccess {
    ToolResult request(UUID owner, String id);

    ToolResult list(UUID owner, int page);

    ToolResult help();

    ToolResult teach(UUID owner, Caller caller, JsonObject args);
}
