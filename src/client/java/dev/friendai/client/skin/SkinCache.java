package dev.friendai.client.skin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.friendai.FriendaiMod;
import dev.friendai.net.SkinRequestC2SPayload;
import dev.friendai.net.SkinS2CPayload;
import dev.friendai.skin.SkinHash;
import dev.friendai.skin.SkinImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Companion skin textures by hash. A hash seen on a companion is requested from the server once; the image is
 * accepted only for a hash that was asked for and only when its bytes hash to it, then lives in the texture manager
 * until it falls out of the small least-recently-used set. Render thread only.
 */
public final class SkinCache {
    static final int CAPACITY = 16;
    /** A request that got no answer is repeated after this long. */
    private static final long RETRY_NANOS = 10_000_000_000L;

    private static final Map<String, Identifier> LOADED = new LinkedHashMap<>(32, 0.75f, true);
    private static final Map<String, Long> PENDING = new HashMap<>();
    /** Hashes whose image could not be decoded; they are not asked for again until the cache is cleared. */
    private static final Map<String, Boolean> BROKEN = new HashMap<>();

    private SkinCache() {}

    /** The texture for {@code hash}, or null while it is not here yet (the caller draws the default skin). */
    public static Identifier textureFor(String hash) {
        if (!SkinHash.isValid(hash) || BROKEN.containsKey(hash)) {
            return null;
        }
        Identifier id = LOADED.get(hash);
        if (id != null) {
            return id;
        }
        long now = System.nanoTime();
        Long asked = PENDING.get(hash);
        if ((asked == null || now - asked > RETRY_NANOS) && ClientPlayNetworking.canSend(SkinRequestC2SPayload.TYPE)) {
            PENDING.put(hash, now);
            ClientPlayNetworking.send(new SkinRequestC2SPayload(hash));
        }
        return null;
    }

    public static void onSkin(SkinS2CPayload payload) {
        String hash = payload.hash();
        if (PENDING.remove(hash) == null
                || !SkinHash.isValid(hash)
                || !SkinHash.of(payload.png()).equals(hash)) {
            return;
        }
        try {
            NativeImage image = NativeImage.read(payload.png());
            if (image.getWidth() != SkinImage.SIZE || image.getHeight() != SkinImage.SIZE) {
                image.close();
                BROKEN.put(hash, true);
                return;
            }
            Identifier id = FriendaiMod.id("skins/" + hash);
            // The texture owns the image from here on.
            Minecraft.getInstance()
                    .getTextureManager()
                    .register(id, new DynamicTexture(() -> "friendai skin " + hash, image));
            LOADED.put(hash, id);
            evict();
        } catch (IOException | RuntimeException e) {
            FriendaiMod.LOGGER.warn("Dropping an unreadable companion skin: {}", e.getMessage());
            BROKEN.put(hash, true);
        }
    }

    /** Forgets everything, e.g. on disconnect; textures are released. */
    public static void clear() {
        var textures = Minecraft.getInstance().getTextureManager();
        LOADED.values().forEach(textures::release);
        LOADED.clear();
        PENDING.clear();
        BROKEN.clear();
    }

    private static void evict() {
        var textures = Minecraft.getInstance().getTextureManager();
        Iterator<Map.Entry<String, Identifier>> oldest = LOADED.entrySet().iterator();
        while (LOADED.size() > CAPACITY && oldest.hasNext()) {
            textures.release(oldest.next().getValue());
            oldest.remove();
        }
    }
}
