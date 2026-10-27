package dev.friendai.skill;

import com.google.gson.annotations.SerializedName;
import java.util.*;

/** Immutable instructions. Metadata is assigned by the server, never by an import or tool. */
public record InstructionSkill(String id, String name, String description, String body, boolean enabled,
        Scope scope, Source source, UUID author, long createdAt, long updatedAt, int version,
        Activation activation, boolean alwaysOn, Set<Kind> turnKinds, List<String> triggers,
        List<String> tags, boolean includeProfile) {
    public enum Scope { @SerializedName("companion") COMPANION, @SerializedName("server") SERVER }
    public enum Source { @SerializedName("builtin") BUILTIN, @SerializedName("owner") OWNER, @SerializedName("model") MODEL }
    public enum Activation { @SerializedName("active") ACTIVE, @SerializedName("pending") PENDING }
    public enum Kind {
        @SerializedName("chat") CHAT, @SerializedName("event") EVENT, @SerializedName("planning") PLANNING,
        @SerializedName("free_time") FREE_TIME, @SerializedName("building") BUILDING, @SerializedName("combat") COMBAT
    }
    public InstructionSkill {
        Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(description);
        Objects.requireNonNull(body); Objects.requireNonNull(scope); Objects.requireNonNull(source);
        Objects.requireNonNull(activation);
        turnKinds = Set.copyOf(turnKinds); triggers = List.copyOf(triggers); tags = List.copyOf(tags);
        if (!id.matches("(?:builtin|owner|model|server):[a-z0-9-]{1,80}") || version < 1
                || createdAt < 0 || updatedAt < createdAt) throw new IllegalArgumentException("Invalid skill metadata.");
    }
    public SkillDraft draft() {
        return new SkillDraft(name, description, body, enabled, alwaysOn, turnKinds, triggers, tags, includeProfile);
    }
}
