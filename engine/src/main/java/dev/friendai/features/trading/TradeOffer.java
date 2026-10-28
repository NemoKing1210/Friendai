package dev.friendai.features.trading;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Immutable observed offer. A quote is for one pricing actor and always needs live validation. */
public record TradeOffer(
        String ref,
        String fingerprint,
        List<TradeCost> baseInputs,
        List<TradeCost> effectiveInputs,
        TradeItem output,
        int uses,
        int maxUses,
        int merchantXp,
        UUID pricingActor,
        long observedTick) {
    public TradeOffer {
        TradeItem.requireText(ref, 160);
        if (ref.isBlank() || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("offer needs a reference and semantic fingerprint");
        }
        baseInputs = inputs(baseInputs);
        effectiveInputs = inputs(effectiveInputs);
        if (baseInputs.size() != effectiveInputs.size()) throw new IllegalArgumentException("cost arity changed");
        for (int i = 0; i < baseInputs.size(); i++) {
            var base = baseInputs.get(i);
            var effective = effectiveInputs.get(i);
            if (!base.itemId().equals(effective.itemId())
                    || !base.predicateJson().equals(effective.predicateJson())) {
                throw new IllegalArgumentException("effective price changed input identity");
            }
        }
        if (output == null
                || pricingActor == null
                || observedTick < 0
                || uses < 0
                || maxUses < 0
                || uses > maxUses
                || merchantXp < 0) {
            throw new IllegalArgumentException("invalid observed trade offer");
        }
    }

    private static List<TradeCost> inputs(List<TradeCost> values) {
        if (values == null || values.isEmpty() || values.size() > 2) {
            throw new IllegalArgumentException("an offer needs one or two inputs");
        }
        return List.copyOf(values);
    }

    public boolean exhausted() {
        return uses == maxUses;
    }

    /** Called by the observing adapter, using a stable discriminator for genuinely duplicate offers. */
    public static String fingerprint(List<TradeCost> baseInputs, TradeItem output, int duplicateDiscriminator) {
        if (duplicateDiscriminator < 0 || output == null) throw new IllegalArgumentException("invalid offer identity");
        var root = new JsonObject();
        var costs = new JsonArray();
        for (var input : inputs(baseInputs)) {
            var cost = new JsonObject();
            cost.addProperty("item", input.itemId());
            cost.addProperty("count", input.count());
            cost.add("predicate", JsonParser.parseString(input.predicateJson()));
            costs.add(cost);
        }
        root.add("costs", costs);
        root.addProperty("result", output.itemId());
        root.addProperty("count", output.count());
        root.add("components", JsonParser.parseString(output.componentsJson()));
        root.addProperty("duplicate", duplicateDiscriminator);
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(root.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Stable independently of list index. Book references never reuse an old index for new goods. */
    public String stableRef() {
        return "offer-" + semanticIdentity() + "-" + fingerprint;
    }

    TradeOffer withStableRef() {
        return new TradeOffer(
                stableRef(),
                fingerprint,
                baseInputs,
                effectiveInputs,
                output,
                uses,
                maxUses,
                merchantXp,
                pricingActor,
                observedTick);
    }

    String semanticIdentity() {
        return fingerprint(baseInputs, output, 0);
    }
}
