package dev.friendai.farming;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lookup of {@link CropInfo} by crop block id or seed item id; the list order is the plantable order. */
public final class CropBook {
    private final List<CropInfo> plantable;
    private final Map<String, CropInfo> byCrop = new HashMap<>();
    private final Map<String, CropInfo> bySeed = new HashMap<>();

    private CropBook(List<CropInfo> crops) {
        this.plantable = List.copyOf(crops);
        for (CropInfo info : plantable) {
            if (byCrop.put(info.crop(), info) != null) {
                throw new IllegalArgumentException("duplicate crop id: " + info.crop());
            }
            if (bySeed.put(info.seed(), info) != null) {
                throw new IllegalArgumentException("duplicate seed id: " + info.seed());
            }
        }
    }

    public static CropBook of(List<CropInfo> crops) {
        return new CropBook(crops);
    }

    public Optional<CropInfo> byCrop(String crop) {
        return Optional.ofNullable(byCrop.get(crop));
    }

    public Optional<CropInfo> bySeed(String seed) {
        return Optional.ofNullable(bySeed.get(seed));
    }

    public List<CropInfo> plantable() {
        return plantable;
    }
}
