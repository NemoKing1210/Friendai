package dev.friendai.skill;

import dev.friendai.tool.Caller;
import java.util.UUID;

/** Authenticated context built by a server adapter. */
public record SkillActor(UUID owner, Caller caller, boolean operator) {}
