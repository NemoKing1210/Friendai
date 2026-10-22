package dev.friedai.task.inventory;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Matches items by id ("minecraft:oak_log") or by tag ("#minecraft:planks"). */
public interface ItemMatcher {
    boolean test(ItemStack stack);

    /** The id or #tag this matcher was built from, normalized with its namespace. */
    String label();

    static Optional<ItemMatcher> parse(String idOrTag) {
        if (idOrTag == null || idOrTag.isBlank()) {
            return Optional.empty();
        }
        String text = idOrTag.strip();
        boolean isTag = text.startsWith("#");
        Identifier id = Identifier.tryParse(isTag ? text.substring(1) : text);
        if (id == null) {
            return Optional.empty();
        }
        if (isTag) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            return Optional.of(new ItemMatcher() {
                @Override
                public boolean test(ItemStack stack) {
                    return !stack.isEmpty() && stack.is(tag);
                }

                @Override
                public String label() {
                    return "#" + id;
                }
            });
        }
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return Optional.empty();
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return Optional.of(new ItemMatcher() {
            @Override
            public boolean test(ItemStack stack) {
                return !stack.isEmpty() && stack.is(item);
            }

            @Override
            public String label() {
                return id.toString();
            }
        });
    }
}
