package dev.friendai.features.appearance;

/** Raw bytes of a downloaded skin and whether its profile asked for the slim arm model. Not yet validated. */
public record FetchedSkin(byte[] png, boolean slim) {}
