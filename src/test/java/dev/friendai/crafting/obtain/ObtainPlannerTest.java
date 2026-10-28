package dev.friendai.crafting.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.crafting.FakeRecipeBook;
import dev.friendai.crafting.Ingredient;
import dev.friendai.crafting.Recipe;
import dev.friendai.crafting.Station;
import dev.friendai.pathing.Pos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ObtainPlannerTest {
    private static final String OAK_LOG = "minecraft:oak_log";
    private static final String ACACIA_LOG = "minecraft:acacia_log";
    private static final String STONE = "minecraft:stone";
    private static final String IRON_ORE = "minecraft:iron_ore";
    private static final String COAL_ORE = "minecraft:coal_ore";
    private static final String RAW_IRON = "minecraft:raw_iron";
    private static final String IRON_INGOT = "minecraft:iron_ingot";
    private static final String STICK = "minecraft:stick";
    private static final String STONE_PICKAXE = "minecraft:stone_pickaxe";
    private static final String WOODEN_PICKAXE = "minecraft:wooden_pickaxe";
    private static final String IRON_PICKAXE = "minecraft:iron_pickaxe";

    private static FakeRecipeBook book() {
        Ingredient cobblestone = Ingredient.of("minecraft:cobblestone");
        Ingredient stick = Ingredient.of(STICK);
        Ingredient ironIngot = Ingredient.of(IRON_INGOT);
        Ingredient rawIron = Ingredient.of(RAW_IRON);
        return FakeRecipeBook.vanillaLike()
                .add(new Recipe(
                        "test:stone_pickaxe",
                        STONE_PICKAXE,
                        1,
                        List.of(cobblestone, cobblestone, cobblestone, stick, stick),
                        Station.CRAFTING_TABLE))
                .add(new Recipe(
                        "test:furnace",
                        "minecraft:furnace",
                        1,
                        java.util.Collections.nCopies(8, cobblestone),
                        Station.CRAFTING_TABLE))
                .add(new Recipe("test:iron_ingot", IRON_INGOT, 1, List.of(rawIron), Station.FURNACE, 200))
                .add(new Recipe(
                        "test:gold_ingot",
                        "minecraft:gold_ingot",
                        1,
                        List.of(Ingredient.of("minecraft:raw_gold")),
                        Station.FURNACE,
                        200));
    }

    private static WorldFacts world(
            Map<String, Integer> blocks, Map<String, Integer> mobs, Map<String, Integer> containers, boolean explore) {
        return new WorldFacts(blocks, mobs, containers, Map.of(), Set.of(Station.CRAFTING_TABLE.noun()), explore, true);
    }

    private static WorldFacts world(Map<String, Integer> blocks, boolean explore) {
        return world(blocks, Map.of(), Map.of(), explore);
    }

    private static ObtainPlan plan(String target, int count, Map<String, Integer> inventory, WorldFacts world) {
        return ObtainPlanner.plan(target, count, inventory, book(), FakeSourceBook.vanillaLike(), world);
    }

    private static int indexOf(ObtainPlan plan, Predicate<ObtainStep> test) {
        for (int i = 0; i < plan.steps().size(); i++) {
            if (test.test(plan.steps().get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasCraft(ObtainPlan plan, String item) {
        return indexOf(
                        plan,
                        step -> step instanceof ObtainStep.Craft craft
                                && craft.item().equals(item))
                >= 0;
    }

    @Test
    void woodenPickaxeFromLogs() {
        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), world(Map.of(OAK_LOG, 6), false));
        assertTrue(plan.ok(), plan.missing().toString());
        assertEquals(2, plan.steps().size(), plan.steps().toString());
        assertEquals(OAK_LOG, ((ObtainStep.Mine) plan.steps().get(0)).block());
        assertEquals(WOODEN_PICKAXE, ((ObtainStep.Craft) plan.steps().get(1)).item());
    }

    @Test
    void localWoodBeatsExploringForTheFirstSpecies() {
        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), world(Map.of(ACACIA_LOG, 6), true));

        assertTrue(plan.ok(), plan.missing().toString());
        assertFalse(
                plan.steps().stream().anyMatch(step -> step instanceof ObtainStep.Explore),
                plan.steps().toString());
        assertEquals(ACACIA_LOG, ((ObtainStep.Mine) plan.steps().get(0)).block());
    }

    @Test
    void planksTagExploresAnyWood() {
        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), world(Map.of(STONE, 8), true));

        assertTrue(plan.ok(), plan.missing().toString());
        assertEquals(
                ObtainStep.Explore.class,
                plan.steps().get(0).getClass(),
                plan.steps().toString());
        ObtainStep.Explore explore = (ObtainStep.Explore) plan.steps().get(0);
        assertEquals("#minecraft:logs", explore.block());
        assertFalse(explore.down());
        assertFalse(
                plan.steps().stream()
                        .anyMatch(step -> step instanceof ObtainStep.Explore other
                                && !other.block().startsWith("#")),
                plan.steps().toString());
    }

    @Test
    void specificLogItemExploresTheSpecies() {
        ObtainPlan plan = plan(OAK_LOG, 1, Map.of(), world(Map.of(ACACIA_LOG, 6), true));

        assertTrue(plan.ok(), plan.missing().toString());
        assertEquals(OAK_LOG, ((ObtainStep.Explore) plan.steps().get(0)).block());
    }

    @Test
    void woodTagIngredientDoesNotExplorePerSpecies() {
        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), world(Map.of(STONE, 8), true));

        List<ObtainStep> explores = plan.steps().stream()
                .filter(ObtainStep.Explore.class::isInstance)
                .toList();
        assertEquals(1, explores.size(), plan.steps().toString());
        assertEquals("#minecraft:logs", ((ObtainStep.Explore) explores.get(0)).block());
    }

    @Test
    void mineCapacityIsNotDoubleCounted() {
        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), world(Map.of(ACACIA_LOG, 1), true));

        List<ObtainStep.Mine> mines = plan.steps().stream()
                .filter(ObtainStep.Mine.class::isInstance)
                .map(ObtainStep.Mine.class::cast)
                .filter(mine -> mine.block().equals(ACACIA_LOG))
                .toList();
        assertEquals(1, mines.size(), plan.steps().toString());
        assertTrue(
                plan.steps().stream()
                        .anyMatch(
                                step -> step instanceof ObtainStep.Explore || step instanceof ObtainStep.VisitResource),
                plan.steps().toString());
    }

    @Test
    void oneAcaciaTreeSuppliesMultiplePlanksCrafts() {
        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), world(Map.of(ACACIA_LOG, 4), true));

        List<ObtainStep.Mine> mines = plan.steps().stream()
                .filter(ObtainStep.Mine.class::isInstance)
                .map(ObtainStep.Mine.class::cast)
                .filter(mine -> mine.block().equals(ACACIA_LOG))
                .toList();
        assertEquals(1, mines.size(), plan.steps().toString());
        assertEquals(2, mines.get(0).count(), plan.steps().toString());
    }

    @Test
    void conditionalSiteIsUsedOnlyWhenNoLocalPlanExists() {
        ResourceCandidate remembered = new ResourceCandidate(
                41,
                ACACIA_LOG,
                "minecraft:overworld",
                new Pos(40, 64, 0),
                new Pos(39, 64, 0),
                5,
                1_200,
                false,
                false,
                184.0);
        WorldFacts facts = new WorldFacts(
                Map.of(ACACIA_LOG, 6),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of(Station.CRAFTING_TABLE.noun()),
                true,
                true,
                List.of(remembered));

        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), facts);

        assertTrue(plan.ok(), plan.missing().toString());
        assertFalse(
                plan.steps().stream().anyMatch(step -> step instanceof ObtainStep.VisitResource),
                plan.steps().toString());
        assertEquals(ACACIA_LOG, ((ObtainStep.Mine) plan.steps().get(0)).block());
    }

    @Test
    void rememberedResourceProducesConditionalVisitPrefix() {
        ResourceCandidate candidate = new ResourceCandidate(
                41,
                OAK_LOG,
                "minecraft:overworld",
                new Pos(40, 64, 0),
                new Pos(39, 64, 0),
                5,
                1_200,
                false,
                false,
                184.0);
        ResourceCandidate tied = new ResourceCandidate(
                42,
                OAK_LOG,
                "minecraft:overworld",
                new Pos(40, 64, 0),
                new Pos(39, 64, 0),
                5,
                1_200,
                false,
                false,
                184.0);
        WorldFacts facts = new WorldFacts(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of(Station.CRAFTING_TABLE.noun()),
                true,
                true,
                List.of(tied, candidate));

        ObtainPlan plan = plan("minecraft:oak_planks", 1, Map.of(), facts);

        assertFalse(plan.ok());
        assertTrue(plan.conditional());
        assertTrue(plan.actionable());
        assertEquals(Map.of(OAK_LOG, 1), plan.missing());
        assertEquals(List.of(new ObtainStep.VisitResource(41, "minecraft:overworld", OAK_LOG)), plan.steps());
    }

    @Test
    void rememberedResourceIsIgnoredWhenExplorationIsDisabled() {
        ResourceCandidate candidate = new ResourceCandidate(
                41,
                OAK_LOG,
                "minecraft:overworld",
                new Pos(40, 64, 0),
                new Pos(39, 64, 0),
                5,
                1_200,
                false,
                false,
                184.0);
        WorldFacts facts = new WorldFacts(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of(Station.CRAFTING_TABLE.noun()),
                false,
                true,
                List.of(candidate));

        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), facts);

        assertFalse(plan.actionable());
        assertFalse(plan.conditional());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    void excludedSiteFallsBackToNextRememberedSite() {
        ResourceCandidate first = new ResourceCandidate(
                41,
                OAK_LOG,
                "minecraft:overworld",
                new Pos(40, 64, 0),
                new Pos(39, 64, 0),
                5,
                1_200,
                false,
                false,
                184.0);
        ResourceCandidate second = new ResourceCandidate(
                42,
                OAK_LOG,
                "minecraft:overworld",
                new Pos(50, 64, 0),
                new Pos(49, 64, 0),
                5,
                1_200,
                false,
                false,
                220.0);
        WorldFacts facts = new WorldFacts(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of(Station.CRAFTING_TABLE.noun()),
                true,
                true,
                List.of(first, second),
                Set.of(),
                Set.of(41L));

        ObtainPlan plan = plan("minecraft:oak_planks", 1, Map.of(), facts);

        assertEquals(
                new ObtainStep.VisitResource(42, "minecraft:overworld", OAK_LOG),
                plan.steps().get(0));
    }

    @Test
    void ironPickaxeChain() {
        ObtainPlan plan =
                plan(IRON_PICKAXE, 1, Map.of(), world(Map.of(OAK_LOG, 8, STONE, 16, IRON_ORE, 4, COAL_ORE, 4), false));
        assertTrue(plan.ok(), plan.missing().toString());
        int wooden = indexOf(
                plan,
                step -> step instanceof ObtainStep.Craft craft && craft.item().equals(WOODEN_PICKAXE));
        int mineStone = indexOf(
                plan,
                step -> step instanceof ObtainStep.Mine mine && mine.block().equals(STONE));
        int stone = indexOf(
                plan,
                step -> step instanceof ObtainStep.Craft craft && craft.item().equals(STONE_PICKAXE));
        int mineIron = indexOf(
                plan,
                step -> step instanceof ObtainStep.Mine mine && mine.block().equals(IRON_ORE));
        int smelt = indexOf(plan, step -> step instanceof ObtainStep.Smelt);
        int iron = indexOf(
                plan,
                step -> step instanceof ObtainStep.Craft craft && craft.item().equals(IRON_PICKAXE));
        assertTrue(
                wooden >= 0
                        && mineStone > wooden
                        && stone > mineStone
                        && mineIron > stone
                        && smelt > mineIron
                        && iron > smelt,
                plan.steps().toString());
        assertTrue(hasCraft(plan, "minecraft:furnace"), plan.steps().toString());
    }

    @Test
    void carriedStonePickaxeSkipsToolChain() {
        ObtainPlan plan = plan(
                IRON_PICKAXE,
                1,
                Map.of(STONE_PICKAXE, 1, STICK, 2),
                world(Map.of(OAK_LOG, 8, STONE, 16, IRON_ORE, 4, COAL_ORE, 4), false));
        assertTrue(plan.ok(), plan.missing().toString());
        assertFalse(hasCraft(plan, WOODEN_PICKAXE), plan.steps().toString());
        assertFalse(hasCraft(plan, STONE_PICKAXE), plan.steps().toString());
    }

    @Test
    void containersCheaperThanMining() {
        ObtainPlan plan = plan(
                IRON_PICKAXE, 1, Map.of(STICK, 2), world(Map.of(IRON_ORE, 4), Map.of(), Map.of(IRON_INGOT, 3), false));
        assertTrue(plan.ok(), plan.missing().toString());
        assertEquals(2, plan.steps().size(), plan.steps().toString());
        ObtainStep.Take take = (ObtainStep.Take) plan.steps().get(0);
        assertEquals(IRON_INGOT, take.item());
        assertEquals(3, take.count());
        assertEquals(IRON_PICKAXE, ((ObtainStep.Craft) plan.steps().get(1)).item());
    }

    @Test
    void containersOff() {
        ObtainPlan plan = plan(
                IRON_PICKAXE,
                1,
                Map.of(STICK, 2),
                world(Map.of(OAK_LOG, 8, STONE, 16, IRON_ORE, 4, COAL_ORE, 4), false));
        assertTrue(plan.ok(), plan.missing().toString());
        assertFalse(
                plan.steps().stream().anyMatch(step -> step instanceof ObtainStep.Take),
                plan.steps().toString());
        assertTrue(
                plan.steps().stream().anyMatch(step -> step instanceof ObtainStep.Smelt),
                plan.steps().toString());
    }

    @Test
    void partialFromContainers() {
        ObtainPlan plan = plan(
                IRON_INGOT,
                3,
                Map.of(STICK, 2),
                world(Map.of(OAK_LOG, 8, STONE, 16, IRON_ORE, 4, COAL_ORE, 4), Map.of(), Map.of(IRON_INGOT, 2), false));
        assertTrue(plan.ok(), plan.missing().toString());
        ObtainStep.Take take = plan.steps().stream()
                .filter(step -> step instanceof ObtainStep.Take)
                .map(step -> (ObtainStep.Take) step)
                .findFirst()
                .orElseThrow();
        assertEquals(2, take.count());
        ObtainStep.Smelt smelt = plan.steps().stream()
                .filter(step -> step instanceof ObtainStep.Smelt)
                .map(step -> (ObtainStep.Smelt) step)
                .findFirst()
                .orElseThrow();
        assertEquals(1, smelt.count());
        assertEquals(RAW_IRON, smelt.input());
    }

    @Test
    void huntForLeather() {
        ObtainPlan plan =
                plan("minecraft:leather", 1, Map.of(), world(Map.of(), Map.of("minecraft:cow", 2), Map.of(), false));
        assertTrue(plan.ok(), plan.missing().toString());
        assertEquals(1, plan.steps().size());
        assertEquals(1, ((ObtainStep.Hunt) plan.steps().get(0)).count());

        ObtainPlan missing = plan("minecraft:leather", 1, Map.of(), world(Map.of(), false));
        assertFalse(missing.ok());
        assertEquals(Map.of("minecraft:leather", 1), missing.missing());
    }

    @Test
    void exploreDownForIron() {
        ObtainPlan plan = plan(RAW_IRON, 1, Map.of(STONE_PICKAXE, 1), world(Map.of(STONE, 8), true));
        assertTrue(plan.ok(), plan.missing().toString());
        int explore = indexOf(plan, step -> step instanceof ObtainStep.Explore);
        int mine = indexOf(plan, step -> step instanceof ObtainStep.Mine);
        assertTrue(explore >= 0 && mine > explore, plan.steps().toString());
        ObtainStep.Explore step = (ObtainStep.Explore) plan.steps().get(explore);
        assertTrue(step.down());
        assertEquals(16, step.depthY());
        assertEquals(IRON_ORE, step.block());
    }

    @Test
    void exploreOffMeansMissing() {
        ObtainPlan plan = plan(RAW_IRON, 1, Map.of(STONE_PICKAXE, 1), world(Map.of(STONE, 8), false));
        assertFalse(plan.ok());
        assertEquals(Map.of(RAW_IRON, 1), plan.missing());
        assertTrue(plan.steps().isEmpty(), plan.steps().toString());
    }

    @Test
    void otherDimensionMeansMissing() {
        WorldFacts world = new WorldFacts(
                Map.of(STONE, 8), Map.of(), Map.of(), Map.of(), Set.of(Station.CRAFTING_TABLE.noun()), true, false);
        ObtainPlan plan = plan(RAW_IRON, 1, Map.of(STONE_PICKAXE, 1), world);
        assertFalse(plan.ok());
        assertEquals(Map.of(RAW_IRON, 1), plan.missing());
        assertFalse(
                plan.steps().stream().anyMatch(step -> step instanceof ObtainStep.Explore),
                plan.steps().toString());
    }

    @Test
    void surfaceExploreForLogs() {
        ObtainPlan plan = plan(OAK_LOG, 1, Map.of(), world(Map.of(STONE, 8), true));
        assertTrue(plan.ok(), plan.missing().toString());
        assertEquals(2, plan.steps().size(), plan.steps().toString());
        ObtainStep.Explore explore = (ObtainStep.Explore) plan.steps().get(0);
        assertFalse(explore.down());
        assertEquals(OAK_LOG, ((ObtainStep.Mine) plan.steps().get(1)).block());
    }

    @Test
    void toolMissingAfterBreakIsPlannedAgain() {
        WorldFacts world = world(Map.of(OAK_LOG, 8, STONE, 8, IRON_ORE, 4), false);
        ObtainPlan plan = plan(RAW_IRON, 3, Map.of(RAW_IRON, 2), world);
        assertTrue(plan.ok(), plan.missing().toString());
        int stonePickaxe = indexOf(
                plan,
                step -> step instanceof ObtainStep.Craft craft && craft.item().equals(STONE_PICKAXE));
        int mineIron = indexOf(
                plan,
                step -> step instanceof ObtainStep.Mine mine && mine.block().equals(IRON_ORE));
        assertTrue(stonePickaxe >= 0 && mineIron > stonePickaxe, plan.steps().toString());
        assertEquals(1, ((ObtainStep.Mine) plan.steps().get(mineIron)).count());

        ObtainPlan nothing = plan(RAW_IRON, 2, Map.of(RAW_IRON, 2), world);
        assertTrue(nothing.ok());
        assertTrue(nothing.steps().isEmpty(), nothing.steps().toString());
    }

    @Test
    void goldIngotFromScratchNeedsAnIronPickaxe() {
        ObtainPlan plan = plan(
                "minecraft:gold_ingot",
                1,
                Map.of(),
                world(Map.of(OAK_LOG, 8, STONE, 16, IRON_ORE, 4, "minecraft:gold_ore", 4, COAL_ORE, 4), false));
        assertTrue(plan.ok(), plan.missing().toString());
        int ironPickaxe = indexOf(
                plan,
                step -> step instanceof ObtainStep.Craft craft && craft.item().equals(IRON_PICKAXE));
        int gold = indexOf(
                plan,
                step -> step instanceof ObtainStep.Mine mine && mine.block().equals("minecraft:gold_ore"));
        assertTrue(ironPickaxe >= 0 && gold > ironPickaxe, plan.steps().toString());
    }

    @Test
    void noSelfLoopThroughTarget() {
        ObtainPlan plan = plan(STICK, 4, Map.of(), world(Map.of(OAK_LOG, 8), false));
        assertTrue(plan.ok(), plan.missing().toString());
        assertTrue(plan.steps().size() <= ObtainPlanner.MAX_DEPTH, plan.steps().toString());
        assertTrue(
                plan.steps().stream().anyMatch(step -> step instanceof ObtainStep.Mine),
                plan.steps().toString());
    }

    @Test
    void mergesConsecutiveSteps() {
        ObtainPlan plan = plan(WOODEN_PICKAXE, 1, Map.of(), world(Map.of(OAK_LOG, 8), false));
        List<ObtainStep> mines = plan.steps().stream()
                .filter(step ->
                        step instanceof ObtainStep.Mine mine && mine.block().equals(OAK_LOG))
                .toList();
        assertEquals(1, mines.size(), plan.steps().toString());
        assertTrue(((ObtainStep.Mine) mines.get(0)).count() >= 2, plan.steps().toString());
    }
}
