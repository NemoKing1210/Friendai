package dev.friendai.client.audio;

import java.util.Objects;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/** Ensures client-only native initialization cannot be reached from a dedicated-server environment. */
public final class NativeAudioGuard {
    private NativeAudioGuard() {}

    public static void loadClientNative(Runnable initializer) {
        boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        load(client, initializer);
    }

    public static void requireClientEnvironment() {
        checkClient(FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT);
    }

    public static <T> T createClientNative(Supplier<T> initializer) {
        boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
        return create(client, initializer);
    }

    static void load(boolean clientEnvironment, Runnable initializer) {
        Objects.requireNonNull(initializer, "initializer");
        checkClient(clientEnvironment);
        initializer.run();
    }

    static <T> T create(boolean clientEnvironment, Supplier<T> initializer) {
        Objects.requireNonNull(initializer, "initializer");
        checkClient(clientEnvironment);
        return initializer.get();
    }

    private static void checkClient(boolean clientEnvironment) {
        if (!clientEnvironment)
            throw new IllegalStateException("native audio is only available in a client environment");
    }
}
