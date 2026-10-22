package dev.friedai.llm.model;

import com.google.gson.JsonObject;

public record ToolSpec(String name, String description, JsonObject parametersSchema) {}
