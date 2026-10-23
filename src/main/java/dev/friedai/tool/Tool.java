package dev.friedai.tool;

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

    /** Called with arguments that already passed validation. */
    ToolResult execute(C ctx, JsonObject args);
}
