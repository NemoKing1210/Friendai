package dev.friedai.tool;

import com.google.gson.JsonObject;

/** A skill as seen by the LLM. {@code C} is the execution context (the companion in the game). */
public interface Tool<C> {
    String name();

    String description();

    /** JSON schema (subset: object/string/integer/number/boolean, required, enum, minimum, maximum). */
    JsonObject schema();

    /** Called with arguments that already passed validation. */
    ToolResult execute(C ctx, JsonObject args);
}
