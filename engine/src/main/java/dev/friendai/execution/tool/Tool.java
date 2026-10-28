package dev.friendai.execution.tool;

import com.google.gson.JsonObject;

/** A skill as seen by the LLM. {@code C} is the execution context (the companion in the game). */
public interface Tool<C> {
    String name();

    String description();

    /** JSON schema (subset: object/string/integer/number/boolean, required, enum, minimum, maximum). */
    JsonObject schema();

    /** The lowest access level whose turn may see and run this tool. Everything acts by default. */
    default AccessLevel minAccess() {
        return AccessLevel.TRUSTED;
    }

    /** Whether the tool exists at all right now; a disabled tool is hidden and refuses calls. */
    default boolean enabled() {
        return true;
    }

    /** Called with arguments that already passed validation. */
    ToolResult execute(C ctx, JsonObject args);

    /** Tools that need to know who the turn acts for override this; the rest keep the two-argument form. */
    default ToolResult execute(C ctx, Caller caller, JsonObject args) {
        return execute(ctx, args);
    }
}
