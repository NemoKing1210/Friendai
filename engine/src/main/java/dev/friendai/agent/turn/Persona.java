package dev.friendai.agent.turn;

/**
 * @param language "auto" (reply in the player's language) or a language name the companion always uses
 * @param ownerLanguage the language the owner writes in, learned from their messages; null until known. Used only
 *                      when {@code language} is auto, as a default the owner's request can still override
 */
public record Persona(String name, String description, String language, String ownerLanguage) {
    public Persona(String name, String description, String language) {
        this(name, description, language, null);
    }

    public Persona {
        ownerLanguage = ownerLanguage == null || ownerLanguage.isBlank() ? null : ownerLanguage.strip();
    }
}
