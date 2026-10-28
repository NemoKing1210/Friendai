package dev.friendai.task.queue;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.queue.GuardFacts;
import dev.friendai.task.inventory.ItemMatcher;
import dev.friendai.task.mining.BlockMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.Map;

/** One server-thread facts pass for queue guards. */
public final class CompanionGuardFacts implements GuardFacts {
    private final CompanionEntity companion;
    private final Map<String, Integer> itemCounts = new HashMap<>();
    private final Map<String, Boolean> blockFacts = new HashMap<>();
    private Integer emptySlots, ownerDistance;
    private Float healthFraction;
    private String dimension;
    private long blockStamp = Long.MIN_VALUE;
    private static final long BLOCK_TTL_TICKS = 40;
    public CompanionGuardFacts(CompanionEntity companion) { this.companion = companion; }
    /** Starts a new pass: cheap facts are re-read, the expensive block scans are reused for a short while. */
    public void refresh() {
        itemCounts.clear(); emptySlots = null; ownerDistance = null; healthFraction = null; dimension = null;
        long now = companion.level().getGameTime();
        if (now - blockStamp >= BLOCK_TTL_TICKS || now < blockStamp) { blockFacts.clear(); blockStamp = now; }
    }
    @Override public int countItems(String id) {
        if (itemCounts.containsKey(id)) return itemCounts.get(id);
        var matcher = ItemMatcher.parse(id).orElse(null); if (matcher == null) return 0; int count=matcher.test(companion.getMainHandItem())?companion.getMainHandItem().getCount():0;
        for(int i=0;i<companion.inventory().getContainerSize();i++){ItemStack stack=companion.inventory().getItem(i);if(matcher.test(stack))count+=stack.getCount();} itemCounts.put(id,count); return count;
    }
    @Override public int emptySlots() { if(emptySlots==null){int n=0;for(int i=0;i<companion.inventory().getContainerSize();i++)if(companion.inventory().getItem(i).isEmpty())n++;emptySlots=n;}return emptySlots; }
    @Override public float healthFraction() { if(healthFraction==null)healthFraction=companion.getHealth()/Math.max(1f,companion.getMaxHealth());return healthFraction; }
    @Override public int ownerDistance() { if(ownerDistance==null){ServerPlayer owner=companion.owner().orElse(null);ownerDistance=owner==null||owner.level()!=companion.level()?-1:(int)Math.round(Math.sqrt(companion.distanceToSqr(owner)));}return ownerDistance; }
    @Override public int countBlocks(String id,int radius) { var matcher=BlockMatcher.parse(id).orElse(null);if(matcher==null)return 0;int n=0;BlockPos p=companion.blockPosition();for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++){BlockPos at=p.offset(x,y,z);if(companion.level().hasChunkAt(at)&&matcher.test(companion.level().getBlockState(at)))n++;}return n; }
    @Override public String dimension() { if(dimension==null)dimension=companion.level().dimension().identifier().toString();return dimension; }
    @Override public boolean blockInFront(String id, int distance) {
        var matcher = BlockMatcher.parse(id).orElse(null);
        if (matcher == null || distance < 1 || distance > 8) return false;
        BlockPos feet = companion.blockPosition().relative(companion.getDirection(), distance);
        if (!companion.level().hasChunkAt(feet)) return false;
        return matcher.test(companion.level().getBlockState(feet))
                || (companion.level().hasChunkAt(feet.above()) && matcher.test(companion.level().getBlockState(feet.above())));
    }
    @Override public String biome() {
        BlockPos feet = companion.blockPosition();
        if (!companion.level().hasChunkAt(feet)) return "";
        return companion.level().getBiome(feet).unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }
    @Override public boolean fluidAhead() {
        BlockPos feet = companion.blockPosition().relative(companion.getDirection());
        if (!companion.level().hasChunkAt(feet)) return false;
        return !companion.level().getBlockState(feet).getFluidState().isEmpty()
                || (companion.level().hasChunkAt(feet.above())
                    && !companion.level().getBlockState(feet.above()).getFluidState().isEmpty());
    }
    @Override public boolean hasBlocks(String id,int radius,int count) {
        String key=id+":"+radius+":"+count; if(blockFacts.containsKey(key))return blockFacts.get(key);
        var matcher=BlockMatcher.parse(id).orElse(null);if(matcher==null)return false;int found=0;BlockPos p=companion.blockPosition();
        for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++){
            BlockPos at=p.offset(x,y,z);if(companion.level().hasChunkAt(at)&&matcher.test(companion.level().getBlockState(at))&&++found>=count){blockFacts.put(key,true);return true;}
        }
        blockFacts.put(key,false);return false;
    }
}
