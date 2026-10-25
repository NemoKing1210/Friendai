package dev.friendai.task.queue;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.queue.GuardFacts;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** One server-thread facts pass for queue guards. */
public final class CompanionGuardFacts implements GuardFacts {
    private final CompanionEntity companion;
    public CompanionGuardFacts(CompanionEntity companion) { this.companion = companion; }
    @Override public int countItems(String id) {
        var matcher = ItemMatcher.parse(id).orElse(null); if (matcher == null) return 0; int count=matcher.test(companion.getMainHandItem())?companion.getMainHandItem().getCount():0;
        for(int i=0;i<companion.inventory().getContainerSize();i++){ItemStack stack=companion.inventory().getItem(i);if(matcher.test(stack))count+=stack.getCount();} return count;
    }
    @Override public int emptySlots() { int n=0;for(int i=0;i<companion.inventory().getContainerSize();i++)if(companion.inventory().getItem(i).isEmpty())n++;return n; }
    @Override public float healthFraction() { return companion.getHealth()/Math.max(1f,companion.getMaxHealth()); }
    @Override public int ownerDistance() { ServerPlayer owner=companion.owner().orElse(null); return owner==null||owner.level()!=companion.level()?-1:(int)Math.round(Math.sqrt(companion.distanceToSqr(owner))); }
    @Override public int countBlocks(String id,int radius) { var matcher=BlockMatcher.parse(id).orElse(null);if(matcher==null)return 0;int n=0;BlockPos p=companion.blockPosition();for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++){BlockPos at=p.offset(x,y,z);if(companion.level().hasChunkAt(at)&&matcher.test(companion.level().getBlockState(at)))n++;}return n; }
    @Override public String dimension() { return companion.level().dimension().identifier().toString(); }
}
