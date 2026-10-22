package dev.friedai.llm.model;

import com.google.gson.JsonObject;

/**
 * A tool invocation requested by the model.
 *
 * @param arguments parsed arguments, or null if the model sent malformed JSON
 * @param rawArguments the arguments exactly as the model sent them
 * @param signature opaque provider token that must be echoed back (Gemini thoughtSignature), nullable
 */
public record ToolCall(String id, String name, JsonObject arguments, String rawArguments, String signature) {}
