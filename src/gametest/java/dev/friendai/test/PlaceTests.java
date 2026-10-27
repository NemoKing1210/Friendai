package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceService;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolExecutor;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import dev.friendai.llm.model.ToolCall;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class PlaceTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            this.summary = "cancelled: " + reason;
        }
    }

    private static ToolResult runAs(ToolExecutor tools, String name, String json, Caller caller) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("1", name, args, json, null), caller);
    }

    private static ToolExecutor tools(CompanionEntity companion) {
        return FriendaiTools.create(() -> 32).bind(companion);
    }

    @GameTest
    public void place_tests_access(GameTestHelper h) {
        GameTestSupport.platform(h, 15);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 3, 2, 3);
        companion.setOwner(owner.getUUID());
        ToolExecutor tools = tools(companion);
        Caller guest = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.GUEST);
        Caller trusted = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED);
        Caller ownerCaller = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.OWNER);
        String remember = "{\"name\":\"home\",\"kind\":\"house\"}";

        ToolResult refused = runAs(tools, "remember_place", remember, guest);
        h.assertTrue(refused.error() && refused.content().contains("not available"),
                "a guest must not remember places: " + refused.content());

        ToolResult remembered = runAs(tools, "remember_place", remember, trusted);
        h.assertTrue(!remembered.error(), "a trusted speaker should remember: " + remembered.content());
        h.assertValueEqual(PlaceService.book(companion).all().size(), 1, "places after remember");

        ToolResult trustedForget = runAs(tools, "forget_place", "{\"name\":\"home\"}", trusted);
        h.assertTrue(trustedForget.error() && trustedForget.content().contains("not available"),
                "a trusted speaker must not forget places: " + trustedForget.content());
        h.assertValueEqual(PlaceService.book(companion).all().size(), 1, "places after refused forget");

        ToolResult forgotten = runAs(tools, "forget_place", "{\"name\":\"home\"}", ownerCaller);
        h.assertTrue(!forgotten.error(), "the owner should forget: " + forgotten.content());
        h.assertTrue(PlaceService.book(companion).all().isEmpty(),
                "the place should be gone: " + PlaceService.book(companion).all());
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void place_tests_remember_and_go(GameTestHelper h) {
        GameTestSupport.platform(h, 20);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 12, 2, 2);
        companion.setOwner(owner.getUUID());
        Result result = new Result();
        companion.taskRunner().setListener(result);
        ToolExecutor tools = tools(companion);
        Caller caller = new Caller(owner.getUUID(), owner.getName().getString(), AccessLevel.TRUSTED);

        ToolResult remembered = runAs(tools, "remember_place",
                "{\"name\":\"home\",\"kind\":\"house\",\"radius\":8}", caller);
        h.assertTrue(!remembered.error(), "remember failed: " + remembered.content());
        List<Place> places = PlaceService.book(companion).all();
        h.assertValueEqual(places.size(), 1, "places after remember");
        Place place = places.get(0);
        h.assertValueEqual(place.name(), "home", "stored name");
        h.assertValueEqual(place.anchor().x(), owner.blockPosition().getX(), "anchor x follows the speaker");
        h.assertValueEqual(place.anchor().z(), owner.blockPosition().getZ(), "anchor z follows the speaker");

        ToolResult go = runAs(tools, "go_to_place", "{\"name\":\"home\"}", caller);
        h.assertTrue(!go.error(), "go failed: " + go.content());
        h.assertTrue(companion.taskRunner().current()
                        .map(task -> task.type().equals("go_to_place") && task.describe().equals("walking to home"))
                        .orElse(false),
                "the go_to_place task should be current");

        double successRadius = Math.max(2, Math.min(place.radius(), 8));
        h.succeedWhen(() -> {
            companion.taskRunner().tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "status " + result.status + " " + result.summary);
            Place visited = PlaceService.book(companion).byId(place.id()).orElseThrow();
            h.assertValueEqual(visited.visits(), 1, "visits");
            double horizontal = Math.hypot(
                    companion.getX() - (visited.anchor().x() + 0.5),
                    companion.getZ() - (visited.anchor().z() + 0.5));
            h.assertTrue(horizontal <= successRadius,
                    "arrived " + horizontal + " blocks away, allowed " + successRadius);
        });
    }
}
