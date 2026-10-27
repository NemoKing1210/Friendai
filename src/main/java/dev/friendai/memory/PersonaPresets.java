package dev.friendai.memory;

import java.util.List;
import java.util.Optional;

/** The ready-made personalities the memory tab offers. The texts go to the model, so they are English. */
public final class PersonaPresets {
    /** {@code labelKey} is the translation key of the button label. */
    public record Preset(String id, String labelKey, String text) {}

    private static final List<Preset> ALL = List.of(
            new Preset(
                    "friendly",
                    "friendai.menu.description.preset.friendly",
                    "A friendly and resourceful Minecraft companion. Keeps answers short and helpful."),
            new Preset(
                    "cheerful",
                    "friendai.menu.description.preset.cheerful",
                    "An upbeat, enthusiastic Minecraft companion. Celebrates small wins, makes light jokes and keeps"
                            + " answers short."),
            new Preset(
                    "calm",
                    "friendai.menu.description.preset.calm",
                    "A calm, measured Minecraft companion. Speaks gently, reassures the player when things go wrong"
                            + " and keeps answers short."),
            new Preset(
                    "witty",
                    "friendai.menu.description.preset.witty",
                    "A witty Minecraft companion with dry humour and gentle teasing. Still helpful and never rude."
                            + " Keeps answers short."),
            new Preset(
                    "adventurer",
                    "friendai.menu.description.preset.adventurer",
                    "A brave, eager adventurer. Loves exploring, mining and fighting monsters, and talks like a bold"
                            + " explorer. Keeps answers short."),
            new Preset(
                    "gruff",
                    "friendai.menu.description.preset.gruff",
                    "A blunt, slightly grumpy but loyal Minecraft companion. Uses few words, complains a little and"
                            + " always gets the job done."));

    private PersonaPresets() {}

    public static List<Preset> all() {
        return ALL;
    }

    /** The preset whose text equals {@code text} exactly, if any. */
    public static Optional<Preset> matching(String text) {
        return ALL.stream().filter(p -> p.text().equals(text)).findFirst();
    }
}
