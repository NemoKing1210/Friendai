package dev.friendai.features.mining.minecraft.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/** Matches an included block id/tag and rejects any of the excluded ids/tags. */
public final class BlockFilter implements BlockMatcher {
    private final BlockMatcher include;
    private final List<BlockMatcher> excludes;

    public BlockFilter(BlockMatcher include, List<BlockMatcher> excludes) {
        this.include = Objects.requireNonNull(include, "include");
        this.excludes = List.copyOf(Objects.requireNonNull(excludes, "excludes"));
    }

    @Override
    public boolean test(BlockState state) {
        if (!include.test(state)) {
            return false;
        }
        for (BlockMatcher exclude : excludes) {
            if (exclude.test(state)) {
                return false;
            }
        }
        return true;
    }

    /** The include label followed by every exclusion, in the order the caller supplied them. */
    @Override
    public String label() {
        if (excludes.isEmpty()) {
            return include.label();
        }
        StringBuilder text = new StringBuilder(include.label()).append(" except ");
        for (int i = 0; i < excludes.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(excludes.get(i).label());
        }
        return text.toString();
    }

    public static Optional<BlockFilter> parse(String include, List<String> excludes) {
        if (include == null || include.isBlank()) {
            return Optional.empty();
        }
        List<BlockMatcher> parsed = new ArrayList<>(excludes.size());
        for (String exclude : excludes) {
            if (exclude == null || exclude.isBlank()) {
                return Optional.empty();
            }
            Optional<BlockMatcher> matcher = BlockMatcher.parse(exclude);
            if (matcher.isEmpty()) {
                return Optional.empty();
            }
            parsed.add(matcher.get());
        }
        Optional<BlockMatcher> base = BlockMatcher.parseTarget(include);
        return base.map(matcher -> new BlockFilter(matcher, parsed));
    }
}
