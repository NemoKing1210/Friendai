package dev.friendai.features.acquisition;

import dev.friendai.features.knowledge.search.EscortPolicy;

/** Immutable resource-search settings passed from server configuration to an acquisition task. */
public record ResourceSearchSettings(
        boolean allowExpeditions,
        boolean chunkLoadingEnabled,
        int localRadius,
        int localTicks,
        int expeditionRadius,
        int expeditionTicks,
        EscortPolicy.Config escort) {}
