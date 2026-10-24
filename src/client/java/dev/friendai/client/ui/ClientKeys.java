package dev.friendai.client.ui;

import dev.friendai.FriendaiMod;
import net.minecraft.client.KeyMapping;

/** The mod's key category. Registering the same category twice throws, so it is created once, lazily. */
public final class ClientKeys {
    private static KeyMapping.Category category;

    private ClientKeys() {}

    public static synchronized KeyMapping.Category category() {
        if (category == null) {
            category = KeyMapping.Category.register(FriendaiMod.id("main"));
        }
        return category;
    }
}
