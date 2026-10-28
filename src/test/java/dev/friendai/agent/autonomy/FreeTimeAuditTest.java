package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.crafting.obtain.Tier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The item 18.1 harness. Runs one compressed hour on a fixed world and writes the table the audit quotes.
 *
 * <p>The world starts from nothing a player made: an empty bag, no home, no storage, no farm, the owner
 * online and idle. It gets dark after 20 simulated minutes. Shifts complete deterministically, and the
 * world evolves from what they did, so the agenda has to keep finding work.
 */
class FreeTimeAuditTest {
    @Test
    void anHourWithoutAPlayerKeepsTheCompanionWorking() throws IOException {
        ScenarioWorld world = new ScenarioWorld();
        FreeTimeAuditHarness.Report report =
                new FreeTimeAuditHarness(world, AutonomySettings.DEFAULTS).run(Facts.NOW, 3_600);
        Path out = Path.of("build", "reports", "freetime-audit-after.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.markdown());
        assertTrue(report.shifts() >= 2, "an hour of free time must start work, got " + report.shifts());
        assertEquals(0, report.stalls(), "nothing running for a watchdog window is a bug: " + report.events());
    }

    /** A scripted survival world: what the facts say now and what a finished shift changes. */
    private static final class ScenarioWorld implements FreeTimeAuditHarness.World {
        private long start;
        private int logs;
        private int cobble;
        private int coal;
        private int torches;
        private int iron;
        private int diamonds;
        private int seeds;
        private int xp;
        private int foodPoints;
        private Tier pickaxe = Tier.NONE;
        private Tier axe = Tier.NONE;
        private Tier sword = Tier.NONE;
        private Tier armor = Tier.NONE;
        private int armorPieces;
        private boolean shield;
        private boolean bucket;
        private boolean home;
        private boolean storage;
        private boolean farm;
        private boolean bed;
        private boolean trade;
        private boolean enchanting;
        private boolean craftingTable;
        private boolean furnace;
        private boolean dark;
        private int hunger = 20;
        private boolean slept;

        @Override
        public AutonomyFacts facts(long now) {
            long minute = (now - Facts.NOW) / 60_000L;
            boolean night = minute >= 20 && minute < 55;
            if (!night) {
                slept = false;
            }
            dark = night && !slept;
            Facts facts = Facts.base()
                    .freeSlots(20)
                    .foodPoints(foodPoints)
                    .stock(StockKind.LOGS, logs)
                    .stock(StockKind.COBBLE, cobble)
                    .stock(StockKind.COAL, coal)
                    .stock(StockKind.TORCHES, torches)
                    .stock(StockKind.IRON_INGOT, iron)
                    .stock(StockKind.DIAMOND, diamonds)
                    .stock(StockKind.SEEDS, seeds)
                    .hunger(hunger)
                    .xpLevel(xp)
                    .pickaxe(pickaxe)
                    .axe(axe)
                    .sword(sword)
                    .armor(armor, armorPieces)
                    .craftingTable(craftingTable)
                    .furnace(furnace)
                    .dark(dark)
                    .now(now);
            if (shield) {
                facts.shield();
            }
            if (bucket) {
                facts.bucket();
            }
            if (home) {
                facts.home("home", 12);
            }
            if (storage) {
                facts.storage("storage", 10).storageFreeSlots(18);
            }
            if (farm) {
                facts.farm("wheat farm", "wheat", 8, 30 * 60_000L);
            }
            if (bed) {
                facts.bedAtHome();
            }
            if (trade) {
                facts.trade("village", 20, 40 * 60_000L);
            }
            if (enchanting) {
                facts.enchantingTableAtHome();
            }
            return facts.build();
        }

        @Override
        public boolean canPlan(NeedKind kind) {
            return switch (kind) {
                case SLEEP -> bed;
                case FARM, FARM_CREATE -> true;
                default -> true;
            };
        }

        @Override
        public int planSeconds(NeedKind kind) {
            return switch (kind) {
                case SURVIVE, SHELTER, SLEEP -> 300;
                case HUNGER -> 10;
                case UNLOAD -> 90;
                case COLLECT -> 60;
                case FARM, FARM_CREATE -> 180;
                case BUILD -> 600;
                case STOCK -> 300;
                case GEAR -> 240;
                case EXPLORE -> 420;
                default -> 240;
            };
        }

        @Override
        public void completed(NeedKind kind, boolean success) {
            if (!success) {
                return;
            }
            switch (kind) {
                case STOCK -> {
                    logs += 16;
                    coal += 8;
                    cobble += 16;
                    torches += 16;
                    if (pickaxe == Tier.STONE) {
                        iron += 4;
                    }
                    xp += 2;
                }
                case FOOD -> {
                    foodPoints = 20;
                    seeds += 2;
                    xp += 1;
                }
                case GEAR -> {
                    if (pickaxe == Tier.NONE) {
                        pickaxe = Tier.WOOD;
                        craftingTable = true;
                    } else if (pickaxe == Tier.WOOD && cobble >= 8) {
                        pickaxe = Tier.STONE;
                        axe = Tier.STONE;
                        sword = Tier.STONE;
                        furnace = true;
                    } else if (pickaxe == Tier.STONE && iron >= 3) {
                        pickaxe = Tier.IRON;
                        sword = Tier.IRON;
                        armor = Tier.IRON;
                        armorPieces = 2;
                        shield = true;
                        bucket = true;
                    } else if (pickaxe == Tier.IRON && diamonds >= 3) {
                        pickaxe = Tier.DIAMOND;
                        sword = Tier.DIAMOND;
                    }
                }
                case BUILD -> {
                    home = true;
                    bed = true;
                    trade = true;
                }
                case STORAGE -> storage = true;
                case FARM_CREATE -> {
                    farm = true;
                    enchanting = true;
                }
                case HUNGER -> hunger = 20;
                case SLEEP -> slept = true;
                case ENCHANT -> xp = Math.max(0, xp - 5);
                case TRADE -> {}
                default -> {}
            }
            hunger = Math.max(0, hunger - 2);
        }

        @Override
        public int blocksPlaced(NeedKind kind) {
            return switch (kind) {
                case BUILD -> 27;
                case FARM_CREATE -> 9;
                case STORAGE -> 1;
                default -> 0;
            };
        }
    }
}
