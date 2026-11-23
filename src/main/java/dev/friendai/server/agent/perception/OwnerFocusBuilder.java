package dev.friendai.server.agent.perception;

import dev.friendai.agent.perception.OwnerFocus;
import dev.friendai.agent.perception.OwnerGear;
import dev.friendai.agent.perception.PerceptionSnapshot;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.spatial.RelativeDirection;
import dev.friendai.world.block.MiningToolView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
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

    private OwnerFocusBuilder() {}

    /**
     * The view of {@code principal}, or of the companion's owner when no speaker was named. A named speaker who is
     * gone is reported as offline and never replaced by the owner: their aim and gear must not answer for them.
     */
    public static OwnerFocus of(CompanionEntity c, UUID principal) {
        if (!(c.level() instanceof ServerLevel level)) {
            return OwnerFocus.unavailable("Owner", OwnerFocus.Visibility.OFFLINE, 0);
        }
        ServerPlayer player = named(c, principal);
        if (player == null) {
            boolean owner = principal == null || principal.equals(c.ownerUuid());
            return OwnerFocus.unavailable(owner ? "Owner" : "Speaker", OwnerFocus.Visibility.OFFLINE, 0);
        }
        boolean isOwner = player.getUUID().equals(c.ownerUuid());
        String label = isOwner ? "Owner" : "Speaker (" + player.getName().getString() + ")";
        if (player.level() != level) {
            return OwnerFocus.unavailable(label, OwnerFocus.Visibility.OTHER_DIMENSION, 0);
        }
        double distance = c.distanceTo(player);
        if (!inView(c, player, distance)) {
            return OwnerFocus.unavailable(label, OwnerFocus.Visibility.OUT_OF_SIGHT, distance);
        }
        return OwnerFocus.visible(
                label,
                distance,
                coords(player.blockPosition()),
                standingOn(level, player),
                lookingAt(c, level, player),
                gear(player),
                RelativeDirection.relativeTo(c.getYRot(), player.getX() - c.getX(), player.getZ() - c.getZ()),
                facing(RelativeDirection.relativeTo(
                        player.getYRot(), c.getX() - player.getX(), c.getZ() - player.getZ())),
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                player.getArmorValue(),
                bodyState(player));
    }

    /**
     * The block {@code principal} (or the owner when no principal was named) is looking at, if that player is close
     * enough and in sight. Server thread only.
     */
    public static Optional<BlockPos> lookedAtBlock(CompanionEntity c, UUID principal) {
        ServerPlayer player = visibleSpeaker(c, principal);
        if (player == null) {
            return Optional.empty();
        }
        HitResult hit = player.pick(LOOK_RANGE, 1.0F, false);
        return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                ? Optional.of(blockHit.getBlockPos())
                : Optional.empty();
    }

    /** The block the speaker stands in, if that player is close enough and in sight. Server thread only. */
    public static Optional<BlockPos> speakerBlock(CompanionEntity c, UUID principal) {
        return Optional.ofNullable(visibleSpeaker(c, principal)).map(ServerPlayer::blockPosition);
    }

    private static ServerPlayer visibleSpeaker(CompanionEntity c, UUID principal) {
        if (!(c.level() instanceof ServerLevel level)) {
            return null;
        }
        ServerPlayer player = named(c, principal);
        return player != null && player.level() == level && inView(c, player, c.distanceTo(player)) ? player : null;
    }

    /**
     * The named player, or the owner when nobody was named. A named player who cannot be resolved yields null
     * rather than the owner, so an absent speaker is never answered for by somebody else.
     */
    private static ServerPlayer named(CompanionEntity c, UUID principal) {
        if (principal == null) {
            return c.owner().orElse(null);
        }
        return c.level() instanceof ServerLevel level
                ? level.getServer().getPlayerList().getPlayer(principal)
                : null;
    }

    private static boolean inView(CompanionEntity c, ServerPlayer player, double distance) {
        return distance <= VIEW_RANGE && c.hasLineOfSight(player);
    }

    private static String facing(String towardsCompanion) {
        return switch (towardsCompanion) {
            case "ahead" -> "looking at you";
            case "ahead-left", "ahead-right" -> "looking toward you";
            case "left", "right" -> "facing sideways";
            default -> "looking away from you";
        };
    }

    private static String bodyState(ServerPlayer player) {
        List<String> parts = new ArrayList<>();
        if (player.isInWater()) {
            parts.add("in water");
        }
        if (player.isInLava()) {
            parts.add("in lava");
        }
        if (player.isOnFire()) {
            parts.add("on fire");
        }
        return parts.isEmpty() ? "ok" : String.join(", ", parts);
    }

    private static String standingOn(ServerLevel level, ServerPlayer player) {
        return GroundText.under(level, player);
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
                player,
                eye,
                end,
                box,
                e -> e != companion
                        && e != player.getVehicle()
                        && !e.isPassengerOfSameVehicle(player)
                        && !e.isSpectator()
                        && e.isPickable(),
                LOOK_RANGE * LOOK_RANGE);
        double entityDistance = entityHit == null ? Double.MAX_VALUE : eye.distanceTo(entityHit.getLocation());

        if (entityHit != null && entityDistance < blockDistance) {
            Entity target = entityHit.getEntity();
            return "entity " + shortName(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType())) + " at "
                    + coords(target.blockPosition()) + " (" + blocks(entityDistance) + " blocks)";
        }
        if (blockPos != null) {
            return "block " + blockName(level, blockPos) + " at " + coords(blockPos) + " (" + blocks(blockDistance)
                    + " blocks)" + blockFacts(level, blockPos, companion);
        }
        return "nothing within " + (int) LOOK_RANGE + " blocks";
    }

    private static String blockFacts(ServerLevel level, BlockPos pos, CompanionEntity companion) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return "; unbreakable";
        }
        StringBuilder facts = new StringBuilder("; hardness ").append(PerceptionSnapshot.number(hardness));
        if (!state.requiresCorrectToolForDrops()) {
            return facts.append(", can be dug by hand").toString();
        }
        String tool = MiningToolView.currentToolFor(companion, state).orElse(null);
        return facts.append(
                        tool == null
                                ? ", needs a proper tool (you have none)"
                                : ", needs a proper tool (you have " + tool + ")")
                .toString();
    }

    private static OwnerGear gear(ServerPlayer player) {
        List<OwnerGear.Piece> worn = new ArrayList<>();
        for (OwnerGear.Slot slot : OwnerGear.Slot.values()) {
            ItemStack stack = player.getItemBySlot(armorSlot(slot));
            if (!stack.isEmpty()) {
                worn.add(new OwnerGear.Piece(slot, itemName(stack)));
            }
        }
        return new OwnerGear(itemName(player.getMainHandItem()), itemName(player.getOffhandItem()), worn);
    }

    private static EquipmentSlot armorSlot(OwnerGear.Slot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }

    /** The item id the prompt shows, with a stack count once it is more than one; empty for an empty stack. */
    private static String itemName(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        String name = shortName(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        return stack.getCount() > 1 ? name + " x" + stack.getCount() : name;
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
