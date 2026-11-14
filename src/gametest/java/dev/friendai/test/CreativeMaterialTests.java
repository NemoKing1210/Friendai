package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.acquisition.minecraft.ObtainSubtaskFactory;
import dev.friendai.features.acquisition.minecraft.task.BatchObtainTask;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.construction.minecraft.task.BuildTask;
import dev.friendai.features.construction.minecraft.task.PlaceBlockTask;
import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.crafting.minecraft.Stations;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.server.creative.CreativeModeService;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;

public final class CreativeMaterialTests {
    private static CompanionEntity creativeCompanion(GameTestHelper h, ServerPlayer owner) {
        owner.level()
                .getServer()
                .getPlayerList()
                .op(owner.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        h.assertTrue(
                CreativeModeService.requestMode(GameTestSupport.runtime(h), companion, owner, true)
                        == CreativeModeService.Result.CHANGED,
                "operator owner could not enable creative mode");
        return companion;
    }

    private static Caller caller(ServerPlayer player) {
        return new Caller(player.getUUID(), player.getName().getString(), AccessLevel.OWNER);
    }

    private static List<String> firstPlankAlternatives(GameTestHelper h, CompanionEntity companion) {
        CraftPlanner.RawNeed need = CraftPlanner.materialsFor(
                        List.of(new CraftPlanner.Target("minecraft:crafting_table", 1)),
                        Map.of(),
                        RecipeIndex.of(h.getLevel()),
                        Stations.available(h.getLevel(), companion))
                .stream()
                .filter(candidate -> candidate.candidates().size() >= 3)
                .findFirst()
                .orElseThrow(() -> new AssertionError("crafting table demand has fewer than three alternatives"));
        return need.candidates().stream().limit(3).toList();
    }

    private static Block logForPlanks(String plankId) {
        String logId = switch (plankId) {
            case "minecraft:bamboo_planks" -> "minecraft:bamboo_block";
            case "minecraft:crimson_planks" -> "minecraft:crimson_stem";
            case "minecraft:warped_planks" -> "minecraft:warped_stem";
            default -> plankId.replace("_planks", "_log");
        };
        Identifier identifier = Identifier.tryParse(logId);
        return identifier == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(identifier);
    }

    /** Creative wood is taken by the count miner: a felling would raise a scaffold it does not need. */
    @GameTest(maxTicks = 100)
    public void creativeWoodStepUsesTheCountMinerNotTheFeller(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 5, 2, 3);
        CompanionEntity companion = creativeCompanion(h, owner);
        TaskContext context = new TaskContext(companion, h.getLevel());
        MinecraftObtainSubtaskFactory factory = new MinecraftObtainSubtaskFactory();
        ObtainSubtaskFactory.Options options = new ObtainSubtaskFactory.Options(null, null, null, 0, 0, 0);
        Task creative = factory.create(context, new ObtainStep.Mine("minecraft:oak_log", 4, null), options)
                .task();
        h.assertTrue(creative.type().equals("mine_blocks"), "creative wood used " + creative.type());
        companion.setCreativeMode(false);
        Task survival = factory.create(context, new ObtainStep.Mine("minecraft:oak_log", 4, null), options)
                .task();
        h.assertTrue(survival.type().equals("obtain_logs"), "survival wood used " + survival.type());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void creativeBatchDoesNotReserveRecipeIngredients(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 5, 2, 3);
        CompanionEntity companion = creativeCompanion(h, owner);
        BatchObtainTask task = new BatchObtainTask(
                List.of(new CraftPlanner.Target("minecraft:stone_pickaxe", 1)),
                false,
                false,
                new MinecraftObtainSubtaskFactory());
        companion.executionCoordinator().start(task, "obtain_items", caller(owner), true);

        TaskContext context = companion.executionCoordinator().activeTaskContext();
        h.assertTrue(
                context.canUseCreativeAction(CreativeAction.CREATE_ITEM),
                "the batch should hold its live creative authorization");
        Map<String, Integer> needs = task.materialNeeds(context);
        h.assertFalse(
                needs.containsKey("minecraft:cobblestone"), "creative crafting does not need reserved cobblestone");
        h.assertFalse(needs.containsKey("minecraft:stick"), "creative crafting does not need reserved sticks");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void survivalBatchReservesOnlyOneCandidateForAlternativeIngredients(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        BatchObtainTask task = new BatchObtainTask(
                List.of(new CraftPlanner.Target("minecraft:crafting_table", 1)),
                false,
                false,
                new MinecraftObtainSubtaskFactory());
        TaskContext context = new TaskContext(companion, h.getLevel());
        List<CraftPlanner.RawNeed> demand = CraftPlanner.materialsFor(
                List.of(new CraftPlanner.Target("minecraft:crafting_table", 1)),
                Map.of(),
                RecipeIndex.of(h.getLevel()),
                Stations.available(h.getLevel(), companion));
        CraftPlanner.RawNeed alternatives = demand.stream()
                .filter(need -> need.candidates().size() > 1)
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("crafting table demand has no alternative ingredient: " + demand));

        Map<String, Integer> needs = task.materialNeeds(context);
        long reservedCandidates =
                alternatives.candidates().stream().filter(needs::containsKey).count();
        h.assertValueEqual(
                reservedCandidates,
                1L,
                "one interchangeable raw ingredient is reserved instead of every candidate: " + needs);
        String reserved = alternatives.candidates().stream()
                .filter(needs::containsKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no alternative ingredient was reserved: " + needs));
        h.assertValueEqual(
                needs.get(reserved), alternatives.count(), "the selected candidate reserves the full recipe need");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void survivalBatchReservesHeldAndSelectedAlternativeIngredients(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        List<String> alternatives = firstPlankAlternatives(h, companion);
        String selected = alternatives.getFirst();
        String held = alternatives.get(2);
        ItemStack heldStack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.tryParse(held)));
        companion.inventory().addItem(heldStack);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
        BlockPos source = new BlockPos(5, 2, 2);
        Block sourceLog = logForPlanks(selected);
        h.assertFalse(sourceLog == Blocks.AIR, "no log source was mapped for " + selected);
        h.setBlock(source.getX(), source.getY(), source.getZ(), sourceLog);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(source)),
                "the dark oak log should be observed as a plank source");
        BatchObtainTask task = new BatchObtainTask(
                List.of(new CraftPlanner.Target("minecraft:crafting_table", 1)),
                false,
                false,
                new MinecraftObtainSubtaskFactory());

        Map<String, Integer> needs = task.materialNeeds(new TaskContext(companion, h.getLevel()));
        h.assertValueEqual(
                needs.getOrDefault(held, -1), 1, "the carried alternative stays reserved for the recipe: " + needs);
        h.assertValueEqual(
                needs.getOrDefault(selected, -1),
                3,
                "the gatherable alternative covers the remaining recipe demand: " + needs);
        h.assertValueEqual(needs.size(), 3, "only the held alternative, selected alternative, and target are reserved");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void inFlightBatchGatherKeepsItsAlternativeReserved(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        List<String> alternatives = firstPlankAlternatives(h, companion);
        String selected = alternatives.get(1);
        String newlyAvailable = alternatives.getFirst();
        String held = alternatives.get(2);
        companion.inventory().addItem(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.tryParse(held))));
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
        BlockPos darkOak = new BlockPos(5, 2, 2);
        Block initialLog = logForPlanks(selected);
        h.assertFalse(initialLog == Blocks.AIR, "no log source was mapped for " + selected);
        h.setBlock(darkOak.getX(), darkOak.getY(), darkOak.getZ(), initialLog);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(darkOak)),
                "the dark oak log should be observed before the batch starts");
        BatchObtainTask task = new BatchObtainTask(
                List.of(new CraftPlanner.Target("minecraft:crafting_table", 1)),
                false,
                false,
                new MinecraftObtainSubtaskFactory());
        TaskContext context = new TaskContext(companion, h.getLevel());
        task.start(context);
        h.assertTrue(task.tick(context) == TaskStatus.RUNNING, "the batch should start a gather child");
        Map<String, Integer> beforeSourceChange = task.materialNeeds(context);
        h.assertValueEqual(
                beforeSourceChange.getOrDefault(selected, -1),
                3,
                "the in-flight gather candidate is reserved: " + beforeSourceChange);

        BlockPos spruce = new BlockPos(4, 2, 3);
        Block newLog = logForPlanks(newlyAvailable);
        h.assertFalse(newLog == Blocks.AIR, "no log source was mapped for " + newlyAvailable);
        h.setBlock(spruce.getX(), spruce.getY(), spruce.getZ(), newLog);
        h.assertTrue(
                GameTestSupport.observeVisible(h, companion, h.absolutePos(spruce)),
                "the newly available spruce log should be observed");
        Map<String, Integer> afterSourceChange = task.materialNeeds(context);
        h.assertValueEqual(
                afterSourceChange.getOrDefault(selected, -1),
                3,
                "a changed world does not retarget the active gather reservation: " + afterSourceChange);
        h.assertValueEqual(
                afterSourceChange.getOrDefault(held, -1),
                1,
                "the carried alternative remains reserved independently: " + afterSourceChange);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void survivalBatchDoesNotReserveUnusedRecipeBranchMaterials(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        RecipeIndex book = RecipeIndex.of(h.getLevel());
        List<CraftPlanner.Target> targets = List.of(new CraftPlanner.Target("minecraft:suspicious_stew", 1));
        Set<dev.friendai.features.crafting.Station> stations = Stations.available(h.getLevel(), companion);
        List<CraftPlanner.RawNeed> emptyDemand = CraftPlanner.materialsFor(targets, Map.of(), book, stations);
        Set<String> recipeItems = new LinkedHashSet<>();
        book.producing("minecraft:suspicious_stew")
                .forEach(recipe -> recipe.ingredients().forEach(ingredient -> recipeItems.addAll(ingredient.items())));

        String heldAlternative = null;
        List<CraftPlanner.RawNeed> unusedNeeds = List.of();
        for (String item : recipeItems) {
            if (emptyDemand.stream().anyMatch(need -> need.candidates().contains(item))) {
                continue;
            }
            List<CraftPlanner.RawNeed> liveDemand = CraftPlanner.materialsFor(targets, Map.of(item, 1), book, stations);
            List<CraftPlanner.RawNeed> changedBranchNeeds = emptyDemand.stream()
                    .filter(fullNeed -> liveDemand.stream()
                            .noneMatch(liveNeed -> liveNeed.label().equals(fullNeed.label())
                                    && liveNeed.candidates().stream().anyMatch(fullNeed.candidates()::contains)))
                    .filter(fullNeed -> fullNeed.candidates().stream()
                            .noneMatch(candidate -> liveDemand.stream()
                                    .anyMatch(liveNeed -> liveNeed.candidates().contains(candidate))))
                    .toList();
            if (!changedBranchNeeds.isEmpty()) {
                heldAlternative = item;
                unusedNeeds = changedBranchNeeds;
                break;
            }
        }
        h.assertTrue(
                heldAlternative != null,
                "suspicious stew should provide an alternative recipe branch: empty=" + emptyDemand);
        companion
                .inventory()
                .addItem(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.tryParse(heldAlternative))));
        BatchObtainTask task = new BatchObtainTask(targets, false, false, new MinecraftObtainSubtaskFactory());

        Map<String, Integer> needs = task.materialNeeds(new TaskContext(companion, h.getLevel()));
        for (CraftPlanner.RawNeed unusedNeed : unusedNeeds) {
            for (String candidate : unusedNeed.candidates()) {
                h.assertFalse(
                        needs.containsKey(candidate),
                        "an unused recipe branch must not reserve " + candidate + ": " + needs);
            }
        }
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void survivalBatchProtectsShortageAfterPartialLiveOnlyIngredient(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        RecipeIndex book = RecipeIndex.of(h.getLevel());
        Set<dev.friendai.features.crafting.Station> stations = Stations.available(h.getLevel(), companion);
        List<CraftPlanner.Target> targets = List.of(new CraftPlanner.Target("minecraft:white_dye", 2));
        List<CraftPlanner.RawNeed> emptyDemand = CraftPlanner.materialsFor(targets, Map.of(), book, stations);
        String carried = "minecraft:lily_of_the_valley";
        List<CraftPlanner.RawNeed> liveDemand = CraftPlanner.materialsFor(targets, Map.of(carried, 1), book, stations);
        CraftPlanner.RawNeed liveOnly = liveDemand.stream()
                .filter(liveNeed -> emptyDemand.stream()
                        .noneMatch(fullNeed -> fullNeed.label().equals(liveNeed.label())
                                && liveNeed.candidates().stream().anyMatch(fullNeed.candidates()::contains)))
                .filter(need -> need.candidates().contains(carried))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("partial lily stock should select a live-only white dye recipe: empty="
                                + emptyDemand + ", live=" + liveDemand));
        companion.inventory().addItem(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.tryParse(carried))));
        BatchObtainTask task = new BatchObtainTask(targets, false, false, new MinecraftObtainSubtaskFactory());

        Map<String, Integer> needs = task.materialNeeds(new TaskContext(companion, h.getLevel()));
        int reserved = liveOnly.candidates().stream()
                .mapToInt(item -> needs.getOrDefault(item, 0))
                .sum();
        h.assertValueEqual(
                reserved,
                liveOnly.count() + 1,
                "one carried flower is protected in addition to the live branch shortage: " + needs);
        h.succeed();
    }

    @GameTest(maxTicks = 500)
    public void placeBlockGetsFreeMaterialForAuthorizedOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 5, 2, 3);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        PlaceBlockTask task = new PlaceBlockTask((BlockItem) Blocks.MUD_BRICKS.asItem(), target);
        companion.executionCoordinator().start(task, "place_block", caller(owner), true);
        h.succeedWhen(() -> {
            companion.executionCoordinator().tick();
            h.assertTrue(
                    h.getLevel().getBlockState(target).is(Blocks.MUD_BRICKS),
                    "creative placement did not create the requested block");
            h.assertTrue(
                    companion.inventory().countItem(Items.MUD_BRICKS) == 0,
                    "temporary placement material was inserted in the companion inventory");
            h.assertTrue(task.resultSummary().startsWith("placed "), "unexpected result: " + task.resultSummary());
        });
    }

    @GameTest(maxTicks = 700)
    public void buildGetsFreeBlueprintMaterials(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity companion = creativeCompanion(h, owner);
        var blueprint = BuildTests.parse("""
                {"name":"creative_test","palette":{"C":"minecraft:cobblestone"},
                 "ops":[{"op":"box","from":[0,0,0],"to":[0,0,0],"block":"C"}]}
                """);
        BlockPos origin = h.absolutePos(new BlockPos(7, 2, 7));
        BuildTask task = new BuildTask(blueprint, origin, Rotation.NONE, true, false);
        companion.executionCoordinator().start(task, "build_structure", caller(owner), true);
        h.succeedWhen(() -> {
            companion.executionCoordinator().tick();
            h.assertTrue(
                    h.getLevel().getBlockState(origin).is(Blocks.COBBLESTONE),
                    "blueprint did not place its free creative material");
            h.assertTrue(
                    companion.inventory().countItem(Items.COBBLESTONE) == 0,
                    "creative build consumed or retained synthetic inventory material");
            h.assertTrue(task.resultSummary().contains("built"), "unexpected result: " + task.resultSummary());
        });
    }

    @GameTest(maxTicks = 1800)
    public void houseBuildUsesUnlimitedCreativeMaterialsBeyondStackSize(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 7, 2, 7);
        CompanionEntity companion = creativeCompanion(h, owner);
        var blueprint = BuildTests.parse("""
                {"name":"creative_house","palette":{"P":"minecraft:oak_planks"},"ops":[
                  {"op":"box","from":[0,0,0],"to":[4,0,4],"block":"P"},
                  {"op":"box","from":[0,1,0],"to":[4,5,0],"block":"P"},
                  {"op":"box","from":[0,1,4],"to":[4,5,4],"block":"P"},
                  {"op":"box","from":[0,1,1],"to":[0,5,3],"block":"P"},
                  {"op":"box","from":[4,1,1],"to":[4,5,3],"block":"P"},
                  {"op":"box","from":[0,5,0],"to":[4,5,4],"block":"P"}
                ]}
                """);
        h.assertTrue(
                blueprint.blocks().size() > 64,
                "the house should require more than one stack; blocks="
                        + blueprint.blocks().size());
        BlockPos origin = h.absolutePos(new BlockPos(1, 2, 1));
        BuildTask task = new BuildTask(blueprint, origin, Rotation.NONE, true, false);
        final TaskStatus[] status = {null};
        final String[] summary = {""};
        companion.executionCoordinator().setEventSink(new dev.friendai.server.execution.TaskEventSink() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {}
        });
        companion.executionCoordinator().start(task, "build_structure", caller(owner), true);

        h.succeedWhen(() -> {
            for (int x = 0; x < 9; x++) {
                for (int y = 2; y <= 11; y++) {
                    for (int z = 0; z < 9; z++) {
                        var state = h.getBlockState(new BlockPos(x, y, z));
                        h.assertFalse(
                                state.is(Blocks.DIRT) || state.is(Blocks.COBBLESTONE),
                                "creative building placed a scaffold block at " + x + " " + y + " " + z);
                    }
                }
            }
            h.assertTrue(
                    status[0] == TaskStatus.SUCCESS,
                    "creative house did not finish; status=" + status[0] + ", result=" + summary[0]);
            h.assertTrue(summary[0].startsWith("built creative_house"), "unexpected result: " + summary[0]);
            h.assertTrue(
                    companion.inventory().countItem(Items.OAK_PLANKS) == 0,
                    "creative materials must not be inserted into inventory");
            h.assertTrue(h.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.OAK_PLANKS), "house floor was not built");
            h.assertTrue(h.getBlockState(new BlockPos(3, 7, 1)).is(Blocks.OAK_PLANKS), "upper wall was not built");
            h.assertTrue(h.getBlockState(new BlockPos(3, 7, 3)).is(Blocks.OAK_PLANKS), "roof was not built");
        });
    }

    @GameTest(maxTicks = 500)
    public void creativeBuildCanStepVerticallyOutOfOneBlockShaft(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 13, 2, 13);
        CompanionEntity companion = creativeCompanion(h, owner);
        int x = 7;
        int z = 7;
        for (int y = 2; y <= 7; y++) {
            for (int wallX = 4; wallX <= 10; wallX++) {
                for (int wallZ = 4; wallZ <= 10; wallZ++) {
                    if (wallX == x && wallZ == z) continue;
                    h.setBlock(wallX, y, wallZ, Blocks.STONE);
                }
            }
        }
        BlockPos target = h.absolutePos(new BlockPos(x, 2, z));
        companion.snapTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0, 0);
        var blueprint = BuildTests.parse("""
                {"name":"shaft_exit","palette":{"C":"minecraft:cobblestone"},
                 "ops":[{"op":"box","from":[0,0,0],"to":[0,0,0],"block":"C"}]}
                """);
        BuildTask task = new BuildTask(blueprint, target, Rotation.NONE, true, false);
        final TaskStatus[] status = {null};
        final String[] summary = {""};
        companion.executionCoordinator().setEventSink(new dev.friendai.server.execution.TaskEventSink() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {}
        });
        companion.executionCoordinator().start(task, "build_structure", caller(owner), true);
        final double[] highestY = {companion.getY()};

        h.succeedWhen(() -> {
            companion.executionCoordinator().tick();
            highestY[0] = Math.max(highestY[0], companion.getY());
            h.assertTrue(status[0] != null, "creative shaft build did not finish");
            h.assertTrue(status[0] == TaskStatus.SUCCESS, "creative shaft build failed; result=" + summary[0]);
            h.assertTrue(summary[0].startsWith("built shaft_exit"), "unexpected result: " + summary[0]);
            h.assertTrue(
                    highestY[0] >= target.getY() + 1.0,
                    "companion did not use the vertical opening to leave the placement cell; highest Y=" + highestY[0]);
            h.assertFalse(
                    companion.getBoundingBox().intersects(new AABB(target)),
                    "companion still overlaps the block it built in the shaft");
            h.assertTrue(
                    h.getLevel().getBlockState(target).is(Blocks.COBBLESTONE),
                    "shaft block was not placed; task=" + task.resultSummary() + ", status=" + status[0]);
            h.assertFalse(
                    summary[0].contains("scaffold blocks"),
                    "creative shaft build should not use scaffolding: " + summary[0]);
        });
    }

    @GameTest(maxTicks = 800)
    public void tallCreativeBuildFliesWithoutPillaring(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 7, 2, 7);
        CompanionEntity companion = creativeCompanion(h, owner);
        for (int x = 3; x <= 5; x++) {
            for (int y = 7; y <= 14; y++) {
                for (int z = 3; z <= 5; z++) h.setBlock(x, y, z, Blocks.AIR);
            }
        }
        var blueprint = BuildTests.parse("""
                {"name":"creative_tower","palette":{"C":"minecraft:cobblestone"},
                 "ops":[{"op":"box","from":[0,0,0],"to":[0,8,0],"block":"C"}]}
                """);
        BlockPos origin = h.absolutePos(new BlockPos(4, 2, 4));
        BuildTask task = new BuildTask(blueprint, origin, Rotation.NONE, true, false);
        final TaskStatus[] status = {null};
        final String[] summary = {""};
        companion.executionCoordinator().setEventSink(new dev.friendai.server.execution.TaskEventSink() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus taskStatus, String result) {
                status[0] = taskStatus;
                summary[0] = result;
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {}
        });
        companion.executionCoordinator().start(task, "build_structure", caller(owner), true);

        h.succeedWhen(() -> {
            for (int x = 0; x < 9; x++) {
                for (int y = 2; y <= 10; y++) {
                    for (int z = 0; z < 9; z++) {
                        h.assertFalse(
                                h.getBlockState(new BlockPos(x, y, z)).is(Blocks.DIRT),
                                "creative tower used a dirt access pillar at " + x + " " + y + " " + z);
                    }
                }
            }
            h.assertTrue(
                    status[0] == TaskStatus.SUCCESS,
                    "creative tower did not finish; status=" + status[0] + ", result=" + summary[0]);
            h.assertTrue(summary[0].startsWith("built creative_tower"), "unexpected result: " + summary[0]);
            h.assertFalse(
                    summary[0].contains("scaffold blocks"),
                    "creative tower should not need construction scaffolding: " + summary[0]);
            for (int y = 2; y <= 10; y++) {
                h.assertTrue(
                        h.getBlockState(new BlockPos(4, y, 4)).is(Blocks.COBBLESTONE), "tower block missing at y=" + y);
            }
        });
    }

    @GameTest
    public void placeBlockDoesNotGetFreeMaterialWithoutAnAuthorizedContext(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 5, 2, 3);
        CompanionEntity companion = creativeCompanion(h, owner);
        BlockPos target = h.absolutePos(new BlockPos(8, 2, 3));
        PlaceBlockTask task = new PlaceBlockTask((BlockItem) Blocks.MUD_BRICKS.asItem(), target);
        task.start(new dev.friendai.task.TaskContext(companion, h.getLevel()));
        TaskStatus status = task.tick(new dev.friendai.task.TaskContext(companion, h.getLevel()));
        h.assertTrue(status == TaskStatus.FAILED, "unauthorized task status was " + status);
        h.assertTrue(h.getLevel().getBlockState(target).isAir(), "unauthorized task placed a block");
        h.assertTrue(task.resultSummary().contains("I don't have"), "unexpected result: " + task.resultSummary());
        h.succeed();
    }

    @GameTest
    public void obtainCreatesMissingRegisteredItemForCreativeOwner(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = creativeCompanion(h, owner);
        ObtainTask task = new ObtainTask("minecraft:diamond", 130, false, false);
        companion.executionCoordinator().start(task, "obtain_item", caller(owner), true);
        h.succeedWhen(() -> {
            companion.executionCoordinator().tick();
            h.assertTrue(
                    companion.inventory().countItem(Items.DIAMOND) == 130,
                    "obtain delivered " + companion.inventory().countItem(Items.DIAMOND));
            h.assertTrue(task.resultSummary().contains("got 130"), "unexpected result: " + task.resultSummary());
        });
    }
}
