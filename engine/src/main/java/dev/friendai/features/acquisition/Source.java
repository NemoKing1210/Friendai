package dev.friendai.features.acquisition;

/** A way an item enters the world: a block drop, a mob drop, a harvest, shearing or milking. */
public sealed interface Source {
    /** Mining a block; {@code perBlock} is the expected yield of one block. */
    record Mine(String block, ToolNeed tool, double perBlock, boolean surface, int depthY) implements Source {
        public Mine {
            if (perBlock <= 0) {
                throw new IllegalArgumentException("mine yield must be positive: " + block);
            }
        }
    }

    /** Killing a mob; {@code perKill} is the expected yield of one kill. */
    record Hunt(String mob, double perKill) implements Source {
        public Hunt {
            if (perKill <= 0) {
                throw new IllegalArgumentException("hunt yield must be positive: " + mob);
            }
        }
    }

    /** Harvesting a mature crop block. */
    record Harvest(String crop, double perBlock) implements Source {
        public Harvest {
            if (perBlock <= 0) {
                throw new IllegalArgumentException("harvest yield must be positive: " + crop);
            }
        }
    }

    /** Shearing an animal. */
    record Shear(String mob, double perAnimal) implements Source {
        public Shear {
            if (perAnimal <= 0) {
                throw new IllegalArgumentException("shear yield must be positive: " + mob);
            }
        }
    }

    /** Milking an animal into a bucket. */
    record Milk(String mob) implements Source {}
}
