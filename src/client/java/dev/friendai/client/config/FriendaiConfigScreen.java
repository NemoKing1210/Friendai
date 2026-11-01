package dev.friendai.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.Blaze3D;
import dev.friendai.agent.llm.provider.LlmProfile;
import dev.friendai.agent.llm.provider.ProviderDocs;
import dev.friendai.agent.llm.provider.ProviderPreset;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.turn.ErrorMessages;
import dev.friendai.client.FriendaiClient;
import dev.friendai.client.audio.VoiceModelManager;
import dev.friendai.client.audio.WhisperModel;
import dev.friendai.client.tts.SpeechPlayback;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.ui.ClientToasts;
import dev.friendai.client.voice.MicCapture;
import dev.friendai.client.voice.SpeechToText;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.common.Components;
import dev.friendai.features.mining.MiningRuntimeSettings;
import dev.friendai.features.settings.SettingsModel;
import dev.friendai.features.speech.CloudField;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.ResolvedTts;
import dev.friendai.features.speech.VoiceErrors;
import dev.friendai.features.speech.VoicePreset;
import dev.friendai.features.speech.VoiceReadiness;
import dev.friendai.features.speech.stt.SttPresets;
import dev.friendai.features.speech.tts.TtsPresets;
import dev.friendai.features.speech.tts.VoiceModel;
import dev.friendai.features.speech.tts.VoiceModels;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.SecretResolver;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.config.SettingsSession;
import dev.friendai.infrastructure.config.VoiceConfig;
import dev.friendai.infrastructure.config.VoiceDraft;
import dev.friendai.llm.LlmClient;
import dev.friendai.llm.http.HttpTransport;
import dev.friendai.presentation.companion.ClientSessionId;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.gui.YACLScreen;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** The Friendai settings screen, built with YACL. Only loaded when YACL is installed. */
public final class FriendaiConfigScreen {
    private static final Set<String> AUTO_FETCHED = ConcurrentHashMap.newKeySet();

    /**
     * Models the connectivity test saw answering with text instead of calling the tool, by
     * "provider|model". Only a real request can tell, so the verdict is recorded when the player
     * tests the connection and stays next to the model option for the rest of the session.
     */
    private static final Map<String, UserMessage> TEXT_ONLY_MODELS = new ConcurrentHashMap<>();

    private static final List<String> WHISPER_MODELS = List.of("tiny", "base", "small");

    /** Export/import envelope version; a file that does not carry this number is refused. */
    private static final int TRANSFER_VERSION = 1;

    /** What an exported key looks like: the real value never leaves the machine. */
    private static final String MASK = "***";

    private static final String DEFAULT_TRANSFER_FILE = "friendai-settings-export.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Set while a rebuild writes pending widget values into the config, so listeners stay quiet. */
    private static boolean applying;

    private static SettingsScreenLifecycle lifecycle;

    /** The open settings session; a rebuild reuses it, a fresh open replaces and closes it. */
    private static SettingsSession session;

    private static long nextGeneration;
    private static long screenRevision;
    private static List<? extends Option<?>> pendingOptions = List.of();
    private static boolean draftChanged;
    private static boolean providerKeyRevealed;
    private static YACLScreen settingsScreen;
    private static Runnable pendingRebuild;

    /** True shows advanced options (keys, URLs, budgets, radii, internals); survives closing the screen. */
    private static boolean advancedOptions = true;

    /** Filter applied to the option lists. A fresh open clears it so a stale search never hides a tab. */
    private static String searchQuery = "";

    /** Name typed in the navigation tab; create, duplicate, rename and delete all read this field. */
    private static String profileName = "";

    /** Import/export target; relative paths resolve against the config directory. Kept across openings. */
    private static String transferPath = DEFAULT_TRANSFER_FILE;

    /** First line of the last validation report, shown next to the search field until a clean save. */
    private static String lastSaveProblem;

    /** Options that only drive the screen; changing them must not arm Save or count as an unsaved edit. */
    private static final List<Option<?>> UI_OPTIONS = new ArrayList<>();

    private FriendaiConfigScreen() {}

    private static Component tr(String key) {
        return Component.translatable(key);
    }

    /** One badge per category, so a reader can tell what a change affects before opening it. */
    private static Component scoped(Component name, String scopeKey) {
        return name.copy().append(Component.literal(" \u00b7 ")).append(tr(scopeKey));
    }

    public static Screen create(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        boolean serverEditable = mc.getSingleplayerServer() != null || mc.level == null;
        ClientConfigurationCoordinator configuration = FriendaiClient.runtime().configuration();
        ServerConfig server = null;
        boolean serverConfigReadOnly = false;
        if (serverEditable) {
            server = configuration.loadServer();
            serverConfigReadOnly = configuration.serverConfigReadOnly();
            if (serverConfigReadOnly) server = null;
        }
        if (session != null) session.close();
        pendingOptions = List.of();
        draftChanged = false;
        providerKeyRevealed = false;
        searchQuery = "";
        profileName = "";
        session = SettingsSession.open(++nextGeneration, FriendaiClient.clientConfig(), server, serverConfigReadOnly);
        lifecycle = new SettingsScreenLifecycle(session);
        return create(parent, false, session.voiceDraft());
    }

    /**
     * Builds the screen from the open session. Rebuilds carry widget drafts into the same session; nothing is
     * written to the live or persisted configuration until Save.
     */
    private static Screen create(Screen parent, boolean revealKey, VoiceDraft voiceDraft) {
        SettingsSession current = session;
        if (current == null || !current.isOpen()) return parent;
        applyPending();
        screenRevision++;
        pendingRebuild = null;
        providerKeyRevealed = revealKey;
        SettingsScreenLifecycle currentLifecycle = lifecycle;
        current.voiceDraft(VoiceDraft.from(current.clientDraft())
                .withSttReveal(voiceDraft.sttReveal())
                .withTtsReveal(voiceDraft.ttsReveal()));
        ClientConfigurationCoordinator configuration = FriendaiClient.runtime().configuration();
        ServerConfig server = current.serverDraft();
        boolean serverConfigReadOnly = current.serverReadOnly();
        boolean serverConfigProtected = configuration.serverConfigProtected();
        ClientConfig client = current.clientDraft();
        client.validate();
        VoiceDraft voice = current.voiceDraft();

        YetAnotherConfigLib.Builder builder =
                YetAnotherConfigLib.createBuilder().title(tr("friendai.config.title"));
        // The navigation tab is never filtered: it holds the search field, the Basic/Advanced switch,
        // profile management and import/export, so the way out of a filter is always one click away.
        Runnable reopen = () -> deferRebuild(() -> show(create(parent, revealKey, voiceDraft)));
        builder.category(navigationCategory(server, client, reopen, serverConfigProtected));
        List<ConfigCategory> categories = new ArrayList<>();
        if (server != null) {
            categories.add(providerCategory(parent, server, revealKey, voice));
            categories.add(companionCategory(server));
            categories.add(chatCategory(server));
            categories.add(planningCategory(server));
            categories.add(tasksCategory(server));
            categories.add(worldCategory(server));
            categories.add(tradingCategory(server));
            categories.add(resourcesCategory(server));
            categories.add(autonomyCategory(server));
            categories.add(memoryCategory(server));
            categories.add(miningCategory(server));
        } else {
            categories.add(ConfigCategory.createBuilder()
                    .name(scoped(tr("friendai.config.category.server"), "friendai.config.scope.server"))
                    .option(LabelOption.create(tr(
                            serverConfigReadOnly
                                    ? serverConfigProtected
                                            ? "friendai.config.server_protected"
                                            : "friendai.config.server_unsupported"
                                    : "friendai.config.server_only")))
                    .build());
        }
        categories.add(socialCategory(client));
        categories.add(voiceCategory(parent, client, voice));
        // "Reset everything" sits at the top of the first settings tab and targets the same option instances
        // the per-category buttons do, so both kinds of reset reach the draft and need a Save.
        List<Option<?>> resetAllTargets = new ArrayList<>();
        for (ConfigCategory category : categories) resetAllTargets.addAll(resetTargets(category));
        for (int index = 0; index < categories.size(); index++) {
            ConfigCategory category = categories.get(index);
            ConfigCategory prepared = index == 0
                    ? withResetAll(withReset(category, reopen), resetAllTargets, reopen)
                    : withReset(category, reopen);
            ConfigCategory narrowed = filtered(prepared);
            if (narrowed != null) builder.category(narrowed);
        }

        builder.save(() -> {
            current.voiceDraft(VoiceDraft.from(client));
            // Validate before publishing: validate() is also what clamps, and its first line is the only
            // honest way to say that a value was adjusted instead of dropping the change silently.
            lastSaveProblem = firstProblem(current);
            current.publish(configuration::save, configuration::saveServer);
            // YACL's Save button leaves the editor open. A fresh session permits another edit/save,
            // and makes subsequent Cancel/Undo return to the newly saved configuration.
            show(create(parent));
        });
        // The removal listener can only be attached once the screen is initialised: Fabric creates a screen's
        // event objects at the head of init(), including on return from a confirmation and on resize.
        // Compare event identity so every new event gets hooks without duplicating hooks on the same event.
        Object[] hooked = {null};
        builder.screenInit(screen -> {
            var removeEvent = ScreenEvents.remove(screen);
            if (hooked[0] == removeEvent) return;
            hooked[0] = removeEvent;
            removeEvent.register(removed -> {
                // YACL replaces this screen with PopupControllerScreen while a dropdown is open.
                // Its popup flag is already set when removal fires; this is not an editing-session exit.
                if (session != current) return;
                currentLifecycle.removed(screen.popupControllerVisible);
                // A real exit (not a rebuild and not a dropdown popup) also drops the stale save report.
                if (!current.isOpen()) lastSaveProblem = null;
            });
            ScreenEvents.afterTick(screen).register(ticked -> {
                if (session == current && Minecraft.getInstance().gui.screen() == screen && pendingRebuild != null) {
                    Runnable refresh = pendingRebuild;
                    pendingRebuild = null;
                    deferRebuild(refresh);
                }
            });
        });
        YetAnotherConfigLib config = builder.build();
        pendingOptions = config.categories().stream()
                .flatMap(category -> category.groups().stream())
                .flatMap(group -> group.options().stream())
                .toList();
        settingsScreen = new YACLScreen(config, parent) {
            @Override
            public boolean pendingChanges() {
                return super.pendingChanges() || session == current && draftChanged;
            }

            @Override
            public void finishOrSave() {
                if (pendingChanges()) {
                    applyPending();
                    config.saveFunction().run();
                } else {
                    onClose();
                }
            }

            @Override
            public void undo() {
                show(create(parent));
            }
        };
        return settingsScreen;
    }

    /** Replaces the screen as a rebuild, so the outgoing screen's removal keeps the session open. */
    private static void show(Screen next) {
        Screen previous = Minecraft.getInstance().gui.screen();
        Component tab = previous instanceof YACLScreen screen && screen.tabManager.getCurrentTab() != null
                ? screen.tabManager.getCurrentTab().getTabTitle()
                : null;
        lifecycle.replace(() -> Minecraft.getInstance().setScreenAndShow(next));
        if (tab != null && next instanceof YACLScreen screen) {
            screen.tabNavigationBar.getTabs().stream()
                    .filter(candidate -> candidate.getTabTitle().equals(tab))
                    .findFirst()
                    .ifPresent(candidate -> screen.tabManager.setCurrentTab(candidate, false));
        }
    }

    /** Queue after YACL has finished closing a dropdown; execute() can run inline on the client thread. */
    private static void deferRebuild(Runnable rebuild) {
        if (applying) return;
        SettingsSession expected = session;
        long revision = screenRevision;
        YACLScreen expectedScreen = settingsScreen;
        Minecraft.getInstance().schedule(() -> {
            if (session != expected || !expected.isOpen() || screenRevision != revision) return;
            if (Minecraft.getInstance().gui.screen() == expectedScreen) {
                rebuild.run();
            } else {
                // A documentation confirmation or controller popup owns the UI until it returns.
                pendingRebuild = rebuild;
            }
        });
    }

    /**
     * The navigation tab, always first and never filtered: search, the Basic/Advanced switch, profile
     * management and import/export. Filters live here so clearing one is always a single click away.
     */
    private static ConfigCategory navigationCategory(
            ServerConfig server, ClientConfig client, Runnable reopen, boolean protectedFile) {
        UI_OPTIONS.clear();
        List<Option<?>> find = new ArrayList<>();
        Option<String> search = Option.<String>createBuilder()
                .name(tr("friendai.config.nav.search"))
                .description(OptionDescription.of(tr("friendai.config.nav.search.tooltip")))
                .binding("", () -> searchQuery, value -> searchQuery = value == null ? "" : value.strip())
                .controller(StringControllerBuilder::create)
                .build();
        // The controller commits on Enter or on losing focus, so one committed query costs one rebuild.
        // An instant controller would rebuild per keystroke and steal the caret between characters.
        search.addListener((option, value) -> {
            if (!applying) reopen.run();
        });
        find.add(search);
        UI_OPTIONS.add(search);
        Option<Boolean> advanced = Option.<Boolean>createBuilder()
                .name(tr("friendai.config.nav.advanced"))
                .description(OptionDescription.of(tr("friendai.config.nav.advanced.tooltip")))
                .binding(true, () -> advancedOptions, value -> advancedOptions = value)
                .controller(BooleanControllerBuilder::create)
                .build();
        advanced.addListener((option, value) -> {
            if (!applying) reopen.run();
        });
        find.add(advanced);
        UI_OPTIONS.add(advanced);
        if (protectedFile) {
            // The file was damaged and the last good backup is in use; saving replaces the damaged file.
            find.add(LabelOption.create(Component.translatable("friendai.config.nav.recovered")));
        }
        if (lastSaveProblem != null) {
            find.add(LabelOption.create(
                    Component.translatable("friendai.config.nav.save_problem", Component.literal(lastSaveProblem))));
        }
        if (!searchQuery.isBlank() || !advancedOptions) {
            find.add(LabelOption.create(tr("friendai.config.nav.filter_note")));
        }
        ConfigCategory.Builder category = ConfigCategory.createBuilder()
                .name(tr("friendai.config.nav.title"))
                .options(find);
        if (server != null) category.group(profileGroup(server, reopen));
        category.group(transferGroup(server, client, reopen));
        return category.build();
    }

    /** Create, duplicate, rename and delete read the one name field above them. */
    private static OptionGroup profileGroup(ServerConfig server, Runnable reopen) {
        List<Option<?>> options = new ArrayList<>();
        Option<String> name = Option.<String>createBuilder()
                .name(tr("friendai.config.nav.profile_name"))
                .description(OptionDescription.of(tr("friendai.config.nav.profile_name.tooltip")))
                .binding("", () -> profileName, value -> profileName = value == null ? "" : value.strip())
                .controller(StringControllerBuilder::create)
                .build();
        options.add(name);
        UI_OPTIONS.add(name);
        options.add(actionButton("friendai.config.nav.profile_create", () -> createProfile(server, reopen)));
        options.add(actionButton("friendai.config.nav.profile_duplicate", () -> duplicateProfile(server, reopen)));
        options.add(actionButton("friendai.config.nav.profile_rename", () -> renameProfile(server, reopen)));
        options.add(actionButton("friendai.config.nav.profile_delete", () -> deleteProfile(server, reopen)));
        return OptionGroup.createBuilder()
                .name(tr("friendai.config.nav.profiles"))
                .options(options)
                .build();
    }

    /** Share a file between installs; secrets leave masked and masked secrets never overwrite a real key. */
    private static OptionGroup transferGroup(ServerConfig server, ClientConfig client, Runnable reopen) {
        Option<String> path = Option.<String>createBuilder()
                .name(tr("friendai.config.nav.transfer_path"))
                .description(OptionDescription.of(tr("friendai.config.nav.transfer_path.tooltip")))
                .binding(
                        DEFAULT_TRANSFER_FILE,
                        () -> transferPath,
                        value -> transferPath = value == null ? "" : value.strip())
                .controller(StringControllerBuilder::create)
                .build();
        UI_OPTIONS.add(path);
        return OptionGroup.createBuilder()
                .name(tr("friendai.config.nav.transfer"))
                .option(path)
                .option(actionButton("friendai.config.nav.export", () -> exportSettings(client, server)))
                .option(actionButton("friendai.config.nav.import", () -> importSettings(client, server, reopen)))
                .build();
    }

    private static ButtonOption actionButton(String nameKey, Runnable action) {
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
    private static ConfigCategory withReset(ConfigCategory category, Runnable reopen) {
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
    private static ConfigCategory withResetAll(ConfigCategory category, List<Option<?>> targets, Runnable reopen) {
        return withLeadingButton(category, resetButton("friendai.config.reset_all", targets, reopen));
    }

    /** Every option of the category that holds a setting: labels and buttons only drive the screen. */
    private static List<Option<?>> resetTargets(ConfigCategory category) {
        List<Option<?>> targets = new ArrayList<>();
        for (OptionGroup group : category.groups()) {
            for (Option<?> option : group.options()) {
                if (row(option).resetsOnCategoryReset()) targets.add(option);
            }
        }
        return targets;
    }

    private static ButtonOption resetButton(String nameKey, List<Option<?>> targets, Runnable reopen) {
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
    private static ConfigCategory withLeadingButton(ConfigCategory category, ButtonOption button) {
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
    private static ConfigCategory filtered(ConfigCategory category) {
        if (searchQuery.isBlank() && advancedOptions) return category;
        List<Option<?>> root = new ArrayList<>();
        List<OptionGroup> groups = new ArrayList<>();
        for (OptionGroup group : category.groups()) {
            List<Option<?>> kept = new ArrayList<>();
            for (Option<?> option : group.options()) {
                if (visible(option)) kept.add(option);
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
        return ConfigCategory.createBuilder()
                .name(category.name())
                .options(root)
                .groups(groups)
                .build();
    }

    /**
     * True when the option survives the current Basic/Advanced mode and query. Labels and buttons are named
     * with translation keys too, so they follow their category and match on their own words; the visible
     * label and the tooltip add the words a player actually reads.
     */
    private static boolean visible(Option<?> option) {
        return SettingsModel.visible(row(option), searchQuery, advancedOptions);
    }

    /** The screen adapter: a YACL widget and its translated texts become the pure row the model decides on. */
    private static SettingsModel.Row row(Option<?> option) {
        Component name = option.name();
        String label = name == null ? "" : name.getString();
        return new SettingsModel.Row(translationKey(name), label, descriptionText(option), kindOf(option));
    }

    private static SettingsModel.Kind kindOf(Option<?> option) {
        if (option instanceof LabelOption) return SettingsModel.Kind.LABEL;
        if (option instanceof ButtonOption) return SettingsModel.Kind.BUTTON;
        return SettingsModel.Kind.SETTING;
    }

    /** The tooltip is translated when the screen is built, so a search can reach its words too. */
    private static String descriptionText(Option<?> option) {
        OptionDescription description = option.description();
        if (description == null || description.text() == null) return "";
        return description.text().getString();
    }

    private static String translationKey(Component name) {
        return name != null && name.getContents() instanceof TranslatableContents text ? text.getKey() : null;
    }

    /** The first line of the server validation report, or null when the draft needed no adjustment. */
    private static String firstProblem(SettingsSession current) {
        ServerConfig draft = current.serverDraft();
        if (draft == null) return null;
        return SettingsModel.firstProblem(draft.validate());
    }

    private static void createProfile(ServerConfig server, Runnable reopen) {
        String name = profileName.strip();
        String problem = profileNameProblem(server, name);
        if (problem != null) {
            toast(Component.translatable(problem, name));
            return;
        }
        server.profiles.put(name, new ServerConfig.ProfileConfig());
        server.activeProfile = name;
        profileName = "";
        toast(Component.translatable("friendai.config.nav.profile_created", name));
        reopen.run();
    }

    private static void duplicateProfile(ServerConfig server, Runnable reopen) {
        String source = server.activeProfile;
        ServerConfig.ProfileConfig existing = server.profiles.get(source);
        if (existing == null) {
            toast(Component.translatable("friendai.config.nav.profile_unknown", source));
            return;
        }
        String name = profileName.strip().isEmpty() ? uniqueProfileName(server, source + "-copy") : profileName.strip();
        String problem = profileNameProblem(server, name);
        if (problem != null) {
            toast(Component.translatable(problem, name));
            return;
        }
        server.profiles.put(name, GSON.fromJson(GSON.toJson(existing), ServerConfig.ProfileConfig.class));
        server.activeProfile = name;
        profileName = "";
        toast(Component.translatable("friendai.config.nav.profile_duplicated", name));
        reopen.run();
    }

    private static void renameProfile(ServerConfig server, Runnable reopen) {
        String from = server.activeProfile;
        String name = profileName.strip();
        if (name.isEmpty()) {
            toast(Component.translatable("friendai.config.nav.profile_name_required"));
            return;
        }
        if (!server.profiles.containsKey(from)) {
            toast(Component.translatable("friendai.config.nav.profile_unknown", from));
            return;
        }
        if (name.equals(from)) {
            toast(Component.translatable("friendai.config.nav.profile_same", name));
            return;
        }
        String problem = profileNameProblem(server, name);
        if (problem != null) {
            toast(Component.translatable(problem, name));
            return;
        }
        // Keep the drop-down order: the renamed profile takes the old slot instead of moving to the end.
        Map<String, ServerConfig.ProfileConfig> renamed = new LinkedHashMap<>();
        server.profiles.forEach((key, profile) -> renamed.put(key.equals(from) ? name : key, profile));
        server.profiles = renamed;
        server.activeProfile = name;
        if (from.equals(server.agent.planner)) server.agent.planner = name;
        profileName = "";
        toast(Component.translatable("friendai.config.nav.profile_renamed", name));
        reopen.run();
    }

    private static void deleteProfile(ServerConfig server, Runnable reopen) {
        String name = profileName.strip();
        if (name.isEmpty()) {
            toast(Component.translatable("friendai.config.nav.profile_name_required"));
            return;
        }
        if (!server.profiles.containsKey(name)) {
            toast(Component.translatable("friendai.config.nav.profile_unknown", name));
            return;
        }
        if (server.profiles.size() <= 1) {
            toast(Component.translatable("friendai.config.nav.profile_last"));
            return;
        }
        if (name.equals(server.activeProfile)) {
            toast(Component.translatable("friendai.config.nav.profile_in_use", name));
            return;
        }
        server.profiles.remove(name);
        // The planner must not point at a profile that no longer exists.
        if (name.equals(server.agent.planner)) server.agent.planner = null;
        profileName = "";
        toast(Component.translatable("friendai.config.nav.profile_deleted", name));
        reopen.run();
    }

    /** Null when the name may be used, otherwise the translation key of the refusal. */
    private static String profileNameProblem(ServerConfig server, String name) {
        if (name.isEmpty()) return "friendai.config.nav.profile_name_required";
        if (name.length() > 32) return "friendai.config.nav.profile_too_long";
        if (server.profiles.containsKey(name)) return "friendai.config.nav.profile_exists";
        return null;
    }

    private static String uniqueProfileName(ServerConfig server, String base) {
        if (!server.profiles.containsKey(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + "-" + i;
            if (!server.profiles.containsKey(candidate)) return candidate;
        }
    }

    /**
     * Writes both drafts to {@link #transferPath} as {@code {"friendai": 1, "client": {...}, "server": {...}}}
     * with every {@code apiKey} replaced by {@value #MASK}. No new dependency: plain Gson over the drafts.
     */
    private static void exportSettings(ClientConfig client, ServerConfig server) {
        Path file;
        try {
            file = transferFile();
        } catch (RuntimeException error) {
            toast(Component.translatable("friendai.config.nav.transfer_failed", message(error)));
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("friendai", TRANSFER_VERSION);
        JsonElement clientTree = GSON.toJsonTree(client);
        maskKeys(clientTree);
        root.add("client", clientTree);
        if (server != null) {
            JsonElement serverTree = GSON.toJsonTree(server);
            maskKeys(serverTree);
            root.add("server", serverTree);
        }
        try {
            Path directory = file.getParent();
            if (directory != null) Files.createDirectories(directory);
            Files.writeString(file, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
            toast(Component.translatable("friendai.config.nav.exported", file.toString()));
        } catch (IOException | RuntimeException error) {
            toast(Component.translatable("friendai.config.nav.transfer_failed", message(error)));
        }
    }

    /**
     * Replaces both drafts with the file's contents so the player can review them before Save. Refuses a file
     * without this build's envelope version, and refuses to write a masked key over a real one.
     */
    private static void importSettings(ClientConfig client, ServerConfig server, Runnable reopen) {
        Path file;
        try {
            file = transferFile();
        } catch (RuntimeException error) {
            toast(Component.translatable("friendai.config.nav.transfer_failed", message(error)));
            return;
        }
        if (!Files.isRegularFile(file)) {
            toast(Component.translatable("friendai.config.nav.import_missing", file.toString()));
            return;
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                toast(Component.translatable("friendai.config.nav.import_invalid", file.toString()));
                return;
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            toast(Component.translatable("friendai.config.nav.import_invalid", file.toString()));
            return;
        }
        JsonElement version = root.get("friendai");
        if (version == null
                || !version.isJsonPrimitive()
                || !version.getAsJsonPrimitive().isNumber()) {
            toast(Component.translatable("friendai.config.nav.import_invalid", file.toString()));
            return;
        }
        int found = version.getAsInt();
        if (found != TRANSFER_VERSION) {
            toast(Component.translatable("friendai.config.nav.import_version", found, TRANSFER_VERSION));
            return;
        }
        JsonElement clientTree = root.get("client");
        if (clientTree == null || !clientTree.isJsonObject()) {
            toast(Component.translatable("friendai.config.nav.import_invalid", file.toString()));
            return;
        }
        ClientConfig importedClient = GSON.fromJson(clientTree, ClientConfig.class);
        if (importedClient == null) {
            toast(Component.translatable("friendai.config.nav.import_invalid", file.toString()));
            return;
        }
        restoreCloudKeys(importedClient, client);
        client.voice = importedClient.voice;
        if (importedClient.social != null) client.social = importedClient.social;
        List<String> problems = new ArrayList<>();
        client.validate();
        JsonElement serverTree = root.get("server");
        if (server != null && serverTree != null && serverTree.isJsonObject()) {
            ServerConfig importedServer = GSON.fromJson(serverTree, ServerConfig.class);
            if (importedServer != null) {
                restoreProfileKeys(importedServer, server);
                applyServerDraft(server, importedServer);
                problems.addAll(server.validate());
            }
        }
        lastSaveProblem = problems.isEmpty() ? null : problems.getFirst();
        toast(Component.translatable("friendai.config.nav.imported", file.toString()));
        reopen.run();
    }

    private static Path transferFile() {
        String value = transferPath == null || transferPath.isBlank() ? DEFAULT_TRANSFER_FILE : transferPath.strip();
        Path file = Path.of(value);
        if (file.isAbsolute()) return file;
        return FriendaiClient.runtime().configuration().configDirectory().resolve(file);
    }

    /** Replaces every {@code apiKey} member anywhere in the tree with the mask. */
    private static void maskKeys(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) maskKeys(child);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        for (String name : List.copyOf(object.keySet())) {
            JsonElement value = object.get(name);
            if ("apiKey".equals(name) && value != null && value.isJsonPrimitive()) {
                object.addProperty(name, MASK);
            } else {
                maskKeys(value);
            }
        }
    }

    /** A masked key means "unchanged": the provider entry keeps the key this install already has. */
    private static void restoreCloudKeys(ClientConfig imported, ClientConfig current) {
        if (imported.voice == null) return;
        restoreCloudKeys(imported.voice.stt == null ? null : imported.voice.stt.cloud, current.voice.stt.cloud);
        restoreCloudKeys(imported.voice.tts == null ? null : imported.voice.tts.cloud, current.voice.tts.cloud);
    }

    private static void restoreCloudKeys(
            Map<String, ClientConfig.CloudSettings> imported, Map<String, ClientConfig.CloudSettings> current) {
        if (imported == null) return;
        imported.forEach((id, settings) -> {
            if (settings == null || !MASK.equals(settings.apiKey)) return;
            ClientConfig.CloudSettings existing = current == null ? null : current.get(id);
            settings.apiKey = existing == null ? null : existing.apiKey;
        });
    }

    private static void restoreProfileKeys(ServerConfig imported, ServerConfig current) {
        if (imported.profiles == null) return;
        imported.profiles.forEach((id, profile) -> {
            if (!MASK.equals(profile.apiKey)) return;
            ServerConfig.ProfileConfig existing = current.profiles.get(id);
            profile.apiKey = existing == null ? null : existing.apiKey;
        });
    }

    /** Replaces each section of the live draft; the widgets read the same objects after the rebuild. */
    private static void applyServerDraft(ServerConfig draft, ServerConfig imported) {
        if (imported.profiles != null && !imported.profiles.isEmpty()) {
            draft.profiles = new LinkedHashMap<>(imported.profiles);
            draft.activeProfile = draft.profiles.containsKey(imported.activeProfile)
                    ? imported.activeProfile
                    : draft.profiles.keySet().iterator().next();
        }
        if (imported.companion != null) draft.companion = imported.companion;
        if (imported.chat != null) draft.chat = imported.chat;
        if (imported.brain != null) draft.brain = imported.brain;
        if (imported.tasks != null) draft.tasks = imported.tasks;
        if (imported.memory != null) draft.memory = imported.memory;
        if (imported.pathing != null) draft.pathing = imported.pathing;
        if (imported.mining != null) draft.mining = imported.mining;
        if (imported.places != null) draft.places = imported.places;
        if (imported.structures != null) draft.structures = imported.structures;
        if (imported.agent != null) draft.agent = imported.agent;
        if (imported.autonomy != null) draft.autonomy = imported.autonomy;
        if (imported.skills != null) draft.skills = imported.skills;
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    private static Option<String> stringOption(
            String nameKey,
            String descriptionKey,
            java.util.function.Supplier<String> getter,
            String def,
            java.util.function.Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(tr(nameKey))
                .description(OptionDescription.of(tr(descriptionKey)))
                .binding(def, getter, setter)
                .controller(StringControllerBuilder::create)
                .build();
    }

    private static ConfigCategory providerCategory(
            Screen parent, ServerConfig server, boolean revealKey, VoiceDraft voiceDraft) {
        ServerConfig.ProfileConfig p =
                server.profiles.computeIfAbsent(server.activeProfile, k -> new ServerConfig.ProfileConfig());
        ProviderRegistry registry = ProviderRegistry.withBuiltins(
                new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(30), 1, Duration.ofMillis(300)));
        List<String> providerIds =
                registry.presets().stream().map(ProviderPreset::id).toList();

        Option<String> profileOpt = Option.<String>createBuilder()
                .name(tr("friendai.config.profile"))
                .description(OptionDescription.of(tr("friendai.config.profile.tooltip")))
                .binding(
                        server.profiles.containsKey("main") ? "main" : server.activeProfile,
                        () -> server.activeProfile,
                        v -> server.activeProfile = v)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(new ArrayList<>(server.profiles.keySet()))
                        .allowAnyValue(false))
                .build();
        Option<String> providerOpt = Option.<String>createBuilder()
                .name(tr("friendai.config.provider"))
                .description(OptionDescription.of(tr("friendai.config.provider.tooltip")))
                .binding(new ServerConfig.ProfileConfig().provider, () -> p.provider, v -> p.provider = v)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(providerIds)
                        .allowAnyValue(false))
                .build();
        String statusKey;
        if (registry.preset(p.provider)
                .map(preset -> preset.authStyle() == dev.friendai.agent.llm.provider.AuthStyle.NONE)
                .orElse(false)) {
            statusKey = "friendai.config.status.local";
        } else if (p.apiKey == null || p.apiKey.isBlank()) {
            statusKey = "friendai.config.status.no_key";
        } else if (p.model == null || p.model.isBlank()) {
            statusKey = "friendai.config.status.no_model";
        } else {
            statusKey = "friendai.config.status.ready";
        }
        Option<String> baseUrlOpt = Option.<String>createBuilder()
                .name(tr("friendai.config.base_url"))
                .description(OptionDescription.of(tr("friendai.config.base_url.tooltip")))
                .binding("", () -> p.baseUrl == null ? "" : p.baseUrl, v -> p.baseUrl = v.isBlank() ? null : v.strip())
                .controller(StringControllerBuilder::create)
                .build();
        Option<String> apiKeyOpt = Option.<String>createBuilder()
                .name(tr("friendai.config.api_key"))
                .description(OptionDescription.of(tr("friendai.config.api_key.tooltip")))
                .binding("", () -> SecretResolver.display(p.apiKey, revealKey), v -> {
                    // The field may show a placeholder; leaving it untouched must not overwrite the real key.
                    if (!v.equals(SecretResolver.display(p.apiKey, revealKey))) {
                        p.apiKey = v;
                    }
                })
                .controller(StringControllerBuilder::create)
                .build();
        Option<String> modelOpt = Option.<String>createBuilder()
                .name(tr("friendai.config.model"))
                .description(OptionDescription.of(tr("friendai.config.model.tooltip")))
                .binding(new ServerConfig.ProfileConfig().model, () -> p.model, v -> p.model = v)
                .customController(o -> new SuggestedStringController(o, ModelCache.get(p.provider)))
                .build();
        Option<Double> temperatureOpt = Option.<Double>createBuilder()
                .name(tr("friendai.config.temperature"))
                .description(OptionDescription.of(tr("friendai.config.temperature.tooltip")))
                .binding(0.7, () -> p.temperature == null ? 0.7 : p.temperature, v -> p.temperature = v)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(0.0, 2.0))
                .build();
        Option<Integer> maxTokensOpt = Option.<Integer>createBuilder()
                .name(tr("friendai.config.max_tokens"))
                .description(OptionDescription.of(tr("friendai.config.max_tokens.tooltip")))
                .binding(8192, () -> p.maxTokens, v -> p.maxTokens = v)
                .controller(o -> IntegerFieldControllerBuilder.create(o).range(16, 65536))
                .build();

        java.util.function.Supplier<String> pendingKeyValue = () -> {
            String pendingKey = apiKeyOpt.pendingValue();
            return pendingKey.equals(SecretResolver.display(p.apiKey, revealKey)) ? p.apiKey : pendingKey;
        };
        java.util.function.Supplier<LlmClient> pendingClient = () -> {
            String key = pendingKeyValue.get();
            String base = baseUrlOpt.pendingValue();
            LlmProfile profile = new LlmProfile(
                    providerOpt.pendingValue(),
                    modelOpt.pendingValue(),
                    SecretResolver.resolve(key, System::getenv),
                    base.isBlank() ? null : base.strip(),
                    p.routing,
                    temperatureOpt.pendingValue(),
                    maxTokensOpt.pendingValue());
            return new LlmClient(registry, profile);
        };

        // Fetches the model list for the pending settings, then rebuilds the screen keeping them.
        long generation = session == null ? -1 : session.generation();
        long revision = screenRevision;
        Runnable loadModelsThenReopen = () -> {
            String requestedProvider = providerOpt.pendingValue();
            pendingClient
                    .get()
                    .listModels()
                    .whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
                        if (session == null || !session.current(generation) || screenRevision != revision) return;
                        if (error != null) {
                            toast(Components.of(ErrorMessages.forUser(error)));
                            return;
                        }
                        ModelCache.put(requestedProvider, models);
                        toast(Component.translatable("friendai.config.models_loaded", models.size()));
                        deferRebuild(() -> show(create(parent, revealKey, voiceDraft)));
                    }));
        };

        // Switch immediately to this provider's cached list, even when it has no key or is offline.
        // Fetching fresh models remains an explicit button action using the pending credentials.
        providerOpt.addListener((opt, newProvider) -> deferRebuild(() -> show(create(parent, revealKey, voiceDraft))));
        profileOpt.addListener((opt, newProfile) -> deferRebuild(() -> show(create(parent, false, voiceDraft))));

        ButtonOption loadModels = ButtonOption.createBuilder()
                .name(tr("friendai.config.load_models"))
                .action((screen, button) -> loadModelsThenReopen.run())
                .build();
        ButtonOption toggleKey = ButtonOption.createBuilder()
                .name(tr(revealKey ? "friendai.config.api_key.hide" : "friendai.config.api_key.show"))
                .action((screen, button) -> deferRebuild(() -> show(create(parent, !revealKey, voiceDraft))))
                .build();
        ButtonOption getKey = ButtonOption.createBuilder()
                .name(tr("friendai.config.get_key"))
                .available(ProviderDocs.url(providerOpt.pendingValue()).isPresent())
                .action((screen, button) ->
                        ProviderDocs.url(providerOpt.pendingValue()).ifPresent(url -> Blaze3D.openUri(URI.create(url))))
                .build();
        ButtonOption testConnection = ButtonOption.createBuilder()
                .name(tr("friendai.config.test_connection"))
                .action((screen, button) -> pendingClient
                        .get()
                        .testConnection()
                        .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                            if (session == null || !session.current(generation) || screenRevision != revision) return;
                            if (error != null) {
                                toast(Components.of(ErrorMessages.forUser(error)));
                                return;
                            }
                            // The runtime's own verdict decides whether the model can call a tool;
                            // keep it so the model option carries the reason after the toast is gone.
                            rememberToolVerdict(providerOpt.pendingValue(), modelOpt.pendingValue(), result);
                            toast(Components.of(result));
                            deferRebuild(() -> show(create(parent, revealKey, voiceDraft)));
                        })))
                .build();

        String requestedProvider = providerOpt.pendingValue();
        String autoKey = requestedProvider + "|" + (p.baseUrl == null ? "" : p.baseUrl) + "|"
                + Integer.toHexString(String.valueOf(p.apiKey).hashCode());
        if (!"openai_compat".equals(requestedProvider)
                && ModelCache.get(requestedProvider).isEmpty()
                && p.apiKey != null
                && !p.apiKey.isBlank()
                && registry.preset(requestedProvider)
                        .filter(preset -> preset.authStyle() != dev.friendai.agent.llm.provider.AuthStyle.NONE)
                        .isPresent()
                && AUTO_FETCHED.add(autoKey)) {
            pendingClient
                    .get()
                    .listModels()
                    .whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
                        if (error != null) {
                            return;
                        }
                        ModelCache.put(requestedProvider, models);
                        if (session != null && session.current(generation) && screenRevision == revision) {
                            deferRebuild(() -> show(create(parent, revealKey, voiceDraft)));
                        }
                    }));
        }
        OptionGroup advanced = OptionGroup.createBuilder()
                .name(scoped(tr("friendai.config.category.advanced"), "friendai.config.scope.server"))
                .collapsed(true)
                .option(baseUrlOpt)
                .build();
        // The brain offers tools on every acting turn, so a model that answers text instead of a
        // tool call breaks the companion's action layer; a zero tool-step budget makes a turn chat
        // only, and then the verdict does not matter.
        String toolKey = server.brain.maxToolStepsPerTurn > 0 ? toolVerdictKey(p.provider, p.model) : null;
        UserMessage toolNotice = toolKey == null ? null : TEXT_ONLY_MODELS.get(toolKey);
        ConfigCategory.Builder provider = ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.provider"), "friendai.config.scope.server"))
                .option(profileOpt)
                .option(providerOpt)
                .option(LabelOption.create(
                        Component.translatable("friendai.config.status.line", Component.translatable(statusKey))))
                .option(apiKeyOpt)
                .option(toggleKey)
                .option(getKey)
                .option(modelOpt);
        if (toolNotice != null) {
            // The exact message the runtime gives a text-only model, so the warning needs no second wording.
            provider.option(LabelOption.create(
                    Component.translatable("friendai.config.status.line", Components.of(toolNotice))));
        }
        provider.option(loadModels)
                .option(temperatureOpt)
                .option(maxTokensOpt)
                .option(testConnection)
                .group(advanced);
        return provider.build();
    }

    private static ConfigCategory companionCategory(ServerConfig server) {
        var c = server.companion;
        List<String> plannerProfiles = new ArrayList<>();
        plannerProfiles.add("");
        plannerProfiles.addAll(server.profiles.keySet());
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.companion"), "friendai.config.scope.server"))
                .option(stringOption(
                        "friendai.config.companion.name",
                        "friendai.config.companion.name.tooltip",
                        () -> c.name,
                        "Friendai",
                        v -> c.name = v.isBlank() ? "Friendai" : v.strip()))
                .option(stringOption(
                        "friendai.config.companion.description",
                        "friendai.config.companion.description.tooltip",
                        () -> c.description,
                        new ServerConfig.CompanionSection().description,
                        v -> c.description = v))
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.companion.language"))
                        .description(OptionDescription.of(tr("friendai.config.companion.language.tooltip")))
                        .binding("auto", () -> c.language, v -> c.language = v.isBlank() ? "auto" : v.strip())
                        .controller(StringControllerBuilder::create)
                        .build())
                .option(booleanOption(
                        "friendai.config.skills.enabled",
                        "friendai.config.skills.enabled.tooltip",
                        true,
                        () -> server.skills.enabled,
                        v -> server.skills.enabled = v))
                .option(booleanOption(
                        "friendai.config.skills.approval",
                        "friendai.config.skills.approval.tooltip",
                        true,
                        () -> server.skills.requireApproval,
                        v -> server.skills.requireApproval = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.budget"))
                        .description(OptionDescription.of(tr("friendai.config.skills.budget.tooltip")))
                        .binding(4096, () -> server.skills.promptBudget, v -> server.skills.promptBudget = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(200, 6000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.hourly"))
                        .description(OptionDescription.of(tr("friendai.config.skills.hourly.tooltip")))
                        .binding(6, () -> server.skills.modelWritesPerHour, v -> server.skills.modelWritesPerHour = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(0, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.per_outcome"))
                        .description(OptionDescription.of(tr("friendai.config.skills.per_outcome.tooltip")))
                        .binding(2, () -> server.skills.writesPerOutcome, v -> server.skills.writesPerOutcome = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(0, 2))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.owner_count"))
                        .description(OptionDescription.of(tr("friendai.config.skills.owner_count.tooltip")))
                        .binding(64, () -> server.skills.ownerCount, v -> server.skills.ownerCount = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(1, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.server_count"))
                        .description(OptionDescription.of(tr("friendai.config.skills.server_count.tooltip")))
                        .binding(32, () -> server.skills.serverCount, v -> server.skills.serverCount = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(1, 32))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.total_chars"))
                        .description(OptionDescription.of(tr("friendai.config.skills.total_chars.tooltip")))
                        .binding(96_000, () -> server.skills.totalChars, v -> server.skills.totalChars = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(4_000, 96_000))
                        .build())
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.agent.planner"))
                        .description(OptionDescription.of(tr("friendai.config.agent.planner.tooltip")))
                        .binding(
                                "",
                                () -> server.agent.planner == null ? "" : server.agent.planner,
                                value -> server.agent.planner = value == null || value.isBlank() ? null : value.strip())
                        .controller(option -> DropdownStringControllerBuilder.create(option)
                                .values(plannerProfiles)
                                .allowAnyValue(false)
                                .allowEmptyValue(true))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.planner_retries"))
                        .description(OptionDescription.of(tr("friendai.config.agent.planner_retries.tooltip")))
                        .binding(2, () -> server.agent.plannerRetries, value -> server.agent.plannerRetries = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 4))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.place_interval"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.place_interval.tooltip")))
                        .binding(
                                4,
                                () -> server.tasks.placeIntervalTicks,
                                value -> server.tasks.placeIntervalTicks = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 40))
                        .build())
                .option(booleanOption(
                        "friendai.config.companion.chunk_loading",
                        "friendai.config.companion.chunk_loading.tooltip",
                        true,
                        () -> c.chunkLoading.enabled,
                        value -> c.chunkLoading.enabled = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.companion.chunk_radius"))
                        .description(OptionDescription.of(tr("friendai.config.companion.chunk_radius.tooltip")))
                        .binding(1, () -> c.chunkLoading.radius, value -> c.chunkLoading.radius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 4))
                        .build())
                .build();
    }

    private static ConfigCategory chatCategory(ServerConfig server) {
        var c = server.chat;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.chat"), "friendai.config.scope.server"))
                .option(stringOption(
                        "friendai.config.chat.prefix",
                        "friendai.config.chat.prefix.tooltip",
                        () -> c.prefix,
                        "@",
                        v -> c.prefix = v))
                .option(booleanOption(
                        "friendai.config.chat.respond_to_name",
                        "friendai.config.chat.respond_to_name.tooltip",
                        true,
                        () -> c.respondToName,
                        v -> c.respondToName = v))
                .option(booleanOption(
                        "friendai.config.chat.listen_all",
                        "friendai.config.chat.listen_all.tooltip",
                        false,
                        () -> c.listenAll,
                        v -> c.listenAll = v))
                .option(booleanOption(
                        "friendai.config.chat.guests_enabled",
                        "friendai.config.chat.guests_enabled.tooltip",
                        true,
                        () -> c.guestsEnabled,
                        v -> c.guestsEnabled = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.hearing_radius"))
                        .description(OptionDescription.of(tr("friendai.config.chat.hearing_radius.tooltip")))
                        .binding(64, () -> c.hearingRadius, v -> c.hearingRadius = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(4, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.max_reply_chars"))
                        .description(OptionDescription.of(tr("friendai.config.chat.max_reply_chars.tooltip")))
                        .binding(256, () -> c.maxReplyChars, v -> c.maxReplyChars = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(32, 256))
                        .build())
                .option(booleanOption(
                        "friendai.config.chat.ambient_speech",
                        "friendai.config.chat.ambient_speech.tooltip",
                        true,
                        () -> c.ambientSpeech,
                        v -> c.ambientSpeech = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.guest_cooldown_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.chat.guest_cooldown_seconds.tooltip")))
                        .binding(5, () -> c.guestCooldownMs / 1_000, v -> c.guestCooldownMs = v * 1_000)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(0, 60))
                        .build())
                .build();
    }

    private static ConfigCategory planningCategory(ServerConfig server) {
        var agent = server.agent;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.planning"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.agent.tool_routing",
                        "friendai.config.agent.tool_routing.tooltip",
                        true,
                        () -> agent.toolRouting,
                        value -> agent.toolRouting = value))
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.autonomy"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.tooltip")))
                        .binding("assistive", () -> agent.autonomy, value -> agent.autonomy = value)
                        .controller(option -> DropdownStringControllerBuilder.create(option)
                                .values(List.of("passive", "assistive", "autonomous"))
                                .allowAnyValue(false))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.max_replans"))
                        .description(OptionDescription.of(tr("friendai.config.agent.max_replans.tooltip")))
                        .binding(3, () -> agent.maxReplans, value -> agent.maxReplans = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 10))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.goal_tick_sec"))
                        .description(OptionDescription.of(tr("friendai.config.agent.goal_tick_sec.tooltip")))
                        .binding(30, () -> agent.goalTickSec, value -> agent.goalTickSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.prompt_budget"))
                        .description(OptionDescription.of(tr("friendai.config.agent.prompt_budget.tooltip")))
                        .binding(24_000, () -> agent.promptBudget, value -> agent.promptBudget = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 100_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.planner_timeout_sec"))
                        .description(OptionDescription.of(tr("friendai.config.agent.planner_timeout_sec.tooltip")))
                        .binding(60, () -> agent.plannerTimeoutSec, value -> agent.plannerTimeoutSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 300))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.max_tool_steps"))
                        .description(OptionDescription.of(tr("friendai.config.brain.max_tool_steps.tooltip")))
                        .binding(
                                8,
                                () -> server.brain.maxToolStepsPerTurn,
                                value -> server.brain.maxToolStepsPerTurn = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 32))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.history_messages"))
                        .description(OptionDescription.of(tr("friendai.config.brain.history_messages.tooltip")))
                        .binding(30, () -> server.brain.historyMessages, value -> server.brain.historyMessages = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(6, 200))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.request_timeout_sec"))
                        .description(OptionDescription.of(tr("friendai.config.brain.request_timeout_sec.tooltip")))
                        .binding(
                                60,
                                () -> server.brain.requestTimeoutSec,
                                value -> server.brain.requestTimeoutSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(5, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.cooldown_ms"))
                        .description(OptionDescription.of(tr("friendai.config.brain.cooldown_ms.tooltip")))
                        .binding(500, () -> server.brain.cooldownMs, value -> server.brain.cooldownMs = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 60_000))
                        .build())
                .build();
    }

    private static ConfigCategory tasksCategory(ServerConfig server) {
        var tasks = server.tasks;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.tasks"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.tasks.default_follow",
                        "friendai.config.tasks.default_follow.tooltip",
                        true,
                        () -> tasks.defaultFollow,
                        value -> tasks.defaultFollow = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.mine_search_radius"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.mine_search_radius.tooltip")))
                        .binding(32, () -> tasks.mineSearchRadius, value -> tasks.mineSearchRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.tree_search_radius"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.tree_search_radius.tooltip")))
                        .binding(32, () -> tasks.treeSearchRadius, value -> tasks.treeSearchRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.timeout_sec"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.timeout_sec.tooltip")))
                        .binding(300, () -> tasks.taskTimeoutSec, value -> tasks.taskTimeoutSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 3600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_max_jobs"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_max_jobs.tooltip")))
                        .binding(64, () -> tasks.queueMaxJobs, value -> tasks.queueMaxJobs = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 256))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_max_plans"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_max_plans.tooltip")))
                        .binding(8, () -> tasks.queueMaxPlans, value -> tasks.queueMaxPlans = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 32))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_max_steps"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_max_steps.tooltip")))
                        .binding(32, () -> tasks.queueMaxStepsPerAdd, value -> tasks.queueMaxStepsPerAdd = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(booleanOption(
                        "friendai.config.tasks.fell_collect",
                        "friendai.config.tasks.fell_collect.tooltip",
                        true,
                        () -> tasks.fellCollectDrops,
                        value -> tasks.fellCollectDrops = value))
                .option(booleanOption(
                        "friendai.config.tasks.mine_collect",
                        "friendai.config.tasks.mine_collect.tooltip",
                        true,
                        () -> tasks.mineCollectDrops,
                        value -> tasks.mineCollectDrops = value))
                .option(booleanOption(
                        "friendai.config.tasks.tree_perception",
                        "friendai.config.tasks.tree_perception.tooltip",
                        true,
                        () -> tasks.treePerception,
                        value -> tasks.treePerception = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.tree_perception_radius"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.tree_perception_radius.tooltip")))
                        .binding(12, () -> tasks.treePerceptionRadius, value -> tasks.treePerceptionRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(4, 24))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_blocked_notice_sec"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_blocked_notice_sec.tooltip")))
                        .binding(30, () -> tasks.queueBlockedNoticeSec, value -> tasks.queueBlockedNoticeSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(5, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.free_time_watchdog_sec"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.free_time_watchdog_sec.tooltip")))
                        .binding(120, () -> tasks.freeTimeWatchdogSec, value -> tasks.freeTimeWatchdogSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .build();
    }

    private static ConfigCategory worldCategory(ServerConfig server) {
        var pathing = server.pathing;
        var places = server.places;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.world"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.pathing.allow_break",
                        "friendai.config.pathing.allow_break.tooltip",
                        true,
                        () -> pathing.allowBreak,
                        value -> pathing.allowBreak = value))
                .option(booleanOption(
                        "friendai.config.pathing.allow_place",
                        "friendai.config.pathing.allow_place.tooltip",
                        true,
                        () -> pathing.allowPlace,
                        value -> pathing.allowPlace = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.pathing.search_budget_ms"))
                        .description(OptionDescription.of(tr("friendai.config.pathing.search_budget_ms.tooltip")))
                        .binding(2, () -> pathing.searchBudgetMs, value -> pathing.searchBudgetMs = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 50))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.pathing.max_gather"))
                        .description(OptionDescription.of(tr("friendai.config.pathing.max_gather.tooltip")))
                        .binding(64, () -> pathing.maxGatherBlocks, value -> pathing.maxGatherBlocks = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 256))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.pathing.max_nodes"))
                        .description(OptionDescription.of(tr("friendai.config.pathing.max_nodes.tooltip")))
                        .binding(20_000, () -> pathing.maxNodes, value -> pathing.maxNodes = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1_000, 100_000))
                        .build())
                .option(booleanOption(
                        "friendai.config.places.enabled",
                        "friendai.config.places.enabled.tooltip",
                        true,
                        () -> places.enabled,
                        value -> places.enabled = value))
                .option(booleanOption(
                        "friendai.config.places.auto_remember",
                        "friendai.config.places.auto_remember.tooltip",
                        true,
                        () -> places.autoRemember,
                        value -> places.autoRemember = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.max"))
                        .description(OptionDescription.of(tr("friendai.config.places.max.tooltip")))
                        .binding(128, () -> places.maxPlaces, value -> places.maxPlaces = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.auto_mine_min_blocks"))
                        .description(OptionDescription.of(tr("friendai.config.places.auto_mine_min_blocks.tooltip")))
                        .binding(16, () -> places.autoMineMinBlocks, value -> places.autoMineMinBlocks = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 256))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.merge_radius"))
                        .description(OptionDescription.of(tr("friendai.config.places.merge_radius.tooltip")))
                        .binding(16, () -> places.mergeRadius, value -> places.mergeRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.prompt_limit"))
                        .description(OptionDescription.of(tr("friendai.config.places.prompt_limit.tooltip")))
                        .binding(6, () -> places.promptLimit, value -> places.promptLimit = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 12))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.prompt_budget"))
                        .description(OptionDescription.of(tr("friendai.config.places.prompt_budget.tooltip")))
                        .binding(1_200, () -> places.promptBudget, value -> places.promptBudget = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(200, 4_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.planner_limit"))
                        .description(OptionDescription.of(tr("friendai.config.places.planner_limit.tooltip")))
                        .binding(40, () -> places.plannerLimit, value -> places.plannerLimit = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.structures.max_nearby"))
                        .description(OptionDescription.of(tr("friendai.config.structures.max_nearby.tooltip")))
                        .binding(
                                4,
                                () -> server.structures.maxNearbySpawned,
                                value -> server.structures.maxNearbySpawned = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 32))
                        .build())
                .build();
    }

    private static ConfigCategory tradingCategory(ServerConfig server) {
        var trading = server.tasks.trading;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.trading"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.trading.enabled",
                        "friendai.config.trading.enabled.tooltip",
                        true,
                        () -> trading.enabled,
                        value -> trading.enabled = value))
                .option(booleanOption(
                        "friendai.config.trading.idle_refresh",
                        "friendai.config.trading.idle_refresh.tooltip",
                        true,
                        () -> trading.idleRefresh,
                        value -> trading.idleRefresh = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.radius"))
                        .description(OptionDescription.of(tr("friendai.config.trading.radius.tooltip")))
                        .binding(64, () -> trading.observationRadius, value -> trading.observationRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(8, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.quote_fresh_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.quote_fresh_seconds.tooltip")))
                        .binding(
                                60,
                                () -> trading.quoteFreshTicks / 20,
                                value -> trading.quoteFreshTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 3600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.location_fresh_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.trading.location_fresh_minutes.tooltip")))
                        .binding(
                                10,
                                () -> trading.locationFreshTicks / 1_200,
                                value -> trading.locationFreshTicks = Math.max(1_200, value * 1_200))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.idle_refresh_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.idle_refresh_seconds.tooltip")))
                        .binding(
                                10,
                                () -> trading.idleRefreshIntervalTicks / 20,
                                value -> trading.idleRefreshIntervalTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.wait_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.wait_seconds.tooltip")))
                        .binding(
                                120,
                                () -> trading.waitTicks / 20,
                                value -> trading.waitTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.max_wait_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.max_wait_seconds.tooltip")))
                        .binding(
                                600,
                                () -> trading.maxWaitTicks / 20,
                                value -> trading.maxWaitTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.max_ingots"))
                        .description(OptionDescription.of(tr("friendai.config.trading.max_ingots.tooltip")))
                        .binding(64, () -> trading.maxBarterIngots, value -> trading.maxBarterIngots = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.default_ingots"))
                        .description(OptionDescription.of(tr("friendai.config.trading.default_ingots.tooltip")))
                        .binding(8, () -> trading.barterDefaultIngots, value -> trading.barterDefaultIngots = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 64))
                        .build())
                .build();
    }

    private static ConfigCategory resourcesCategory(ServerConfig server) {
        var resources = server.tasks.resources;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.resources"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.resources.expeditions",
                        "friendai.config.resources.expeditions.tooltip",
                        true,
                        () -> resources.allowExpeditions,
                        value -> resources.allowExpeditions = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.local_radius"))
                        .description(OptionDescription.of(tr("friendai.config.resources.local_radius.tooltip")))
                        .binding(64, () -> resources.localRadius, value -> resources.localRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(16, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.local_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.resources.local_seconds.tooltip")))
                        .binding(
                                120,
                                () -> resources.localTicks / 20,
                                value -> resources.localTicks = Math.max(200, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.expedition_radius"))
                        .description(OptionDescription.of(tr("friendai.config.resources.expedition_radius.tooltip")))
                        .binding(512, () -> resources.expeditionRadius, value -> resources.expeditionRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(16, 2048))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.expedition_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.resources.expedition_minutes.tooltip")))
                        .binding(
                                10,
                                () -> resources.expeditionTicks / 1_200,
                                value -> resources.expeditionTicks = Math.max(1_200, value * 1_200))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 40))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.observation_radius"))
                        .description(OptionDescription.of(tr("friendai.config.resources.observation_radius.tooltip")))
                        .binding(64, () -> resources.observationRadius, value -> resources.observationRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(4, 64))
                        .build())
                .option(booleanOption(
                        "friendai.config.resources.wait_for_owner",
                        "friendai.config.resources.wait_for_owner.tooltip",
                        true,
                        () -> resources.waitForOwner,
                        value -> resources.waitForOwner = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.owner_sight"))
                        .description(OptionDescription.of(tr("friendai.config.resources.owner_sight.tooltip")))
                        .binding(32, () -> resources.ownerSightRadius, value -> resources.ownerSightRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(8, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.owner_hold"))
                        .description(OptionDescription.of(tr("friendai.config.resources.owner_hold.tooltip")))
                        .binding(48, () -> resources.ownerHoldDistance, value -> resources.ownerHoldDistance = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(9, 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.owner_resume"))
                        .description(OptionDescription.of(tr("friendai.config.resources.owner_resume.tooltip")))
                        .binding(
                                24, () -> resources.ownerResumeDistance, value -> resources.ownerResumeDistance = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 511))
                        .build())
                .build();
    }

    private static ConfigCategory autonomyCategory(ServerConfig server) {
        var autonomy = server.autonomy;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.autonomy"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.autonomy.enabled",
                        "friendai.config.autonomy.enabled.tooltip",
                        true,
                        () -> autonomy.enabled,
                        value -> autonomy.enabled = value))
                .option(booleanOption(
                        "friendai.config.autonomy.idle_start",
                        "friendai.config.autonomy.idle_start.tooltip",
                        true,
                        () -> autonomy.idleStart,
                        value -> autonomy.idleStart = value))
                .option(booleanOption(
                        "friendai.config.autonomy.while_offline",
                        "friendai.config.autonomy.while_offline.tooltip",
                        true,
                        () -> autonomy.whileOffline,
                        value -> autonomy.whileOffline = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.resume_after"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.resume_after.tooltip")))
                        .binding(60, () -> autonomy.resumeAfterSec, value -> autonomy.resumeAfterSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 900))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.idle_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.idle_minutes.tooltip")))
                        .binding(5, () -> autonomy.idleMinutes, value -> autonomy.idleMinutes = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 240))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.shift_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.shift_minutes.tooltip")))
                        .binding(20, () -> autonomy.shiftMinutes, value -> autonomy.shiftMinutes = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 240))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.max_shifts"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.max_shifts.tooltip")))
                        .binding(6, () -> autonomy.maxShiftsPerHour, value -> autonomy.maxShiftsPerHour = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.offline_shifts"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.offline_shifts.tooltip")))
                        .binding(2, () -> autonomy.offlineShiftsPerHour, value -> autonomy.offlineShiftsPerHour = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.fail_cooldown"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.fail_cooldown.tooltip")))
                        .binding(10, () -> autonomy.failCooldownMin, value -> autonomy.failCooldownMin = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 240))
                        .build())
                .option(booleanOption(
                        "friendai.config.autonomy.protect_places",
                        "friendai.config.autonomy.protect_places.tooltip",
                        true,
                        () -> autonomy.protectPlaces,
                        value -> autonomy.protectPlaces = value))
                .option(booleanOption(
                        "friendai.config.autonomy.allow_building",
                        "friendai.config.autonomy.allow_building.tooltip",
                        true,
                        () -> autonomy.allowBuilding,
                        value -> autonomy.allowBuilding = value))
                .build();
    }

    private static ConfigCategory memoryCategory(ServerConfig server) {
        var m = server.memory;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.memory"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.memory.enabled",
                        "friendai.config.memory.enabled.tooltip",
                        true,
                        () -> m.enabled,
                        v -> m.enabled = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_fact_chars"))
                        .description(OptionDescription.of(tr("friendai.config.memory.max_fact_chars.tooltip")))
                        .binding(512, () -> m.maxFactChars, v -> m.maxFactChars = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(40, 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_facts_per_player"))
                        .description(OptionDescription.of(tr("friendai.config.memory.max_facts_per_player.tooltip")))
                        .binding(128, () -> m.maxFactsPerPlayer, v -> m.maxFactsPerPlayer = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_world_facts"))
                        .description(OptionDescription.of(tr("friendai.config.memory.max_world_facts.tooltip")))
                        .binding(128, () -> m.maxWorldFacts, v -> m.maxWorldFacts = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 200))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.transcript_entries"))
                        .description(OptionDescription.of(tr("friendai.config.memory.transcript_entries.tooltip")))
                        .binding(200, () -> m.transcriptMaxEntries, v -> m.transcriptMaxEntries = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(20, 2_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.transcript_chars"))
                        .description(OptionDescription.of(tr("friendai.config.memory.transcript_chars.tooltip")))
                        .binding(400, () -> m.transcriptMaxTextChars, v -> m.transcriptMaxTextChars = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(100, 2_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.transcript_retention_days"))
                        .description(
                                OptionDescription.of(tr("friendai.config.memory.transcript_retention_days.tooltip")))
                        .binding(7, () -> m.transcriptRetentionDays, v -> m.transcriptRetentionDays = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 90))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.compact_at_percent"))
                        .description(OptionDescription.of(tr("friendai.config.memory.compact_at_percent.tooltip")))
                        .binding(90, () -> m.compactAtPercent, v -> m.compactAtPercent = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(50, 100))
                        .build())
                .option(booleanOption(
                        "friendai.config.memory.compaction_enabled",
                        "friendai.config.memory.compaction_enabled.tooltip",
                        true,
                        () -> m.compactionEnabled,
                        v -> m.compactionEnabled = v))
                .build();
    }

    /** A valuable drop's protection: whether it is on, what counts as valuable and how many blocks it may cost. */
    private static ConfigCategory miningCategory(ServerConfig server) {
        ServerConfig.MiningSection m = server.mining;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.mining"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.mining.protect_drops",
                        "friendai.config.mining.protect_drops.tooltip",
                        true,
                        () -> m.protectDrops,
                        v -> m.protectDrops = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.mining.catch_blocks"))
                        .description(OptionDescription.of(tr("friendai.config.mining.catch_blocks.tooltip")))
                        .binding(9, () -> m.catchBlocks, v -> m.catchBlocks = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(0, 27))
                        .build())
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.mining.valuable_blocks"))
                        .description(OptionDescription.of(tr("friendai.config.mining.valuable_blocks.desc")))
                        .binding(
                                String.join(",", MiningRuntimeSettings.DEFAULT_VALUABLE),
                                () -> m.valuableBlocks == null ? "" : String.join(",", m.valuableBlocks),
                                v -> m.valuableBlocks = new java.util.ArrayList<>(java.util.Arrays.stream(v.split(","))
                                        .map(String::strip)
                                        .filter(entry -> !entry.isEmpty())
                                        .toList()))
                        .controller(StringControllerBuilder::create)
                        .build())
                .build();
    }

    private static ConfigCategory socialCategory(ClientConfig client) {
        var social = client.social;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.display"), "friendai.config.scope.client"))
                .option(booleanOption(
                        "friendai.config.social.outline",
                        "friendai.config.social.outline.tooltip",
                        true,
                        () -> social.outline,
                        value -> social.outline = value))
                .option(booleanOption(
                        "friendai.config.social.mood_mark",
                        "friendai.config.social.mood_mark.tooltip",
                        true,
                        () -> social.moodMark,
                        value -> social.moodMark = value))
                .build();
    }

    private static ConfigCategory voiceCategory(Screen parent, ClientConfig client, VoiceDraft draft) {
        ClientConfig.SttSection stt = client.voice.stt;
        ClientConfig.TtsSection tts = client.voice.tts;
        boolean sttReveal = draft.sttReveal();
        boolean ttsReveal = draft.ttsReveal();
        VoicePreset sttPreset = SttPresets.require(stt.provider);
        VoicePreset ttsPreset = TtsPresets.require(tts.provider);

        List<Option<?>> recognition = new ArrayList<>();
        List<Option<?>> synthesis = new ArrayList<>();

        Option<Boolean> sttEnabled = booleanOption(
                "friendai.config.voice.enabled",
                "friendai.config.voice.enabled.tooltip",
                true,
                () -> stt.enabled,
                value -> stt.enabled = value);
        Option<String> sttProvider = providerOption(
                "friendai.config.voice.stt.provider",
                SttPresets.LOCAL,
                SttPresets.ids(),
                () -> stt.provider,
                value -> stt.provider = value,
                FriendaiConfigScreen::sttProviderLabel);
        recognition.add(sttEnabled);
        recognition.add(sttProvider);
        sttProvider.addListener((option, value) -> {
            if (!applying) {
                reopen(parent, client, sttReveal, ttsReveal);
            }
        });

        if (sttPreset.local()) {
            recognition.addAll(localRecognitionOptions(parent, client, stt, sttReveal, ttsReveal));
        } else {
            ClientConfig.CloudSettings sttCloud = cloud(stt.cloud, sttPreset);
            recognition.addAll(
                    cloudOptions(sttPreset, sttCloud, sttReveal, () -> reopen(parent, client, !sttReveal, ttsReveal)));
            recognition.add(statusLabel(VoiceRuntime.sttStatus(stt)));
        }

        Option<String> sttLanguage = Option.<String>createBuilder()
                .name(tr("friendai.config.voice.language"))
                .description(OptionDescription.of(tr("friendai.config.voice.language.tooltip")))
                .binding("auto", () -> stt.language, value -> stt.language = value.isBlank() ? "auto" : value.strip())
                .controller(StringControllerBuilder::create)
                .build();
        Option<Integer> maxRecord = Option.<Integer>createBuilder()
                .name(tr("friendai.config.voice.max_record"))
                .description(OptionDescription.of(tr("friendai.config.voice.max_record.tooltip")))
                .binding(30, () -> stt.maxRecordSec, value -> stt.maxRecordSec = value)
                .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 120))
                .build();
        ButtonOption sttTest = ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.test"))
                .action((screen, button) -> testRecording(client))
                .build();
        recognition.add(sttLanguage);
        recognition.add(inputDeviceOption(stt));
        recognition.add(maxRecord);
        recognition.add(sttTest);

        Option<Boolean> ttsEnabled = booleanOption(
                "friendai.config.voice.tts.enabled",
                "friendai.config.voice.tts.enabled.tooltip",
                false,
                () -> tts.enabled,
                value -> tts.enabled = value);
        Option<String> ttsProvider = providerOption(
                "friendai.config.voice.tts.provider",
                TtsPresets.LOCAL,
                TtsPresets.ids(),
                () -> tts.provider,
                value -> tts.provider = value,
                FriendaiConfigScreen::ttsProviderLabel);
        synthesis.add(ttsEnabled);
        synthesis.add(ttsProvider);
        ttsProvider.addListener((option, value) -> {
            if (!applying) {
                reopen(parent, client, sttReveal, ttsReveal);
            }
        });

        if (ttsPreset.local()) {
            synthesis.addAll(localSynthesisOptions(parent, client, tts, sttReveal, ttsReveal));
        } else {
            ClientConfig.CloudSettings ttsCloud = cloud(tts.cloud, ttsPreset);
            synthesis.addAll(
                    cloudOptions(ttsPreset, ttsCloud, ttsReveal, () -> reopen(parent, client, sttReveal, !ttsReveal)));
            // A cloud voice without a key cannot speak; the runtime's own message names the field.
            synthesis.add(statusLabel(synthesisReadiness(tts), "friendai.config.status.ready"));
            synthesis.add(cloudSpeedOption(ttsCloud, ttsPreset.id()));
            if (ttsPreset.paid()) {
                synthesis.add(LabelOption.create(tr("friendai.config.voice.tts.paid_notice")));
            }
            if (ttsPreset.docsUrl() != null) {
                synthesis.add(docsButton(ttsPreset));
            }
        }

        Option<Double> volume = Option.<Double>createBuilder()
                .name(tr("friendai.config.voice.tts.volume"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.volume.tooltip")))
                .binding(1.0, () -> tts.volume, value -> tts.volume = value)
                .controller(o ->
                        DoubleSliderControllerBuilder.create(o).range(0.0, 1.0).step(0.05))
                .build();
        Option<Integer> maxChars = Option.<Integer>createBuilder()
                .name(tr("friendai.config.voice.tts.max_chars"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.max_chars.tooltip")))
                .binding(300, () -> tts.maxChars, value -> tts.maxChars = value)
                .controller(o -> IntegerFieldControllerBuilder.create(o).range(20, 500))
                .build();
        Option<Boolean> speakGuests = booleanOption(
                "friendai.config.voice.tts.speak_guests",
                "friendai.config.voice.tts.speak_guests.tooltip",
                false,
                () -> tts.speakGuests,
                value -> tts.speakGuests = value);
        ButtonOption testVoice = ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.tts.test"))
                .action((screen, button) -> {
                    applyPending();
                    SpeechRuntime.test(tts);
                })
                .build();
        Option<Boolean> spatial = Option.<Boolean>createBuilder()
                .name(tr("friendai.config.voice.tts.spatial"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.spatial.tooltip")))
                .binding(true, () -> tts.spatial, value -> tts.spatial = value)
                .controller(BooleanControllerBuilder::create)
                .build();
        synthesis.add(volume);
        synthesis.add(maxChars);
        synthesis.add(outputDeviceOption(tts));
        synthesis.add(spatial);
        synthesis.add(speakGuests);
        synthesis.add(testVoice);

        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.voice"), "friendai.config.scope.client"))
                .group(OptionGroup.createBuilder()
                        .name(tr("friendai.config.voice.group.recognition"))
                        .options(recognition)
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(tr("friendai.config.voice.group.synthesis"))
                        .options(synthesis)
                        .build())
                .build();
    }

    private static List<Option<?>> localRecognitionOptions(
            Screen parent, ClientConfig client, ClientConfig.SttSection stt, boolean sttReveal, boolean ttsReveal) {
        Option<String> model = Option.<String>createBuilder()
                .name(tr("friendai.config.voice.model"))
                .description(OptionDescription.of(tr("friendai.config.voice.model.tooltip")))
                .binding("base", () -> stt.local.model, value -> stt.local.model = value)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(WHISPER_MODELS)
                        .allowAnyValue(false))
                .build();
        // A missing model is reported with the runtime's own message, so recording fails with the
        // same words this row shows.
        LabelOption status =
                statusLabel(VoiceRuntime.sttStatus(stt).problem(), "friendai.config.voice.status.installed");
        ButtonOption download = ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.download"))
                .action((screen, button) -> {
                    WhisperModel selected = WhisperModel.byId(model.pendingValue());
                    int[] lastStep = {-1};
                    long generation = session == null ? -1 : session.generation();
                    VoiceRuntime.manager()
                            .download(selected.spec(), fraction -> {
                                int percent = (int) (fraction * 100);
                                if (percent / 10 != lastStep[0]) {
                                    lastStep[0] = percent / 10;
                                    Minecraft.getInstance().execute(() -> {
                                        if (session == null || !session.current(generation)) return;
                                        toast(Component.translatable("friendai.config.voice.downloading", percent));
                                    });
                                }
                            })
                            .whenComplete((path, error) -> Minecraft.getInstance()
                                    .execute(() -> {
                                        if (session == null || !session.current(generation)) return;
                                        if (error != null) {
                                            toast(Components.of(ErrorMessages.forUser(error)));
                                        } else {
                                            VoiceRuntime.invalidate();
                                            toast(tr("friendai.config.voice.status.installed"));
                                            reopen(parent, client, sttReveal, ttsReveal);
                                        }
                                    }));
                })
                .build();
        // The installed label and the download target follow the selection.
        model.addListener((option, value) -> {
            if (!applying) {
                reopen(parent, client, sttReveal, ttsReveal);
            }
        });
        return List.of(model, status, download);
    }

    private static List<Option<?>> localSynthesisOptions(
            Screen parent, ClientConfig client, ClientConfig.TtsSection tts, boolean sttReveal, boolean ttsReveal) {
        List<String> ids = new ArrayList<>();
        ids.add("");
        for (VoiceModel model : VoiceModels.all()) {
            ids.add(model.id());
        }
        VoiceModel effective = effectiveVoice(tts);
        Option<String> voice = Option.<String>createBuilder()
                .name(tr("friendai.config.voice.tts.local.voice"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.local.voice.tooltip")))
                .binding(
                        "",
                        () -> tts.local.voice == null ? "" : tts.local.voice,
                        value -> tts.local.voice = value.isBlank() ? null : value)
                .controller(o -> CyclingListControllerBuilder.<String>create(o)
                        .values(ids)
                        .valueFormatter(FriendaiConfigScreen::localVoiceLabel))
                .build();
        LabelOption status = statusLabel(synthesisReadiness(tts), "friendai.config.voice.tts.local.status.installed");
        LabelOption license = LabelOption.create(
                Component.translatable("friendai.config.voice.tts.local.license", effective.license()));
        ButtonOption download = ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.tts.download"))
                .action((screen, button) -> {
                    int[] lastDownloadStep = {-1};
                    int[] lastInstallStep = {-1};
                    long generation = session == null ? -1 : session.generation();
                    SpeechRuntime.models()
                            .download(effective, new VoiceModelManager.DownloadStage() {
                                @Override
                                public void downloading(double fraction) {
                                    lastDownloadStep[0] = progressToast(
                                            "friendai.config.voice.tts.downloading", fraction, lastDownloadStep[0]);
                                }

                                @Override
                                public void installing(double fraction) {
                                    lastInstallStep[0] = progressToast(
                                            "friendai.config.voice.tts.installing", fraction, lastInstallStep[0]);
                                }
                            })
                            .whenComplete((path, error) -> Minecraft.getInstance()
                                    .execute(() -> {
                                        if (session == null || !session.current(generation)) return;
                                        if (error != null) {
                                            toast(Components.of(ErrorMessages.forUser(error)));
                                        } else {
                                            SpeechRuntime.invalidate();
                                            toast(tr("friendai.config.voice.tts.local.status.installed"));
                                            reopen(parent, client, sttReveal, ttsReveal);
                                        }
                                    }));
                })
                .build();
        Option<Double> speed = Option.<Double>createBuilder()
                .name(tr("friendai.config.voice.tts.speed"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.speed.tooltip")))
                .binding(1.0, () -> tts.local.speed, value -> tts.local.speed = value)
                .controller(o ->
                        DoubleSliderControllerBuilder.create(o).range(0.5, 2.0).step(0.05))
                .build();
        // The license and the installed label describe one voice, so they follow the choice.
        voice.addListener((option, value) -> {
            if (!applying) {
                reopen(parent, client, sttReveal, ttsReveal);
            }
        });
        return List.of(voice, status, license, download, speed);
    }

    /** The optional fields a preset declares, in a stable order. */
    private static List<Option<?>> cloudOptions(
            VoicePreset preset, ClientConfig.CloudSettings settings, boolean reveal, Runnable toggleReveal) {
        List<Option<?>> options = new ArrayList<>();
        if (preset.uses(CloudField.API_KEY)) {
            options.addAll(keyOptions(settings, reveal, toggleReveal));
        }
        if (preset.uses(CloudField.MODEL)) {
            options.add(stringOption(
                    "friendai.config.voice.field.model",
                    "friendai.config.voice.field.model.tooltip",
                    () -> settings.model == null ? preset.defaultModel() : settings.model,
                    preset.defaultModel(),
                    value -> settings.model = value));
        }
        if (preset.uses(CloudField.VOICE)) {
            options.add(voiceOption(preset, settings));
        }
        if (preset.uses(CloudField.FOLDER_ID)) {
            options.add(Option.<String>createBuilder()
                    .name(tr("friendai.config.voice.field.folder_id"))
                    .description(OptionDescription.of(tr("friendai.config.voice.field.folder_id.tooltip")))
                    .binding("", () -> nullToEmpty(settings.folderId), value -> settings.folderId = blankToNull(value))
                    .controller(StringControllerBuilder::create)
                    .build());
        }
        if (preset.uses(CloudField.BASE_URL)) {
            options.add(Option.<String>createBuilder()
                    .name(tr("friendai.config.voice.field.base_url"))
                    .description(OptionDescription.of(tr("friendai.config.voice.field.base_url.tooltip")))
                    .binding("", () -> nullToEmpty(settings.baseUrl), value -> settings.baseUrl = trimmedToNull(value))
                    .controller(StringControllerBuilder::create)
                    .build());
        }
        if (preset.uses(CloudField.LANGUAGE)) {
            options.add(Option.<String>createBuilder()
                    .name(tr("friendai.config.voice.field.language"))
                    .description(OptionDescription.of(tr("friendai.config.voice.field.language.tooltip")))
                    .binding("", () -> nullToEmpty(settings.language), value -> settings.language = blankToNull(value))
                    .controller(StringControllerBuilder::create)
                    .build());
        }
        return options;
    }

    private static Option<String> voiceOption(VoicePreset preset, ClientConfig.CloudSettings settings) {
        if (preset.voices().isEmpty()) {
            return Option.<String>createBuilder()
                    .name(tr("friendai.config.voice.field.voice_id"))
                    .description(OptionDescription.of(tr("friendai.config.voice.field.voice_id.tooltip")))
                    .binding("", () -> nullToEmpty(settings.voice), value -> settings.voice = blankToNull(value))
                    .controller(StringControllerBuilder::create)
                    .build();
        }
        return Option.<String>createBuilder()
                .name(tr("friendai.config.voice.field.voice"))
                .description(OptionDescription.of(tr("friendai.config.voice.field.voice.tooltip")))
                .binding(
                        preset.defaultVoice(),
                        () -> settings.voice == null ? preset.defaultVoice() : settings.voice,
                        value -> settings.voice = value)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(preset.voices())
                        .allowAnyValue(false))
                .build();
    }

    private static List<Option<?>> keyOptions(
            ClientConfig.CloudSettings settings, boolean reveal, Runnable toggleReveal) {
        Option<String> key = Option.<String>createBuilder()
                .name(tr("friendai.config.voice.field.key"))
                .description(OptionDescription.of(tr("friendai.config.voice.field.key.tooltip")))
                .binding("", () -> SecretResolver.display(settings.apiKey, reveal), value -> {
                    // The field may show a placeholder; leaving it untouched must not overwrite the real key.
                    if (!value.equals(SecretResolver.display(settings.apiKey, reveal))) {
                        settings.apiKey = value;
                    }
                })
                .controller(StringControllerBuilder::create)
                .build();
        ButtonOption toggle = ButtonOption.createBuilder()
                .name(tr(reveal ? "friendai.config.voice.field.key.hide" : "friendai.config.voice.field.key.show"))
                .action((screen, button) -> toggleReveal.run())
                .build();
        ButtonOption copy = ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.field.key.copy"))
                .action((screen, button) -> {
                    String pending = key.pendingValue();
                    String copied =
                            pending.equals(SecretResolver.display(settings.apiKey, reveal)) ? settings.apiKey : pending;
                    Minecraft.getInstance().keyboardHandler.setClipboard(copied == null ? "" : copied);
                    toast(tr("friendai.config.voice.field.key.copied"));
                })
                .build();
        return List.of(key, toggle, copy);
    }

    private static Option<String> providerOption(
            String nameKey,
            String def,
            List<String> ids,
            java.util.function.Supplier<String> getter,
            java.util.function.Consumer<String> setter,
            java.util.function.Function<String, Component> label) {
        return Option.<String>createBuilder()
                .name(tr(nameKey))
                .description(OptionDescription.of(tr("friendai.config.voice.provider.tooltip")))
                .binding(def, getter, setter)
                .controller(o -> CyclingListControllerBuilder.<String>create(o)
                        .values(ids)
                        .valueFormatter(label))
                .build();
    }

    private static Option<String> inputDeviceOption(ClientConfig.SttSection stt) {
        String defaultDevice = tr("friendai.config.voice.device.default").getString();
        List<String> devices = new ArrayList<>();
        devices.add(defaultDevice);
        try {
            devices.addAll(MicCapture.devices());
        } catch (RuntimeException e) {
            // no audio system: only the default entry is offered
        }
        return Option.<String>createBuilder()
                .name(tr("friendai.config.voice.device"))
                .description(OptionDescription.of(tr("friendai.config.voice.device.tooltip")))
                .binding(
                        defaultDevice,
                        () -> stt.inputDevice == null ? defaultDevice : stt.inputDevice,
                        value -> stt.inputDevice = value.equals(defaultDevice) ? null : value)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(devices)
                        .allowAnyValue(false))
                .build();
    }

    private static Option<String> outputDeviceOption(ClientConfig.TtsSection tts) {
        String defaultDevice =
                tr("friendai.config.voice.tts.output_device.default").getString();
        List<String> devices = new ArrayList<>();
        devices.add(defaultDevice);
        try {
            devices.addAll(SpeechPlayback.devices());
        } catch (RuntimeException e) {
            // no audio system: only the default entry is offered
        }
        return Option.<String>createBuilder()
                .name(tr("friendai.config.voice.tts.output_device"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.output_device.tooltip")))
                .binding(
                        defaultDevice,
                        () -> tts.outputDevice == null ? defaultDevice : tts.outputDevice,
                        value -> tts.outputDevice = value.equals(defaultDevice) ? null : value)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(devices)
                        .allowAnyValue(false))
                .build();
    }

    private static Option<Double> cloudSpeedOption(ClientConfig.CloudSettings settings, String providerId) {
        double min;
        double max;
        switch (providerId) {
            case "elevenlabs" -> {
                min = 0.7;
                max = 1.2;
            }
            case "yandex" -> {
                min = 0.1;
                max = 3.0;
            }
            default -> {
                min = 0.5;
                max = 2.0;
            }
        }
        return speedOption(
                min,
                max,
                "friendai.config.voice.tts.speed.cloud.tooltip",
                () -> settings.speed,
                value -> settings.speed = value);
    }

    private static Option<Double> speedOption(
            double min,
            double max,
            String descriptionKey,
            java.util.function.Supplier<Double> getter,
            java.util.function.Consumer<Double> setter) {
        return Option.<Double>createBuilder()
                .name(tr("friendai.config.voice.tts.speed"))
                .description(OptionDescription.of(tr(descriptionKey)))
                .binding(1.0, getter::get, setter::accept)
                .controller(o ->
                        DoubleSliderControllerBuilder.create(o).range(min, max).step(0.05))
                .build();
    }

    private static ButtonOption docsButton(VoicePreset preset) {
        return ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.tts.docs"))
                .action((screen, button) ->
                        lifecycle.replace(() -> ConfirmLinkScreen.confirmLinkNow(screen, URI.create(preset.docsUrl()))))
                .build();
    }

    private static LabelOption statusLabel(VoiceRuntime.SttStatus status) {
        return statusLabel(status.problem(), "friendai.config.voice.stt.status.ready");
    }

    /**
     * The row a voice dependency shows. When the runtime would refuse to start, its own message is
     * used verbatim, so the screen names the same reason a failed recording or reply names.
     */
    private static LabelOption statusLabel(UserMessage problem, String readyKey) {
        return LabelOption.create(problem == null ? tr(readyKey) : Components.of(problem));
    }

    /**
     * Why synthesis cannot start right now, or null when it can: the same decision
     * {@code SpeechRuntime.playOne} makes, with the same installed-voice check.
     */
    private static UserMessage synthesisReadiness(ClientConfig.TtsSection tts) {
        ResolvedTts resolved = VoiceConfig.resolveTts(
                tts, Minecraft.getInstance().getLanguageManager().getSelected(), System::getenv);
        boolean installed = true;
        if (resolved.local()) {
            installed = VoiceModels.byId(resolved.voice())
                    .map(SpeechRuntime.models()::isInstalled)
                    .orElse(false);
        }
        return VoiceReadiness.tts(resolved, installed);
    }

    /**
     * Keeps what one connectivity test proved about a model. Only the runtime's text-only answer is
     * stored: a model that called the tool needs no warning, and an untested model has no verdict.
     */
    private static void rememberToolVerdict(String provider, String model, UserMessage verdict) {
        String key = toolVerdictKey(provider, model);
        if (key == null) return;
        if ("friendai.config.test.ok_text".equals(verdict.key())) {
            TEXT_ONLY_MODELS.put(key, verdict);
        } else {
            TEXT_ONLY_MODELS.remove(key);
        }
    }

    private static String toolVerdictKey(String provider, String model) {
        if (provider == null || model == null || model.isBlank()) return null;
        return provider.strip() + "|" + model.strip();
    }

    /** The entry for one provider; entries are per provider so one key never moves to another. */
    private static ClientConfig.CloudSettings cloud(Map<String, ClientConfig.CloudSettings> cloud, VoicePreset preset) {
        return cloud.computeIfAbsent(preset.id(), id -> new ClientConfig.CloudSettings());
    }

    /** Writes every widget's pending value into the config, so a rebuild can read it back. */
    private static void applyPending() {
        boolean previous = applying;
        applying = true;
        try {
            for (Option<?> option : pendingOptions) {
                // Search, the Basic/Advanced switch and the profile/transfer fields only drive the screen:
                // their pending values are written back, but they never arm Save or mark a draft as edited.
                if (option.changed() && !UI_OPTIONS.contains(option)) draftChanged = true;
                option.applyValue();
            }
        } finally {
            applying = previous;
        }
    }

    /**
     * Applies pending edits, then rebuilds with the provider whose entry is now selected. The new
     * provider's own stored values are shown, so a switch never moves the old provider's key or model.
     */
    private static void reopen(Screen parent, ClientConfig client, boolean sttReveal, boolean ttsReveal) {
        deferRebuild(() -> {
            applyPending();
            VoiceDraft next = VoiceDraft.from(client).withSttReveal(sttReveal).withTtsReveal(ttsReveal);
            show(create(parent, providerKeyRevealed, next));
        });
    }

    private static VoiceModel effectiveVoice(ClientConfig.TtsSection tts) {
        String id = tts.local.voice;
        if (id != null && !id.isBlank()) {
            for (VoiceModel model : VoiceModels.all()) {
                if (model.id().equals(id)) {
                    return model;
                }
            }
        }
        return VoiceModels.defaultFor(
                Minecraft.getInstance().getLanguageManager().getSelected());
    }

    private static Component sttProviderLabel(String id) {
        return switch (id) {
            case "openai" -> tr("friendai.config.voice.stt.provider.openai");
            case "groq" -> tr("friendai.config.voice.stt.provider.groq");
            case "yandex" -> tr("friendai.config.voice.stt.provider.yandex");
            default -> tr("friendai.config.voice.stt.provider.local");
        };
    }

    private static Component ttsProviderLabel(String id) {
        return switch (id) {
            case "openai" -> tr("friendai.config.voice.tts.provider.openai");
            case "elevenlabs" -> tr("friendai.config.voice.tts.provider.elevenlabs");
            case "yandex" -> tr("friendai.config.voice.tts.provider.yandex");
            default -> tr("friendai.config.voice.tts.provider.local");
        };
    }

    private static Component localVoiceLabel(String id) {
        if (id == null || id.isBlank()) {
            return tr("friendai.config.voice.tts.local.voice.default");
        }
        for (VoiceModel model : VoiceModels.all()) {
            if (model.id().equals(id)) {
                return Component.literal(model.displayName() + " · " + model.language());
            }
        }
        return Component.literal(id);
    }

    /** Records three seconds from the microphone and shows what was recognized. */
    private static void testRecording(ClientConfig client) {
        applyPending();
        VoiceRuntime.SttStatus status = VoiceRuntime.sttStatus(client.voice.stt);
        if (!status.ready()) {
            // The readiness message names the real reason: no key, no folder, unsupported language
            // or only a missing local model.
            toast(Components.of(status.problem()));
            return;
        }
        ClientConfig.SttSection stt = client.voice.stt;
        SpeechToText recognizer = VoiceRuntime.stt(stt);
        if (!recognizer.isReady()) {
            toast(Component.translatable("friendai.voice.error.no_model"));
            return;
        }
        ClientSessionId sessionId = FriendaiClient.runtime().sessionId();
        int generation = VoiceRuntime.nextCaptureGeneration();
        MicCapture mic = VoiceRuntime.mic();
        CompletableFuture.runAsync(() -> {
            try {
                mic.start(stt.inputDevice, 5);
                Thread.sleep(3_000);
                byte[] pcm = mic.stop();
                if (!VoiceRuntime.isCurrentOperation(sessionId, generation)) return;
                recognizer
                        .transcribe(PcmConverter.toFloat(pcm), stt.language)
                        .whenComplete((text, error) -> Minecraft.getInstance().execute(() -> {
                            if (!VoiceRuntime.isCurrentOperation(sessionId, generation)) return;
                            toast(
                                    error != null
                                            ? Components.of(VoiceErrors.userMessage(error))
                                            : Component.translatable("friendai.config.voice.test_result", text));
                        }));
            } catch (Exception e) {
                mic.stop();
                Minecraft.getInstance().execute(() -> {
                    if (VoiceRuntime.isCurrentOperation(sessionId, generation)) {
                        toast(Components.of(UserMessage.of("friendai.voice.error.no_mic")));
                    }
                });
            }
        });
    }

    private static Option<Boolean> booleanOption(
            String nameKey,
            boolean def,
            java.util.function.Supplier<Boolean> getter,
            java.util.function.Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(tr(nameKey))
                .binding(def, getter, setter)
                .controller(BooleanControllerBuilder::create)
                .build();
    }

    private static Option<Boolean> booleanOption(
            String nameKey,
            String descriptionKey,
            boolean def,
            java.util.function.Supplier<Boolean> getter,
            java.util.function.Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(tr(nameKey))
                .description(OptionDescription.of(tr(descriptionKey)))
                .binding(def, getter, setter)
                .controller(BooleanControllerBuilder::create)
                .build();
    }

    private static void toast(Component message) {
        ClientToasts.show(message);
    }

    /**
     * Shows a progress toast once per 10% and returns the step reported, so the next call can throttle.
     * Called from the download's background thread; only that thread touches {@code lastStep}.
     */
    private static int progressToast(String key, double fraction, int lastStep) {
        int percent = (int) (fraction * 100);
        int step = Math.min(10, percent / 10);
        if (step == lastStep) {
            return lastStep;
        }
        Minecraft.getInstance().execute(() -> toast(Component.translatable(key, percent)));
        return step;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** URLs are pasted with surrounding whitespace; store them the way readiness and the URI builder see them. */
    private static String trimmedToNull(String value) {
        String trimmed = value == null ? null : value.strip();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
