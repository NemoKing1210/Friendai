package dev.friendai.client.ui;

import net.minecraft.network.chat.Component;

/** Localized record metadata; authored names and bodies remain their original text. */
final class SkillLabels {
    private SkillLabels() {}

    static Component value(String name) {
        return Component.translatable(
                switch (name) {
                    case "OWNER" -> "friendai.menu.skills.source_owner";
                    case "MODEL" -> "friendai.menu.skills.source_model";
                    case "BUILTIN" -> "friendai.menu.skills.source_builtin";
                    case "COMPANION" -> "friendai.menu.skills.scope_companion";
                    case "SERVER" -> "friendai.menu.skills.scope_server";
                    case "PENDING" -> "friendai.menu.skills.pending";
                    case "ACTIVE" -> "friendai.menu.skills.active";
                    case "ON" -> "friendai.menu.skills.on";
                    default -> "friendai.menu.skills.all";
                });
    }
}
