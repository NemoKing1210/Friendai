package dev.friendai.execution.tool;

import com.google.gson.JsonObject;

/** One step of a plan: a tool name and the arguments it will be started with. */
public record PlanStep(String tool, JsonObject args) {}
