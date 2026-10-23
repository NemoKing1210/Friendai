package dev.friendai.voice;

/** A downloadable speech model file with the size and SHA-256 it must have. */
public record ModelSpec(String file, long size, String sha256) {}
