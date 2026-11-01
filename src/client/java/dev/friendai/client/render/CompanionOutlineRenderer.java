package dev.friendai.client.render;

import dev.friendai.FriendaiMod;
import dev.friendai.entity.CompanionEntity;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;

/**
 * Draws a box around the owner's own companion when it is far away or hidden behind terrain.
 *
 * <p>Client only and owner only: the companion's synced owner UUID is compared with the local player, so another
 * player's companion is never highlighted. No arrow and no HUD line — the box answers "where", nothing else.
 */
public final class CompanionOutlineRenderer {
    /** Beyond this the companion is not obviously in view even on flat ground. */
    private static final double FAR_BLOCKS = 12.0;

    private static final int COLOR = 0xFF7FE0FF;
    private static final float WIDTH = 1.5f;

    private CompanionOutlineRenderer() {}

    public static void register() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> draw());
    }

    private static void draw() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        if (!dev.friendai.client.FriendaiClient.clientConfig().social.outline) {
            return;
        }
        String owner = player.getUUID().toString();
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof CompanionEntity companion)) {
                continue;
            }
            if (!owner.equals(companion.syncedOwner())) {
                continue;
            }
            if (!needsOutline(player, companion)) {
                continue;
            }
            Gizmos.cuboid(companion.getBoundingBox().inflate(0.1), GizmoStyle.stroke(COLOR, WIDTH))
                    .setAlwaysOnTop();
        }
    }

    /** Only when the companion is far away or the player has no line of sight to it. */
    private static boolean needsOutline(LocalPlayer player, CompanionEntity companion) {
        try {
            return player.distanceTo(companion) > FAR_BLOCKS || !player.hasLineOfSight(companion);
        } catch (RuntimeException failure) {
            FriendaiMod.LOGGER.debug("Companion outline skipped after a sight check failed", failure);
            return false;
        }
    }

    /** The owner UUID of a companion as the client sees it, for tests and for the keybind's status line. */
    static UUID ownerOf(CompanionEntity companion) {
        String raw = companion.syncedOwner();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
