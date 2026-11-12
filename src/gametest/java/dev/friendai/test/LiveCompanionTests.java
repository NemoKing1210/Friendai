package dev.friendai.test;

import com.google.gson.Gson;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.debug.DebugService;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.llm.HttpStreamingTransport;
import dev.friendai.llm.LlmClient;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.testutil.RecordingSpeaker;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;

/**
 * A battery of ordinary player requests against the real brain, tools and configured provider. Each scenario builds
 * its own small world, speaks one order the way a player would, and judges the result in the world. Every scenario
 * runs in an environment of its own, so the batches run one after another with one companion at a time, as in a
 * singleplayer game. It runs only when {@code FRIENDAI_LIVE_CONFIG} names a server config; otherwise every scenario
 * passes at once. A scenario never fails the run: it prints a {@code [RESULT]} line and succeeds, so one slow request
 * cannot hide the others.
 */
public class LiveCompanionTests {
    private static final int SIZE = 24;
    private static final long SECOND = 1_000_000_000L;
    private static final int MAX_TICKS = 900_000;

    record Verdict(boolean ok, String detail) {}

    enum Mode {
        /** Passes as soon as the world shows the result. */
        EARLY,
        /** Judged once the companion has answered and been quiet for the settle time. */
        SETTLE
    }

    /** Everything a scenario's setup, reaction and check may touch. */
    static final class Live {
        final GameTestHelper h;
        final ServerPlayer owner;
        final CompanionEntity companion;
        final RecordingSpeaker heard;
        final Set<String> flags = new HashSet<>();
        BlockPos companionAtReply;
        Object subject;
        int baseline;

        Live(GameTestHelper h, ServerPlayer owner, CompanionEntity companion, RecordingSpeaker heard) {
            this.h = h;
            this.owner = owner;
            this.companion = companion;
            this.heard = heard;
        }

        int carried(Item item) {
            int count = companion.inventory().countItem(item);
            if (companion.getMainHandItem().is(item))
                count += companion.getMainHandItem().getCount();
            if (companion.getOffhandItem().is(item))
                count += companion.getOffhandItem().getCount();
            return count;
        }

        int blocks(Block block, int fromY, int toY) {
            int count = 0;
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = fromY; y <= toY; y++) {
                        if (h.getBlockState(new BlockPos(x, y, z)).is(block)) count++;
                    }
                }
            }
            return count;
        }

        double ownerDistance() {
            return companion.position().distanceTo(owner.position());
        }

        String spoken() {
            return String.join(" | ", heard.said);
        }

        void moveOwner(double x, double y, double z) {
            Vec3 to = h.absoluteVec(new Vec3(x + 0.5, y, z + 0.5));
            owner.teleportTo(to.x, to.y, to.z);
            flags.add("moved");
        }

        void give(Item item, int count) {
            companion.inventory().addItem(new ItemStack(item, count));
        }

        void hold(Item item) {
            companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        }

        String inventory() {
            StringBuilder text = new StringBuilder();
            text.append("hand=").append(companion.getMainHandItem());
            for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
                ItemStack stack = companion.inventory().getItem(slot);
                if (!stack.isEmpty()) text.append(", ").append(stack);
            }
            return text.toString();
        }
    }

    // ---- movement ----------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_01")
    public void follow(GameTestHelper h) throws Exception {
        scenario(
                h,
                "01 follow me",
                90,
                Mode.EARLY,
                0,
                live -> {},
                "Иди за мной.",
                live -> live.moveOwner(18, 2, 18),
                live -> new Verdict(
                        live.flags.contains("moved") && live.ownerDistance() <= 4,
                        "distance to owner " + round(live.ownerDistance())));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_02")
    public void stay(GameTestHelper h) throws Exception {
        scenario(
                h,
                "02 stay here",
                120,
                Mode.SETTLE,
                25,
                live -> {},
                "Стой здесь и никуда не уходи, пока я не вернусь.",
                live -> live.moveOwner(18, 2, 18),
                live -> {
                    double moved = live.companionAtReply == null
                            ? -1
                            : Math.sqrt(live.companion.blockPosition().distSqr(live.companionAtReply));
                    return new Verdict(
                            live.companionAtReply != null && moved <= 3,
                            "moved " + round(moved) + " blocks after the reply; owner " + round(live.ownerDistance())
                                    + " away");
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_03")
    public void comeHere(GameTestHelper h) throws Exception {
        scenario(
                h,
                "03 come to me",
                90,
                Mode.EARLY,
                0,
                live -> {
                    GameTestSupport.runtime(h.getLevel().getServer())
                            .sessions()
                            .runTool(live.owner.getUUID(), "stay", "{}");
                    live.moveOwner(18, 2, 18);
                },
                "Подойди ко мне.",
                null,
                live -> new Verdict(live.ownerDistance() <= 3.5, "distance to owner " + round(live.ownerDistance())));
    }

    // ---- gathering ---------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_04")
    public void chopWood(GameTestHelper h) throws Exception {
        scenario(
                h,
                "04 chop some wood",
                150,
                Mode.EARLY,
                0,
                live -> tree(h, 12, 12),
                "Наруби немного дерева.",
                null,
                live -> new Verdict(
                        live.carried(Items.OAK_LOG) >= 3, "oak logs carried " + live.carried(Items.OAK_LOG)));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_05")
    public void deadBushes(GameTestHelper h) throws Exception {
        BlockPos[] bushes = {
            new BlockPos(7, 2, 5),
            new BlockPos(9, 2, 8),
            new BlockPos(6, 2, 10),
            new BlockPos(11, 2, 4),
            new BlockPos(12, 2, 10)
        };
        scenario(
                h,
                "05 take the bushes we stand on",
                120,
                Mode.EARLY,
                0,
                live -> {
                    floor(h, Blocks.RED_SAND, 0, SIZE - 1);
                    for (BlockPos bush : bushes) h.setBlock(bush, Blocks.DEAD_BUSH);
                },
                "Забери пару кустов, на которых мы стоим.",
                null,
                live -> {
                    int broken = 0;
                    for (BlockPos bush : bushes) {
                        if (!h.getBlockState(bush).is(Blocks.DEAD_BUSH)) broken++;
                    }
                    return new Verdict(broken >= 2, "bushes broken " + broken + " of " + bushes.length);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_06")
    public void digDirt(GameTestHelper h) throws Exception {
        scenario(
                h,
                "06 dig some dirt",
                150,
                Mode.EARLY,
                0,
                live -> {
                    for (int x = 6; x < SIZE; x++) {
                        for (int z = 6; z < SIZE; z++) {
                            h.setBlock(x, 1, z, Blocks.DIRT);
                            h.setBlock(x, 0, z, Blocks.DIRT);
                        }
                    }
                },
                "Накопай земли, блоков пять.",
                null,
                live -> new Verdict(live.carried(Items.DIRT) >= 5, "dirt carried " + live.carried(Items.DIRT)));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_07")
    public void pickFlowers(GameTestHelper h) throws Exception {
        BlockPos[] flowers = {
            new BlockPos(9, 2, 9), new BlockPos(12, 2, 8), new BlockPos(8, 2, 13), new BlockPos(14, 2, 12)
        };
        scenario(
                h,
                "07 pick the flowers",
                150,
                Mode.EARLY,
                0,
                live -> {
                    for (int x = 6; x <= 16; x++) {
                        for (int z = 6; z <= 16; z++) h.setBlock(x, 1, z, Blocks.GRASS_BLOCK);
                    }
                    for (BlockPos flower : flowers) h.setBlock(flower, Blocks.POPPY);
                },
                "Сорви цветы.",
                null,
                live -> {
                    int left = 0;
                    for (BlockPos flower : flowers) {
                        if (h.getBlockState(flower).is(Blocks.POPPY)) left++;
                    }
                    return new Verdict(
                            flowers.length - left >= 2,
                            "flowers picked " + (flowers.length - left) + ", poppies carried "
                                    + live.carried(Items.POPPY));
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_08")
    public void harvestWheat(GameTestHelper h) throws Exception {
        scenario(
                h,
                "08 harvest the wheat",
                150,
                Mode.EARLY,
                0,
                live -> {
                    for (int x = 9; x <= 11; x++) {
                        for (int z = 9; z <= 11; z++) {
                            if (x == 10 && z == 10) {
                                h.setBlock(x, 1, z, Blocks.WATER);
                            } else {
                                h.setBlock(x, 1, z, Blocks.FARMLAND);
                                h.setBlock(
                                        x,
                                        2,
                                        z,
                                        Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
                            }
                        }
                    }
                },
                "Собери пшеницу.",
                null,
                live -> new Verdict(live.carried(Items.WHEAT) >= 3, "wheat carried " + live.carried(Items.WHEAT)));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_09")
    public void pickUpApples(GameTestHelper h) throws Exception {
        double[][] spots = {{9, 2, 6}, {11, 2, 9}, {7, 2, 11}, {13, 2, 5}, {10, 2, 12}};
        scenario(
                h,
                "09 pick up the apples",
                120,
                Mode.EARLY,
                0,
                live -> {
                    for (double[] spot : spots) {
                        Vec3 at = h.absoluteVec(new Vec3(spot[0] + 0.5, spot[1], spot[2] + 0.5));
                        ItemEntity apple = new ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.APPLE));
                        apple.setDeltaMovement(Vec3.ZERO);
                        apple.setUnlimitedLifetime();
                        h.getLevel().addFreshEntity(apple);
                    }
                },
                "Подбери яблоки с земли.",
                null,
                live -> new Verdict(live.carried(Items.APPLE) >= 4, "apples carried " + live.carried(Items.APPLE)));
    }

    // ---- mining ------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_10")
    public void cobbleWithPickaxe(GameTestHelper h) throws Exception {
        scenario(
                h,
                "10 get 3 cobblestone (has a pickaxe)",
                180,
                Mode.EARLY,
                0,
                live -> {
                    outcrop(h);
                    live.hold(Items.WOODEN_PICKAXE);
                },
                "Добудь три булыжника.",
                null,
                live -> new Verdict(
                        live.carried(Items.COBBLESTONE) >= 3,
                        "cobblestone carried " + live.carried(Items.COBBLESTONE)));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_11")
    public void stoneWithoutPickaxe(GameTestHelper h) throws Exception {
        scenario(
                h,
                "11 get stone (no pickaxe, a tree nearby)",
                240,
                Mode.EARLY,
                0,
                live -> {
                    outcrop(h);
                    tree(h, 8, 16);
                },
                "Добудь немного камня.",
                null,
                live -> new Verdict(
                        live.carried(Items.COBBLESTONE) >= 1,
                        "cobblestone carried " + live.carried(Items.COBBLESTONE) + "; " + live.inventory()));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_12")
    public void visibleIron(GameTestHelper h) throws Exception {
        scenario(
                h,
                "12 mine the visible iron ore",
                180,
                Mode.EARLY,
                0,
                live -> {
                    for (int x = 14; x <= 16; x++) {
                        for (int y = 2; y <= 4; y++) {
                            for (int z = 6; z <= 12; z++) h.setBlock(x, y, z, Blocks.STONE);
                        }
                    }
                    h.setBlock(14, 3, 9, Blocks.IRON_ORE);
                    live.hold(Items.STONE_PICKAXE);
                },
                "Добудь железную руду.",
                null,
                live -> new Verdict(
                        live.carried(Items.RAW_IRON) + live.carried(Items.IRON_ORE) >= 1,
                        "raw iron carried " + live.carried(Items.RAW_IRON)));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_13")
    public void digAPit(GameTestHelper h) throws Exception {
        scenario(
                h,
                "13 dig a 3x3 pit, 2 deep",
                240,
                Mode.EARLY,
                0,
                live -> {
                    for (int x = 0; x < SIZE; x++) {
                        for (int z = 0; z < SIZE; z++) {
                            for (int y = -1; y <= 1; y++) h.setBlock(x, y, z, Blocks.DIRT);
                        }
                    }
                    live.baseline = live.blocks(Blocks.DIRT, -1, 1);
                },
                "Выкопай яму три на три, глубиной два блока.",
                null,
                live -> {
                    int removed = live.baseline - live.blocks(Blocks.DIRT, -1, 1);
                    return new Verdict(removed >= 12, "dirt blocks removed " + removed);
                });
    }

    // ---- crafting ----------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_14")
    public void craftPlanks(GameTestHelper h) throws Exception {
        scenario(
                h,
                "14 make planks from logs",
                120,
                Mode.EARLY,
                0,
                live -> live.give(Items.OAK_LOG, 2),
                "Сделай доски из брёвен.",
                null,
                live -> new Verdict(
                        live.carried(Items.OAK_PLANKS) >= 4, "oak planks carried " + live.carried(Items.OAK_PLANKS)));
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_15")
    public void craftTable(GameTestHelper h) throws Exception {
        scenario(
                h,
                "15 craft a crafting table",
                120,
                Mode.EARLY,
                0,
                live -> live.give(Items.OAK_PLANKS, 4),
                "Скрафти верстак.",
                null,
                live -> {
                    int made = live.carried(Items.CRAFTING_TABLE) + live.blocks(Blocks.CRAFTING_TABLE, 2, 8);
                    return new Verdict(made >= 1, "crafting tables " + made + "; " + live.inventory());
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_16")
    public void woodenPickaxeFromScratch(GameTestHelper h) throws Exception {
        scenario(
                h,
                "16 make a wooden pickaxe from scratch",
                240,
                Mode.EARLY,
                0,
                live -> {
                    tree(h, 12, 12);
                    tree(h, 17, 6);
                },
                "Сделай себе деревянную кирку.",
                null,
                live -> new Verdict(live.carried(Items.WOODEN_PICKAXE) >= 1, live.inventory()));
    }

    // ---- inventory ---------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_17")
    public void inventoryQuestion(GameTestHelper h) throws Exception {
        scenario(
                h,
                "17 what is in your inventory",
                90,
                Mode.SETTLE,
                6,
                live -> {
                    live.give(Items.BREAD, 3);
                    live.give(Items.COBBLESTONE, 7);
                    live.give(Items.TORCH, 2);
                },
                "Что у тебя в инвентаре?",
                null,
                live -> {
                    String said = live.spoken().toLowerCase(Locale.ROOT);
                    boolean bread = said.contains("хлеб");
                    boolean stone = said.contains("булыжн")
                            || said.contains("камн")
                            || said.contains("камен")
                            || said.contains("коббл")
                            || said.contains("cobble");
                    boolean torches = said.contains("факел");
                    return new Verdict(
                            bread && stone && torches, "bread " + bread + ", stone " + stone + ", torches " + torches);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_18")
    public void giveBread(GameTestHelper h) throws Exception {
        scenario(
                h,
                "18 give me some bread",
                120,
                Mode.EARLY,
                0,
                live -> live.give(Items.BREAD, 4),
                "Дай мне пару хлебов.",
                null,
                live -> {
                    int owner = live.owner.getInventory().countItem(Items.BREAD);
                    int left = live.carried(Items.BREAD);
                    return new Verdict(owner >= 1 || left <= 2, "owner has " + owner + ", companion kept " + left);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_19")
    public void storeInChest(GameTestHelper h) throws Exception {
        BlockPos chest = new BlockPos(8, 2, 4);
        scenario(
                h,
                "19 put the cobblestone in the chest",
                120,
                Mode.EARLY,
                0,
                live -> {
                    h.setBlock(chest, Blocks.CHEST);
                    live.give(Items.COBBLESTONE, 10);
                },
                "Положи весь булыжник в сундук.",
                null,
                live -> {
                    int stored = 0;
                    if (h.getLevel().getBlockEntity(h.absolutePos(chest)) instanceof Container container) {
                        for (int slot = 0; slot < container.getContainerSize(); slot++) {
                            ItemStack stack = container.getItem(slot);
                            if (stack.is(Items.COBBLESTONE)) stored += stack.getCount();
                        }
                    }
                    return new Verdict(stored >= 5, "cobblestone in chest " + stored);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_20")
    public void eat(GameTestHelper h) throws Exception {
        scenario(
                h,
                "20 eat something",
                90,
                Mode.EARLY,
                0,
                live -> {
                    live.companion.hunger().load(8, 0.0f, 0.0f);
                    live.give(Items.BREAD, 3);
                },
                "Поешь, ты наверное голодный.",
                null,
                live -> new Verdict(
                        live.companion.hunger().food() > 8 || live.carried(Items.BREAD) < 3,
                        "food " + live.companion.hunger().food() + ", bread " + live.carried(Items.BREAD)));
    }

    // ---- building and combat -----------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_21")
    public void placeTorches(GameTestHelper h) throws Exception {
        scenario(
                h,
                "21 place a couple of torches",
                120,
                Mode.EARLY,
                0,
                live -> live.give(Items.TORCH, 8),
                "Поставь пару факелов рядом с нами.",
                null,
                live -> {
                    int placed = live.blocks(Blocks.TORCH, 2, 8) + live.blocks(Blocks.WALL_TORCH, 2, 8);
                    return new Verdict(placed >= 2, "torches placed " + placed);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_22")
    public void smallHouse(GameTestHelper h) throws Exception {
        scenario(
                h,
                "22 build a small plank house",
                300,
                Mode.EARLY,
                0,
                live -> {
                    live.give(Items.OAK_PLANKS, 64);
                    live.give(Items.OAK_PLANKS, 64);
                },
                "Построй маленький домик из досок.",
                null,
                live -> {
                    int placed = live.blocks(Blocks.OAK_PLANKS, 2, 8);
                    return new Verdict(placed >= 30, "planks placed " + placed);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_23")
    public void killZombie(GameTestHelper h) throws Exception {
        scenario(
                h,
                "23 kill that zombie",
                90,
                Mode.EARLY,
                0,
                live -> {
                    live.hold(Items.IRON_SWORD);
                    live.subject = h.spawn(EntityTypes.HUSK, 16, 2, 16);
                },
                "Убей того зомби.",
                null,
                live -> {
                    var husk = (net.minecraft.world.entity.LivingEntity) live.subject;
                    return new Verdict(!husk.isAlive(), "husk health " + husk.getHealth());
                });
    }

    // ---- honesty -----------------------------------------------------------------------------------------------

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_24")
    public void diamondsNearby(GameTestHelper h) throws Exception {
        scenario(
                h,
                "24 are there diamonds nearby (none exist)",
                90,
                Mode.SETTLE,
                6,
                live -> {},
                "Тут где-нибудь рядом есть алмазы?",
                null,
                live -> {
                    String said = live.spoken().toLowerCase(Locale.ROOT);
                    boolean denies = said.contains("не виж")
                            || said.contains("не вид")
                            || said.contains("нет")
                            || said.contains("не знаю")
                            || said.contains("не замеч");
                    return new Verdict(denies, "denies or admits not knowing: " + denies);
                });
    }

    @GameTest(maxTicks = MAX_TICKS, skyAccess = true, environment = "friendai-gametest:live_25")
    public void cactusThenHandItOver(GameTestHelper h) throws Exception {
        BlockPos base = new BlockPos(12, 2, 9);
        scenario(
                h,
                "25 break the cactus near us and give it to me",
                180,
                Mode.EARLY,
                0,
                live -> {
                    floor(h, Blocks.SAND, 0, SIZE - 1);
                    h.setBlock(base, Blocks.CACTUS);
                    h.setBlock(base.above(), Blocks.CACTUS);
                },
                "Сломай вот этот кактус рядом с нами и отдай его мне.",
                null,
                live -> {
                    boolean broken = !h.getBlockState(base).is(Blocks.CACTUS);
                    int owned = live.owner.getInventory().countItem(Items.CACTUS);
                    int nearOwner = h.getLevel()
                            .getEntitiesOfClass(
                                    ItemEntity.class,
                                    live.owner.getBoundingBox().inflate(4),
                                    item -> item.getItem().is(Items.CACTUS))
                            .size();
                    return new Verdict(
                            broken && (owned > 0 || nearOwner > 0),
                            "broken " + broken + ", owner has " + owned + ", dropped by the owner " + nearOwner
                                    + ", companion carries " + live.carried(Items.CACTUS));
                });
    }

    // ---- runner ------------------------------------------------------------------------------------------------

    static void scenario(
            GameTestHelper h,
            String name,
            int budgetSeconds,
            Mode mode,
            int settleSeconds,
            Consumer<Live> setup,
            String order,
            Consumer<Live> afterReply,
            Function<Live, Verdict> check)
            throws Exception {
        String configPath = System.getenv("FRIENDAI_LIVE_CONFIG");
        if (configPath == null || configPath.isBlank()) {
            h.succeed();
            return;
        }
        GameTestSupport.platform(h, SIZE);
        MinecraftServer server = h.getLevel().getServer();
        command(server, "time set noon");
        command(server, "weather clear");
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var runtime = GameTestSupport.runtime(server);
        runtime.applyLoggingLevel("detailed");
        var sessions = runtime.sessions();
        RecordingSpeaker heard = new RecordingSpeaker();
        sessions.setGatewayOverride(owner.getUUID(), gateway(configPath));
        sessions.setSpeakerWrapper(owner.getUUID(), real -> heard);
        CompanionEntity companion = CompanionManager.get(server).summon(owner);
        Live live = new Live(h, owner, companion, heard);
        setup.accept(live);
        System.out.println("[SCEN] " + name + " owner=" + owner.getUUID() + " order=" + order);
        long started = System.nanoTime();
        long deadline = started + budgetSeconds * SECOND;
        long[] firstReply = {0};
        long[] satisfied = {0};
        Verdict[] reached = {null};
        int[] tick = {0};
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (done[0]) return;
            tick[0]++;
            long now = System.nanoTime();
            if (tick[0] % 3000 == 0) command(server, "time set noon");
            if (tick[0] == 60) TestMenuRequests.voice(owner, order);
            if (tick[0] < 60) return;
            // A turn that only calls a tool says nothing; its end counts as the reply all the same.
            boolean turnEnded =
                    heard.thinkingLog.contains(Boolean.TRUE) && !heard.thinkingLog.get(heard.thinkingLog.size() - 1);
            if (firstReply[0] == 0 && (!heard.said.isEmpty() || turnEnded)) {
                firstReply[0] = now;
                live.companionAtReply = companion.blockPosition();
                if (afterReply != null) afterReply.accept(live);
            }
            Verdict verdict;
            try {
                verdict = check.apply(live);
            } catch (RuntimeException e) {
                verdict = new Verdict(false, "check threw " + e);
            }
            boolean idle = firstReply[0] != 0
                    && (heard.thinkingLog.isEmpty() || !heard.thinkingLog.get(heard.thinkingLog.size() - 1));
            Verdict result = null;
            if (mode == Mode.EARLY) {
                if (verdict.ok() && satisfied[0] == 0) {
                    satisfied[0] = now;
                    reached[0] = verdict;
                }
                // The world showed the result once; a companion that then walks back to its owner, as it should, does
                // not undo it.
                if (satisfied[0] != 0 && (idle || now - satisfied[0] > 15 * SECOND))
                    result = verdict.ok()
                            ? verdict
                            : new Verdict(true, reached[0].detail() + "; later " + verdict.detail());
                else if (now > deadline) result = new Verdict(false, verdict.detail());
            } else if ((idle && now - firstReply[0] >= settleSeconds * SECOND) || now > deadline) {
                result = verdict;
            }
            if (result == null) return;
            done[0] = true;
            System.out.println("[RESULT] " + (result.ok() ? "PASS" : "FAIL") + " | " + name + " | "
                    + round((now - started) / (double) SECOND) + " s | " + result.detail() + " | owner="
                    + owner.getUUID() + " | said: " + live.spoken().replace('\n', ' '));
            if (!result.ok()) trace(sessions.debug().logOf(owner.getUUID()));
            // One companion at a time, as in a singleplayer game: a companion left behind would share the next
            // scenario's sight budget and slow its perception down.
            CompanionManager.get(server).dismiss(owner);
            h.succeed();
        });
    }

    /**
     * Prints what the planner answered and which tools ran, so a failed scenario shows the plan body and the tool
     * results that the durable owner log keeps out of its files.
     */
    private static void trace(DebugLog log) {
        var kinds = EnumSet.of(LogKind.PLANNER, LogKind.TOOL_CALL, LogKind.TOOL_RESULT, LogKind.TASK, LogKind.ERROR);
        int printed = 0;
        for (var entry : log.query(new DebugLog.Query(kinds, 0, 0, DebugService.LOG_CAPACITY, false))) {
            String text = entry.text().replace('\n', ' ');
            // Periodic task samples would drown the few lines that explain the run.
            if (entry.kind() == LogKind.TASK && (text.contains(": progress") || text.contains(": waiting"))) continue;
            if (++printed > 80) break;
            System.out.println("[TRACE] " + entry.kind() + " " + text);
        }
    }

    private static LlmGateway gateway(String configPath) throws Exception {
        ServerConfig config =
                new Gson().fromJson(Files.readString(Path.of(configPath), StandardCharsets.UTF_8), ServerConfig.class);
        config.validate();
        HttpClient http = HttpClient.newHttpClient();
        HttpTransport transport =
                new HttpTransport(http, Duration.ofSeconds(config.brain.requestTimeoutSec), 2, Duration.ofMillis(500));
        return new LlmClient(
                ProviderRegistry.withBuiltins(transport),
                config.toLlmProfile(System::getenv),
                new HttpStreamingTransport(http));
    }

    private static void command(MinecraftServer server, String command) {
        server.getCommands()
                .performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** Replaces the floor at y=1 over the square [from, to], on stone so sand or gravel there cannot fall. */
    static void floor(GameTestHelper h, Block block, int from, int to) {
        for (int x = from; x <= to; x++) {
            for (int z = from; z <= to; z++) {
                h.setBlock(x, 0, z, Blocks.STONE);
                h.setBlock(x, 1, z, block);
            }
        }
    }

    /** A small oak: a five-log trunk and persistent leaves. */
    static void tree(GameTestHelper h, int x, int z) {
        var leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int y = 5; y <= 7; y++) {
            int r = y == 7 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) h.setBlock(x + dx, y, z + dz, leaves);
            }
        }
        for (int y = 2; y <= 6; y++) h.setBlock(x, y, z, Blocks.OAK_LOG);
    }

    /** A 3x3x3 block of stone in plain sight. */
    static void outcrop(GameTestHelper h) {
        for (int x = 14; x <= 16; x++) {
            for (int y = 2; y <= 4; y++) {
                for (int z = 10; z <= 12; z++) h.setBlock(x, y, z, Blocks.STONE);
            }
        }
    }

    static String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
