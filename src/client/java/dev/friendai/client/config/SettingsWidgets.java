package dev.friendai.client.config;

import dev.friendai.client.ui.ClientToasts;
import dev.friendai.features.settings.SettingsModel;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * The generic YACL building blocks the settings screen and its category sections share: options with the
 * project's translation convention, the reset and filter wrappers, the search adapter, and the small text
 * and toast helpers.
 */
final class SettingsWidgets {
    private SettingsWidgets() {}

    static Component tr(String key) {
        return Component.translatable(key);
    }

    /** One badge per category, so a reader can tell what a change affects before opening it. */
    static Component scoped(Component name, String scopeKey) {
        return name.copy().append(Component.literal(" · ")).append(tr(scopeKey));
    }

    static Option<Boolean> booleanOption(
            String nameKey, boolean def, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(tr(nameKey))
                .binding(def, getter, setter)
                .controller(BooleanControllerBuilder::create)
                .build();
    }

    static Option<Boolean> booleanOption(
            String nameKey, String descriptionKey, boolean def, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(tr(nameKey))
                .description(OptionDescription.of(tr(descriptionKey)))
                .binding(def, getter, setter)
                .controller(BooleanControllerBuilder::create)
                .build();
    }

    static Option<String> stringOption(
            String nameKey, String descriptionKey, Supplier<String> getter, String def, Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(tr(nameKey))
                .description(OptionDescription.of(tr(descriptionKey)))
                .binding(def, getter, setter)
                .controller(StringControllerBuilder::create)
                .build();
    }

    static ButtonOption actionButton(String nameKey, Runnable action) {
        return ButtonOption.createBuilder()
                .name(tr(nameKey))
                .action((screen, button) -> action.run())
                .build();
    }

    /**
     * Rebuilds a category with a reset button as its first option. The button returns every config option of
     * that one category to its default and reopens the screen, so the other categories keep their edits and
     * the reset lands in the draft exactly like a manual change: Save still has to be pressed.
     */
    static ConfigCategory withReset(ConfigCategory category, Runnable reopen) {
        List<Option<?>> targets = resetTargets(category);
        if (targets.isEmpty()) return category;
        return withLeadingButton(category, resetButton("friendai.config.category.reset", targets, reopen));
    }

    /**
     * Rebuilds a category with the "reset everything" button as its first option. It returns every config
     * option of every category to its default, so it lands in the draft exactly like the per-category button:
     * Save still has to be pressed.
     *
     * <p>Resetting one option needs no control of ours: YACL itself puts a reset button on every option row
     * whose {@code canResetToDefault()} is true, which is the default for options built with
     * {@code .binding(default, getter, setter)}, and that button calls {@code requestSetDefault()} on that
     * single option.
     */
    static ConfigCategory withResetAll(ConfigCategory category, List<Option<?>> targets, Runnable reopen) {
        return withLeadingButton(category, resetButton("friendai.config.reset_all", targets, reopen));
    }

    /** Every option of the category that holds a setting: labels and buttons only drive the screen. */
    static List<Option<?>> resetTargets(ConfigCategory category) {
        List<Option<?>> targets = new ArrayList<>();
        for (OptionGroup group : category.groups()) {
            for (Option<?> option : group.options()) {
                if (row(option).resetsOnCategoryReset()) targets.add(option);
            }
        }
        return targets;
    }

    static ButtonOption resetButton(String nameKey, List<Option<?>> targets, Runnable reopen) {
        List<Option<?>> reset = List.copyOf(targets);
        return ButtonOption.createBuilder()
                .name(tr(nameKey))
                .action((screen, button) -> {
                    for (Option<?> option : reset) option.requestSetDefault();
                    reopen.run();
                })
                .build();
    }

    /** Rebuilds a category with one action button as its first option, keeping the order of everything else. */
    static ConfigCategory withLeadingButton(ConfigCategory category, ButtonOption button) {
        List<Option<?>> root = new ArrayList<>();
        root.add(button);
        List<OptionGroup> groups = new ArrayList<>();
        for (OptionGroup group : category.groups()) {
            if (group.isRoot()) {
                root.addAll(group.options());
                continue;
            }
            OptionGroup.Builder rebuilt = OptionGroup.createBuilder()
                    .name(group.name())
                    .options(group.options())
                    .collapsed(group.collapsed());
            if (group.description() != null) rebuilt.description(group.description());
            groups.add(rebuilt.build());
        }
        ConfigCategory.Builder rebuilt =
                ConfigCategory.createBuilder().name(category.name()).options(root);
        if (!groups.isEmpty()) rebuilt.groups(groups);
        return rebuilt.build();
    }

    /**
     * Rebuilds a category with only the options the current mode and query leave visible, keeping the option
     * order, the group order and a group's collapsed flag. Returns null when nothing is left to show.
     */
    static ConfigCategory filtered(ConfigCategory category, String searchQuery, boolean advancedOptions) {
        if (searchQuery.isBlank() && advancedOptions) return category;
        List<Option<?>> root = new ArrayList<>();
        List<OptionGroup> groups = new ArrayList<>();
        for (OptionGroup group : category.groups()) {
            List<Option<?>> kept = new ArrayList<>();
            for (Option<?> option : group.options()) {
                if (visible(option, searchQuery, advancedOptions)) kept.add(option);
            }
            if (kept.isEmpty()) continue;
            if (group.isRoot()) {
                root.addAll(kept);
                continue;
            }
            OptionGroup.Builder narrowed =
                    OptionGroup.createBuilder().name(group.name()).options(kept).collapsed(group.collapsed());
            if (group.description() != null) narrowed.description(group.description());
            groups.add(narrowed.build());
        }
        if (root.isEmpty() && groups.isEmpty()) return null;
        ConfigCategory.Builder narrowed =
                ConfigCategory.createBuilder().name(category.name()).options(root);
        // YACL rejects an empty group list, and a search that keeps only root options produces one.
        if (!groups.isEmpty()) narrowed.groups(groups);
        return narrowed.build();
    }

    /**
     * True when the option survives the current Basic/Advanced mode and query. Labels and buttons are named
     * with translation keys too, so they follow their category and match on their own words; the visible
     * label and the tooltip add the words a player actually reads.
     */
    static boolean visible(Option<?> option, String searchQuery, boolean advancedOptions) {
        return SettingsModel.visible(row(option), searchQuery, advancedOptions);
    }

    /** The screen adapter: a YACL widget and its translated texts become the pure row the model decides on. */
    static SettingsModel.Row row(Option<?> option) {
        Component name = option.name();
        String label = name == null ? "" : name.getString();
        return new SettingsModel.Row(translationKey(name), label, descriptionText(option), kindOf(option));
    }

    static SettingsModel.Kind kindOf(Option<?> option) {
        if (option instanceof LabelOption) return SettingsModel.Kind.LABEL;
        if (option instanceof ButtonOption) return SettingsModel.Kind.BUTTON;
        return SettingsModel.Kind.SETTING;
    }

    /** The tooltip is translated when the screen is built, so a search can reach its words too. */
    static String descriptionText(Option<?> option) {
        OptionDescription description = option.description();
        if (description == null || description.text() == null) return "";
        return description.text().getString();
    }

    static String translationKey(Component name) {
        return name != null && name.getContents() instanceof TranslatableContents text ? text.getKey() : null;
    }

    static void toast(Component message) {
        ClientToasts.show(message);
    }

    /**
     * Shows a progress toast once per 10% and returns the step reported, so the next call can throttle.
     * Called from the download's background thread; only that thread touches {@code lastStep}.
     */
    static int progressToast(String key, double fraction, int lastStep) {
        int percent = (int) (fraction * 100);
        int step = Math.min(10, percent / 10);
        if (step == lastStep) {
            return lastStep;
        }
        Minecraft.getInstance().execute(() -> toast(Component.translatable(key, percent)));
        return step;
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** URLs are pasted with surrounding whitespace; store them the way readiness and the URI builder see them. */
    static String trimmedToNull(String value) {
        String trimmed = value == null ? null : value.strip();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
