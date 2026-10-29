package dev.friendai.features.trading.minecraft.trading;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.friendai.features.trading.TradeCost;
import dev.friendai.features.trading.TradeItem;
import dev.friendai.features.trading.TradeOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentExactPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;

/** Lossless, registry-aware conversion between runtime stacks and the bounded pure trading model. */
public final class TradeItemCodec {
    private TradeItemCodec() {}

    public static TradeItem encode(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            throw new IllegalArgumentException("a nonempty stack and registries are required");
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        JsonElement encoded =
                encode(ItemStack.CODEC.encodeStart(ops(registries), stack.copyWithCount(1)), "item stack");
        if (!encoded.isJsonObject()) throw new IllegalArgumentException("encoded item stack is not an object");
        JsonElement components = encoded.getAsJsonObject().get("components");
        String componentJson = components == null || components.isJsonNull() ? "{}" : components.toString();
        return new TradeItem(id, componentJson, stack.getCount());
    }

    public static ItemStack decode(TradeItem item, HolderLookup.Provider registries) {
        if (item == null || registries == null) throw new IllegalArgumentException("item and registries are required");
        JsonObject encoded = new JsonObject();
        encoded.addProperty("id", item.itemId());
        encoded.addProperty("count", item.count());
        JsonElement components = JsonParser.parseString(item.componentsJson());
        if (!components.isJsonObject()) throw new IllegalArgumentException("item components are not an object");
        if (!components.getAsJsonObject().isEmpty()) encoded.add("components", components);
        return decode(ItemStack.CODEC.parse(ops(registries), encoded), "item stack");
    }

    public static TradeCost encode(ItemCost cost, HolderLookup.Provider registries) {
        if (cost == null || registries == null) throw new IllegalArgumentException("cost and registries are required");
        String id = BuiltInRegistries.ITEM.getKey(cost.item().value()).toString();
        JsonElement predicate = encode(
                DataComponentExactPredicate.CODEC.encodeStart(ops(registries), cost.components()),
                "item component predicate");
        return new TradeCost(id, predicate.toString(), cost.count());
    }

    public static ItemCost decode(TradeCost cost, HolderLookup.Provider registries) {
        if (cost == null || registries == null) throw new IllegalArgumentException("cost and registries are required");
        Holder<Item> item = BuiltInRegistries.ITEM
                .get(Identifier.parse(cost.itemId()))
                .orElseThrow(() -> new IllegalArgumentException("unknown trade item " + cost.itemId()));
        JsonElement encoded = JsonParser.parseString(cost.predicateJson());
        DataComponentExactPredicate predicate =
                decode(DataComponentExactPredicate.CODEC.parse(ops(registries), encoded), "item component predicate");
        return new ItemCost(item, cost.count(), predicate);
    }

    /** Fingerprints the full item identity, excluding stack count. */
    public static String fingerprint(ItemStack stack, HolderLookup.Provider registries) {
        TradeItem item = encode(stack.copyWithCount(1), registries);
        byte[] bytes = (item.itemId() + "\u0000" + item.componentsJson()).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Unmodified default stacks are usable; custom, named, enchanted, damaged or bound stacks need exact consent. */
    public static boolean mayPay(ItemStack stack, TradeOrder order, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || order == null || registries == null) return false;
        return stack.getComponentsPatch().isEmpty()
                || order.allowedPaymentFingerprints().contains(fingerprint(stack, registries));
    }

    private static RegistryOps<JsonElement> ops(HolderLookup.Provider registries) {
        return RegistryOps.create(JsonOps.INSTANCE, registries);
    }

    private static <T> JsonElement encode(DataResult<T> result, String label) {
        T value = result.result()
                .orElseThrow(() -> new IllegalArgumentException("could not encode " + label + ": "
                        + result.error().map(DataResult.Error::message).orElse("unknown codec error")));
        if (value instanceof JsonElement json) return json;
        throw new IllegalArgumentException("codec did not produce JSON for " + label);
    }

    private static <T> T decode(DataResult<T> result, String label) {
        return result.result()
                .orElseThrow(() -> new IllegalArgumentException("could not decode " + label + ": "
                        + result.error().map(DataResult.Error::message).orElse("unknown codec error")));
    }
}
