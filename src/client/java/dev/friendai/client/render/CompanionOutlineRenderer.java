package dev.friendai.client.render;

import dev.friendai.FriendaiMod;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.presentation.companion.CompanionOutline;
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
 * player's companion is never highlighted. No arrow and no HUD line — the box answers "where", nothing else. The
 * visibility rule itself is the pure {@link CompanionOutline} policy; this class only reads the world and draws.
 */
public final class CompanionOutlineRenderer {
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
        boolean enabled = dev.friendai.client.FriendaiClient.clientConfig().social.outline;
        String owner = player.getUUID().toString();
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof CompanionEntity companion)) {
                continue;
            }
            if (!needsOutline(enabled, player, companion, owner.equals(companion.syncedOwner()))) {
                continue;
            }
            Gizmos.cuboid(companion.getBoundingBox().inflate(0.1), GizmoStyle.stroke(COLOR, WIDTH))
                    .setAlwaysOnTop();
        }
    }

    /** Only when the option is on, this is the owner's own companion, and it is far away or out of sight. */
    private static boolean needsOutline(
            boolean enabled, LocalPlayer player, CompanionEntity companion, boolean ownerViewing) {
        if (!CompanionOutline.applies(enabled, ownerViewing)) {
            return false;
        }
        try {
            return CompanionOutline.shouldDraw(
                    enabled, ownerViewing, player.distanceToSqr(companion), player.hasLineOfSight(companion));
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
