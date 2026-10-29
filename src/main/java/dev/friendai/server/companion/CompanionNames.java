package dev.friendai.server.companion;

import dev.friendai.features.conversation.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Renders a companion name in the colour derived from it by {@link NameColor}, everywhere it is shown. */
public final class CompanionNames {
    private CompanionNames() {}

    public static Style style(String name) {
        return Style.EMPTY.withColor(NameColor.rgb(name));
    }

    public static Component styled(String name) {
        return Component.literal(name).withStyle(style(name));
    }
}
