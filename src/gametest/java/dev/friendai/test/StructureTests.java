package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.structure.SpecialStructure;
import dev.friendai.structure.StructureBook;
import dev.friendai.structure.StructureSnapshot;
import dev.friendai.task.structure.StructureIndex;
import dev.friendai.task.structure.StructureSite;
import dev.friendai.task.structure.StructureSnapshotBuilder;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Rotation;

public class StructureTests {
    private static SpecialStructure structure(String id) {
        return StructureBook.withBuiltins().get(id).orElseThrow();
    }

    private static StructureSnapshot snapshot(GameTestHelper h, CompanionEntity c, String id, int max) {
        StructureSite site = new StructureSite(h.absolutePos(new BlockPos(2, 2, 2)), Rotation.NONE);
        return StructureSnapshotBuilder.build(h.getLevel(), c, structure(id), site, max);
    }

    @GameTest
    public void book_matches_registries(GameTestHelper h) {
        var problems = StructureIndex.validate(StructureBook.withBuiltins());
        h.assertTrue(problems.isEmpty(), "registry problems: " + problems);
        h.succeed();
    }

    @GameTest
    public void snapshot_reports_free_site(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.footprintFree(), "footprint should be free");
        h.assertFalse(snap.entityInFootprint(), "nobody stands there");
        h.assertFalse(snap.protectedArea(), "site is not protected");
        h.assertFalse(snap.inEnd(), "overworld");
        h.assertTrue(snap.correctBodyBlocks().isEmpty(), "nothing built yet");
        h.assertFalse(snap.outcomeAlreadyPresent(), "nothing finished yet");
        h.assertTrue(snap.nearbySpawned() == 0, "no golems yet, got " + snap.nearbySpawned());
        h.succeed();
    }

    @GameTest
    public void snapshot_sees_companion_in_footprint(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 3, 2, 2);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.entityInFootprint(), "the companion stands in the footprint");
        h.succeed();
    }

    @GameTest
    public void snapshot_counts_nearby_golems(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        h.spawn(EntityTypes.IRON_GOLEM, 6, 2, 2);
        h.spawn(EntityTypes.IRON_GOLEM, 7, 2, 3);
        h.spawn(EntityTypes.SNOW_GOLEM, 6, 2, 3);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.nearbySpawned() == 2, "expected 2 iron golems, got " + snap.nearbySpawned());
        h.succeed();
    }

    @GameTest
    public void snapshot_marks_matching_body_blocks(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 10, 2, 10);
        h.setBlock(3, 2, 2, net.minecraft.world.level.block.Blocks.IRON_BLOCK);
        StructureSnapshot snap = snapshot(h, c, "iron_golem", 4);
        h.assertTrue(snap.correctBodyBlocks().contains(new dev.friendai.structure.Offset(1, 0, 0)),
                "the iron block at offset 1,0,0 should count as built");
        h.assertTrue(snap.footprintFree(), "a correct body block does not block the footprint");
        h.succeed();
    }
}
