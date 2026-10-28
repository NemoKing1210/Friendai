package dev.friendai.features.instructions;

import dev.friendai.execution.tool.Caller;
import java.util.UUID;

/** Authenticated context built by a server adapter. */
public record SkillActor(UUID owner, Caller caller, boolean operator) {}
