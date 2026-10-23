package dev.friendai.debug;

/** A finished or cancelled task. {@code status} is SUCCESS, FAILED or CANCELLED. */
public record TaskRecord(int id, String toolName, String status, String summary) {}
