package dev.friendai.crafting.obtain;

/** One step of an obtain plan, in the order the tasks should run it. */
public sealed interface ObtainStep {
    /** Take items from a nearby container. */
    record Take(String item, int count) implements ObtainStep {
        public Take {
            if (count <= 0) {
                throw new IllegalArgumentException("take count must be positive");
            }
        }
    }

    /** Craft items; the crafting task plans its own intermediates. */
    record Craft(String item, int count) implements ObtainStep {
        public Craft {
            if (count <= 0) {
                throw new IllegalArgumentException("craft count must be positive");
            }
        }
    }

    /** Smelt or cook {@code count} of {@code input} into {@code output}. */
    record Smelt(String input, int count, String output) implements ObtainStep {
        public Smelt {
            if (count <= 0) {
                throw new IllegalArgumentException("smelt count must be positive");
            }
        }
    }

    /** Mine {@code count} blocks of {@code block}. */
    record Mine(String block, int count, ToolNeed tool) implements ObtainStep {
        public Mine {
            if (count <= 0) {
                throw new IllegalArgumentException("mine count must be positive");
            }
        }
    }

    /** Kill {@code count} mobs. */
    record Hunt(String mob, int count) implements ObtainStep {
        public Hunt {
            if (count <= 0) {
                throw new IllegalArgumentException("hunt count must be positive");
            }
        }
    }

    /** Harvest {@code count} mature crop blocks. */
    record Harvest(int count, String crop) implements ObtainStep {
        public Harvest {
            if (count <= 0) {
                throw new IllegalArgumentException("harvest count must be positive");
            }
        }
    }

    /** Shear {@code count} animals. */
    record Shear(String mob, int count) implements ObtainStep {
        public Shear {
            if (count <= 0) {
                throw new IllegalArgumentException("shear count must be positive");
            }
        }
    }

    /** Fill {@code count} buckets with milk. */
    record Milk(int count) implements ObtainStep {
        public Milk {
            if (count <= 0) {
                throw new IllegalArgumentException("milk count must be positive");
            }
        }
    }

    /** Look for {@code block}, either on the surface or down at {@code depthY}. */
    record Explore(String block, boolean down, int depthY) implements ObtainStep {}
}
