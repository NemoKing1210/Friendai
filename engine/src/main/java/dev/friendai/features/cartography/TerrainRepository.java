package dev.friendai.features.cartography;

import java.util.UUID;

/** Owner-scoped persistence for the explored-terrain grid. */
public interface TerrainRepository {
    /** The saved grid, or an empty grid when the owner has no record yet. */
    TerrainGrid grid(UUID owner);

    /** Replaces the owner's grid; an unchanged snapshot may write nothing. */
    void save(UUID owner, TerrainGrid grid);

    /** Session-only implementation for runtimes composed without durability. */
    TerrainRepository NONE = new TerrainRepository() {
        @Override
        public TerrainGrid grid(UUID owner) {
            return new TerrainGrid();
        }

        @Override
        public void save(UUID owner, TerrainGrid grid) {}
    };
}
