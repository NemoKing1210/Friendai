package dev.friendai.brain;

/** @param language "auto" (reply in the player's language) or a language name the companion always uses */
public record Persona(String name, String description, String language) {}
