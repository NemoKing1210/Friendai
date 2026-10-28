package dev.friendai.server.execution;

import dev.friendai.creative.CreativeActionContext;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.TaskContext;
import dev.friendai.tool.Caller;

/** Captures the authenticated actor and runtime snapshot for one task start. */
public record TaskStartContext(
        String toolName,
        Caller caller,
        CreativeActionContext creativeActionContext,
        boolean emitEvents,
        boolean defaultActivity,
        boolean interrupt,
        int id,
        long jobId,
        long planId,
        boolean creativeSensitive,
        ServerSettings settings,
        TaskContext taskContext) {
    public TaskStartContext {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("tool name is required");
        if (id < 0 || jobId < 0 || planId < 0)
            throw new IllegalArgumentException("task identifiers cannot be negative");
        if (settings == null) throw new IllegalArgumentException("server settings are required");
        if (taskContext == null) throw new IllegalArgumentException("task context is required");
    }
}
