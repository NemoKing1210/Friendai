package dev.friendai.test;

import dev.friendai.core.CreativeModeService;
import dev.friendai.creative.CreativeActionContext;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.ExecuteCommandTask;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Items;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;

public final class CreativeCommandTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;
        BlockPos position;

        @Override public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }
        @Override public void onCancelled(int id, String toolName, String reason) {
            this.status = TaskStatus.FAILED;
            this.summary = reason;
        }
    }

    private static CompanionEntity creativeCompanion(GameTestHelper h, ServerPlayer owner) {
        owner.level().getServer().getPlayerList().op(owner.nameAndId(),
                Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CompanionEntity companion = CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(CreativeModeService.requestMode(companion, owner, true) == CreativeModeService.Result.CHANGED,
                "operator owner could not enable creative mode");
        return companion;
    }

    private static Caller caller(ServerPlayer player) {
        return new Caller(player.getUUID(), player.getName().getString(), AccessLevel.OWNER);
    }

    private static ServerPlayer uniquelyNamedOwner(GameTestHelper h) {
        String name = "creative" + UUID.randomUUID().toString().substring(0, 8);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                cookie.gameProfile(), cookie.clientInformation()) {
            @Override public GameType gameMode() { return GameType.CREATIVE; }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        var position = h.absoluteVec(new net.minecraft.world.phys.Vec3(2, 2, 2));
        player.teleportTo(position.x, position.y, position.z);
        return player;
    }

    private static void start(GameTestHelper h, CompanionEntity companion, ServerPlayer owner,
            String command, Result result) {
        companion.taskRunner().setListener(result);
        companion.taskRunner().start(new ExecuteCommandTask(command), "execute_command", caller(owner), true);
    }

    @GameTest
    public void timeCommandUsesOwnerPermissionsAndCapturesFeedback(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        Result result = new Result();
        start(h, companion, owner, "time set 1000", result);
        h.succeedWhen(() -> {
            h.assertTrue(result.status == TaskStatus.SUCCESS, "command status was " + result.status + ": " + result.summary);
            h.assertTrue(h.getLevel().getOverworldClockTime() >= 1_000
                            && h.getLevel().getOverworldClockTime() < 1_100,
                    "time set 1000 produced " + h.getLevel().getOverworldClockTime());
            h.assertTrue(result.summary.startsWith("command succeeded:"), "feedback was " + result.summary);
        });
    }

    @GameTest
    public void tpAtSIsTheCompanionAndGiveCanTargetOnlyItsOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 14, 2, 14);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos target = h.absolutePos(new BlockPos(5, 2, 5));
        Result teleport = new Result();
        start(h, companion, owner, "tp @s " + target.getX() + " " + target.getY() + " " + target.getZ(), teleport);
        h.succeedWhen(() -> {
            h.assertTrue(teleport.status == TaskStatus.SUCCESS,
                    "teleport status was " + teleport.status + ": " + teleport.summary);
            h.assertTrue(companion.blockPosition().distManhattan(target) <= 1,
                    "@s did not teleport the companion: " + companion.blockPosition());
            h.assertTrue(teleport.summary.startsWith("command succeeded:"), "feedback was " + teleport.summary);
        });
    }

    @GameTest
    public void giveCommandDeliversOnlyToExplicitOwnerTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = uniquelyNamedOwner(h);
        CompanionEntity companion = creativeCompanion(h, owner);
        Result result = new Result();
        start(h, companion, owner, "give " + owner.getName().getString() + " minecraft:gold_ingot 3", result);
        h.succeedWhen(() -> {
            h.assertTrue(result.status == TaskStatus.SUCCESS,
                    "give status was " + result.status + ": " + result.summary);
            h.assertTrue(owner.getInventory().countItem(Items.GOLD_INGOT) == 3,
                    "owner received " + owner.getInventory().countItem(Items.GOLD_INGOT)
                            + "; command feedback was " + result.summary);
            h.assertTrue(companion.inventory().countItem(Items.GOLD_INGOT) == 0,
                    "give unexpectedly targeted the companion inventory");
        });
    }

    @GameTest
    public void syntaxAndUnknownRegistryValuesFailWithoutClaimingSuccess(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        TaskContext context = new TaskContext(companion, h.getLevel(), companion.taskRunner(),
                new CreativeActionContext(owner.getUUID(), companion.creativeModeRevision()));
        long timeBefore = h.getLevel().getOverworldClockTime();
        ExecuteCommandTask denied = new ExecuteCommandTask("execute as @s run time set day");
        denied.start(context);
        h.assertTrue(denied.tick(context) == TaskStatus.FAILED, "delegation wrapper was accepted");
        h.assertTrue(h.getLevel().getOverworldClockTime() == timeBefore, "denied wrapper changed time");

        ExecuteCommandTask invalidRegistry = new ExecuteCommandTask("effect give @s friendai:missing 60");
        invalidRegistry.start(context);
        h.assertTrue(invalidRegistry.tick(context) == TaskStatus.FAILED,
                "unknown registry value was accepted: " + invalidRegistry.resultSummary());
        h.assertTrue(!invalidRegistry.resultSummary().startsWith("command succeeded:"),
                "failed command claimed success: " + invalidRegistry.resultSummary());
        h.succeed();
    }

    @GameTest
    public void operatorRevocationStopsQueuedCommandBeforeDispatch(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        TaskContext context = new TaskContext(companion, h.getLevel(), companion.taskRunner(),
                new CreativeActionContext(owner.getUUID(), companion.creativeModeRevision()));
        long timeBefore = h.getLevel().getOverworldClockTime();
        ExecuteCommandTask task = new ExecuteCommandTask("time set day");
        task.start(context);
        owner.level().getServer().getPlayerList().deop(owner.nameAndId());
        h.assertTrue(task.tick(context) == TaskStatus.FAILED, "command ran after operator permission was revoked");
        h.assertTrue(h.getLevel().getOverworldClockTime() == timeBefore, "revoked command changed world time");
        h.assertTrue(task.resultSummary().contains("permission"), "unexpected revocation result: " + task.resultSummary());

        owner.connection.disconnect(Component.literal("test owner offline"));
        h.assertTrue(task.tick(context) == TaskStatus.FAILED, "offline owner retained command access");
        h.succeed();
    }
}
