package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.perception.OwnerFocus;
import dev.friendai.perception.OwnerFocus.Visibility;
import dev.friendai.perception.OwnerFocusBuilder;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** What the companion reads of the speaker's position and view. Each test owns its platform and players. */
public class OwnerViewTests {
    /** Yaw that makes a player look toward +X (east). */
    private static final float EAST = -90F;

    /** An owner on a platform at (2.5, 2, 2.5) looking east, and a companion 5 blocks south of them. */
    private static CompanionEntity companionFor(GameTestHelper h, ServerPlayer owner) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 7);
        companion.setOwner(owner.getUUID());
        return companion;
    }

    private static ServerPlayer lookingEast(GameTestHelper h, double x, double y, double z) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, x, y, z);
        player.setYRot(EAST);
        player.setYHeadRot(EAST);
        player.setXRot(0F);
        player.setOnGround(true);
        return player;
    }

    @GameTest
    public void viewSeesTargetBlock(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(8, 3, 2, Blocks.OAK_LOG);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(focus.visibility() == Visibility.VISIBLE, "expected VISIBLE: " + focus);
        h.assertTrue(focus.label().equals("Owner"), "label: " + focus.label());
        h.assertTrue(focus.lookingAt().startsWith("block oak_log at "), "lookingAt: " + focus.lookingAt());
        h.assertTrue(focus.holding().equals("nothing"), "holding: " + focus.holding());
        h.assertTrue(focus.distance() > 4.5 && focus.distance() < 5.5, "distance: " + focus.distance());
        h.succeed();
    }

    @GameTest
    public void viewHoldingIsTheMainHandItem(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue("diamond_pickaxe".equals(focus.holding()), "holding: " + focus);
        h.succeed();
    }

    @GameTest
    public void playerViewToolReportsTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(8, 3, 2, Blocks.OAK_LOG);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = companionFor(h, owner);
        var tools = dev.friendai.tool.builtin.FriendaiTools.create(() -> 32).bind(companion);
        var call =
                new dev.friendai.llm.model.ToolCall("1", "player_view", new com.google.gson.JsonObject(), "{}", null);
        var result = tools.execute(
                call, new dev.friendai.tool.Caller(owner.getUUID(), "owner", dev.friendai.tool.AccessLevel.OWNER));
        h.assertTrue(!result.error(), "error: " + result.content());
        h.assertTrue(result.content().contains("looking at: block oak_log at"), "content: " + result.content());
        h.succeed();
    }

    @GameTest
    public void viewSeesEntityInFrontOfBlock(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(10, 3, 2, Blocks.OAK_LOG);
        var stand = h.spawn(EntityTypes.ARMOR_STAND, 6, 2, 2);
        var standPos = h.absoluteVec(new net.minecraft.world.phys.Vec3(6.5, 2, 2.5));
        stand.setPos(standPos.x, standPos.y, standPos.z);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(focus.lookingAt().startsWith("entity armor_stand at "), "lookingAt: " + focus.lookingAt());
        h.succeed();
    }

    @GameTest
    public void viewStandingOn(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus ground = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(ground.standingOn().startsWith("stone at "), "on the ground: " + ground.standingOn());

        var air = h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 4, 2.5));
        owner.teleportTo(air.x, air.y, air.z);
        owner.setOnGround(false);
        OwnerFocus jumping = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(
                jumping.standingOn().startsWith("in the air above stone at ")
                        && jumping.standingOn().endsWith("(3 blocks below)"),
                "in the air: " + jumping.standingOn());

        var low = h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 2.5, 2.5));
        owner.teleportTo(low.x, low.y, low.z);
        owner.setOnGround(false);
        OwnerFocus hopping = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(hopping.standingOn().endsWith("(1 block below)"), "one block: " + hopping.standingOn());
        h.succeed();
    }

    @GameTest
    public void viewStandingInTheAirOverNothing(GameTestHelper h) {
        ServerPlayer owner = lookingEast(h, 2.5, 30, 2.5);
        owner.setOnGround(false);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 30, 7);
        companion.setOwner(owner.getUUID());
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(
                focus.visibility() == Visibility.VISIBLE
                        && focus.standingOn().equals("in the air (nothing within 16 blocks below)"),
                "focus: " + focus);
        h.succeed();
    }

    @GameTest
    public void viewLookingAtSkyIsNothing(GameTestHelper h) {
        // High above the test box, whose ceiling would otherwise be the first thing hit.
        ServerPlayer owner = lookingEast(h, 2.5, 30, 2.5);
        owner.setOnGround(false);
        owner.setXRot(-90F);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 30, 7);
        companion.setOwner(owner.getUUID());
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(focus.visibility() == Visibility.VISIBLE, "expected VISIBLE: " + focus);
        h.assertTrue("nothing within 64 blocks".equals(focus.lookingAt()), "lookingAt: " + focus.lookingAt());
        h.assertTrue(!focus.toPromptText().contains("null"), "prompt text: " + focus.toPromptText());
        h.succeed();
    }

    @GameTest
    public void viewBlockedByWallIsOutOfSight(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        for (int x = 0; x <= 6; x++) {
            for (int y = 2; y <= 6; y++) {
                h.setBlock(x, y, 5, Blocks.STONE);
            }
        }
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(focus.visibility() == Visibility.OUT_OF_SIGHT, "expected OUT_OF_SIGHT: " + focus);
        h.assertTrue(focus.lookingAt() == null, "no look data behind a wall: " + focus);
        h.succeed();
    }

    @GameTest
    public void viewTooFarIsOutOfSight(GameTestHelper h) {
        // High in clear air, so only the distance can hide the player.
        ServerPlayer owner = lookingEast(h, 2.5, 30, 2.5);
        owner.setOnGround(false);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 42, 30, 2);
        companion.setOwner(owner.getUUID());
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(focus.visibility() == Visibility.OUT_OF_SIGHT, "expected OUT_OF_SIGHT: " + focus);
        h.assertTrue(focus.distance() > OwnerFocusBuilder.VIEW_RANGE, "distance: " + focus.distance());
        h.succeed();
    }

    @GameTest
    public void viewNoPlayerIsOffline(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(UUID.randomUUID());
        OwnerFocus focus = OwnerFocusBuilder.of(companion, null);
        h.assertTrue(focus.visibility() == Visibility.OFFLINE, "expected OFFLINE: " + focus);
        h.assertTrue(focus.label().equals("Owner"), "label: " + focus.label());
        OwnerFocus unknownSpeaker = OwnerFocusBuilder.of(companion, UUID.randomUUID());
        h.assertTrue(unknownSpeaker.visibility() == Visibility.OFFLINE, "expected OFFLINE: " + unknownSpeaker);
        h.succeed();
    }

    @GameTest
    public void viewReportsHealthFoodAndArmor(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        owner.setHealth(14f);
        owner.getFoodData().setFoodLevel(17);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(focus.health() == 14f, "health: " + focus.health());
        h.assertTrue(focus.food() == 17, "food: " + focus.food());
        h.assertTrue(focus.toPromptText().contains("health: 14/20, food 17/20, armor "), focus.toPromptText());
        h.succeed();
    }

    @GameTest
    public void viewReportsFacing(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 6, 2, 2);
        companion.setOwner(owner.getUUID());
        companion.setYRot(90F); // face west, toward the owner
        OwnerFocus focus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(focus.facing().equals("looking at you"), "facing: " + focus.facing());
        h.assertTrue(focus.relative().equals("ahead"), "relative: " + focus.relative());
        h.succeed();
    }

    @GameTest
    public void viewBlockSaysWhichToolIsNeeded(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        h.setBlock(8, 3, 2, Blocks.IRON_ORE);
        ServerPlayer owner = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus withoutTool = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(withoutTool.lookingAt().contains("needs a proper tool (you have none)"), withoutTool.lookingAt());

        companion.inventory().addItem(new ItemStack(Items.IRON_PICKAXE));
        OwnerFocus withTool = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(withTool.lookingAt().contains("you have iron_pickaxe"), withTool.lookingAt());
        h.succeed();
    }

    @GameTest
    public void guestViewIsTheGuest(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = lookingEast(h, 8.5, 2, 8.5);
        ServerPlayer guest = lookingEast(h, 2.5, 2, 2.5);
        guest.setHealth(6f);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus focus = OwnerFocusBuilder.of(companion, guest.getUUID());
        h.assertTrue(focus.label().startsWith("Speaker ("), focus.label());
        h.assertTrue(focus.health() == 6f, "health: " + focus.health());
        h.succeed();
    }

    @GameTest
    public void viewSpeakerLabel(GameTestHelper h) {
        GameTestSupport.platform(h, 12);
        ServerPlayer owner = lookingEast(h, 8.5, 2, 8.5);
        ServerPlayer speaker = lookingEast(h, 2.5, 2, 2.5);
        CompanionEntity companion = companionFor(h, owner);
        OwnerFocus speakerFocus = OwnerFocusBuilder.of(companion, speaker.getUUID());
        h.assertTrue(speakerFocus.label().startsWith("Speaker ("), "label: " + speakerFocus.label());
        h.assertTrue(speakerFocus.visibility() == Visibility.VISIBLE, "expected VISIBLE: " + speakerFocus);
        OwnerFocus ownerFocus = OwnerFocusBuilder.of(companion, owner.getUUID());
        h.assertTrue(ownerFocus.label().equals("Owner"), "label: " + ownerFocus.label());
        OwnerFocus fallback = OwnerFocusBuilder.of(companion, null);
        h.assertTrue(fallback.label().equals("Owner"), "null principal falls back to the owner: " + fallback.label());
        h.succeed();
    }
}
