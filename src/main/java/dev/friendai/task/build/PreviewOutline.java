package dev.friendai.task.build;

import dev.friendai.features.construction.blueprint.Blueprint;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The outline {@code preview_structure} shows the owner: END_ROD dots along the 12 edges of the footprint, every
 * 10 ticks for 10 seconds, sent to the owner only.
 */
public final class PreviewOutline {
    public static final int DURATION_TICKS = 200;
    private static final int INTERVAL_TICKS = 10;
    private static final double STEP = 0.5;

    private final UUID owner;
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double sizeX;
    private final double sizeY;
    private final double sizeZ;
    private int ticks;

    public PreviewOutline(UUID owner, Placement.PlacedBlueprint placed, Blueprint blueprint) {
        this.owner = owner;
        this.minX = placed.origin().getX();
        this.minY = placed.origin().getY();
        this.minZ = placed.origin().getZ();
        this.sizeX = Placement.rotatedWidth(blueprint, placed.rotation()) - 1;
        this.sizeZ = Placement.rotatedDepth(blueprint, placed.rotation()) - 1;
        this.sizeY = blueprint.height() - 1;
    }

    /** Draws the next frame when due; returns false when the outline is over or the owner is gone. */
    public boolean tick(ServerLevel level) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player == null || ticks > DURATION_TICKS) {
            return false;
        }
        if (ticks % INTERVAL_TICKS == 0) {
            draw(level, player);
        }
        ticks++;
        return true;
    }

    private void draw(ServerLevel level, ServerPlayer player) {
        double maxX = minX + sizeX;
        double maxY = minY + sizeY;
        double maxZ = minZ + sizeZ;
        for (double x = minX; x <= maxX; x += STEP) {
            edge(level, player, x, minY, minZ);
            edge(level, player, x, minY, maxZ);
            edge(level, player, x, maxY, minZ);
            edge(level, player, x, maxY, maxZ);
        }
        for (double z = minZ; z <= maxZ; z += STEP) {
            edge(level, player, minX, minY, z);
            edge(level, player, maxX, minY, z);
            edge(level, player, minX, maxY, z);
            edge(level, player, maxX, maxY, z);
        }
        for (double y = minY; y <= maxY; y += STEP) {
            edge(level, player, minX, y, minZ);
            edge(level, player, maxX, y, minZ);
            edge(level, player, minX, y, maxZ);
            edge(level, player, maxX, y, maxZ);
        }
    }

    private static void edge(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        level.sendParticles(player, ParticleTypes.END_ROD, true, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
