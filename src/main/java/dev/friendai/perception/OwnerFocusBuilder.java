package dev.friendai.perception;

import dev.friendai.entity.CompanionEntity;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Where the speaking player stands and what they look at, read from the world. Server thread only. */
public final class OwnerFocusBuilder {
    /** A player farther than this, or behind a wall, is out of sight. */
    public static final double VIEW_RANGE = 32.0;
    /** How far along the player's look ray blocks and entities are reported. */
    public static final double LOOK_RANGE = 64.0;

    private static final int GROUND_SCAN = 16;

    private OwnerFocusBuilder() {}

    /** The view of {@code principal} if that player is online, else of the companion's owner. */
    public static OwnerFocus of(CompanionEntity c, UUID principal) {
        if (!(c.level() instanceof ServerLevel level)) {
            return new OwnerFocus("Owner", OwnerFocus.Visibility.OFFLINE, 0, null, null, null, null);
        }
        ServerPlayer player = null;
        if (principal != null) {
            player = level.getServer().getPlayerList().getPlayer(principal);
        }
        if (player == null) {
            player = c.owner().orElse(null);
        }
        if (player == null) {
            return new OwnerFocus("Owner", OwnerFocus.Visibility.OFFLINE, 0, null, null, null, null);
        }
        boolean isOwner = player.getUUID().equals(c.ownerUuid());
        String label = isOwner ? "Owner" : "Speaker (" + player.getName().getString() + ")";
        if (player.level() != level) {
            return new OwnerFocus(label, OwnerFocus.Visibility.OTHER_DIMENSION, 0, null, null, null, null);
        }
        double distance = c.distanceTo(player);
        if (distance > VIEW_RANGE || !c.hasLineOfSight(player)) {
            return new OwnerFocus(label, OwnerFocus.Visibility.OUT_OF_SIGHT, distance, null, null, null, null);
        }
        return new OwnerFocus(
                label,
                OwnerFocus.Visibility.VISIBLE,
                distance,
                coords(player.blockPosition()),
                standingOn(level, player),
                lookingAt(c, level, player),
                holding(player));
    }

    private static String standingOn(ServerLevel level, ServerPlayer player) {
        if (player.onGround()) {
            BlockPos under = player.getOnPos();
            return blockName(level, under) + " at " + coords(under);
        }
        BlockPos feet = player.blockPosition();
        for (int n = 1; n <= GROUND_SCAN; n++) {
            BlockPos pos = feet.below(n);
            if (!level.hasChunkAt(pos)) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                return "in the air above " + blockName(level, pos) + " at " + coords(pos) + " (" + n + " blocks below)";
            }
        }
        return "in the air (nothing within " + GROUND_SCAN + " blocks below)";
    }

    private static String lookingAt(CompanionEntity companion, ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(LOOK_RANGE));

        double blockDistance = Double.MAX_VALUE;
        BlockPos blockPos = null;
        HitResult hit = player.pick(LOOK_RANGE, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            blockPos = blockHit.getBlockPos();
            blockDistance = eye.distanceTo(hit.getLocation());
        }

        AABB box = player.getBoundingBox().expandTowards(look.scale(LOOK_RANGE)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, end, box, e -> e != companion && !e.isSpectator() && e.isPickable(), LOOK_RANGE * LOOK_RANGE);
        double entityDistance = entityHit == null ? Double.MAX_VALUE : eye.distanceTo(entityHit.getLocation());

        if (entityHit != null && entityDistance < blockDistance) {
            Entity target = entityHit.getEntity();
            return "entity " + shortName(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()))
                    + " at " + coords(target.blockPosition()) + " (" + blocks(entityDistance) + " blocks)";
        }
        if (blockPos != null) {
            return "block " + blockName(level, blockPos) + " at " + coords(blockPos) + " (" + blocks(blockDistance)
                    + " blocks)";
        }
        return "nothing within " + (int) LOOK_RANGE + " blocks";
    }

    private static String holding(ServerPlayer player) {
        var stack = player.getMainHandItem();
        return stack.isEmpty() ? "nothing" : shortName(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    private static String blockName(ServerLevel level, BlockPos pos) {
        return shortName(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String blocks(double distance) {
        return String.format(Locale.ROOT, "%.1f", distance);
    }

    private static String shortName(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
}
