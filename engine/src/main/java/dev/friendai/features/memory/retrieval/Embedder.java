package dev.friendai.features.memory.retrieval;

/**
 * A text embedder. The interface is deliberately tiny so a local model, a cloud call or a test fake all fit behind
 * it; the retriever never knows which one is in use.
 */
@FunctionalInterface
public interface Embedder {
    /** A vector for the text, or null/empty when the backend cannot answer. */
    float[] embed(String text);
}
