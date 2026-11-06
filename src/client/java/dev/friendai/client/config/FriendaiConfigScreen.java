package dev.friendai.client.config;

import static dev.friendai.client.config.SettingsWidgets.actionButton;
import static dev.friendai.client.config.SettingsWidgets.filtered;
import static dev.friendai.client.config.SettingsWidgets.resetTargets;
import static dev.friendai.client.config.SettingsWidgets.scoped;
import static dev.friendai.client.config.SettingsWidgets.toast;
import static dev.friendai.client.config.SettingsWidgets.tr;
import static dev.friendai.client.config.SettingsWidgets.withReset;
import static dev.friendai.client.config.SettingsWidgets.withResetAll;

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
import dev.friendai.client.FriendaiClient;
import dev.friendai.common.Components;
import dev.friendai.features.settings.SettingsModel;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.SecretResolver;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.config.SettingsSession;
import dev.friendai.infrastructure.config.VoiceDraft;
import dev.friendai.llm.LlmClient;
import dev.friendai.llm.http.HttpTransport;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
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
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Friendai settings screen, built with YACL. Only loaded when YACL is installed. */
public final class FriendaiConfigScreen {
    /** Export/import envelope version; a file that does not carry this number is refused. */
    private static final int TRANSFER_VERSION = 1;

    /** What an exported key looks like: the real value never leaves the machine. */
    private static final String MASK = "***";

    private static final String DEFAULT_TRANSFER_FILE = "friendai-settings-export.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The open session and its unsaved draft; every screen method works through it. */
    private static final SettingsDraftSession draft = new SettingsDraftSession();

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

    private FriendaiConfigScreen() {}

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
        SettingsSession opened = draft.open(FriendaiClient.clientConfig(), server, serverConfigReadOnly);
        providerKeyRevealed = false;
        searchQuery = "";
        profileName = "";
        return create(parent, false, opened.voiceDraft());
    }

    /**
     * Builds the screen from the open session. Rebuilds carry widget drafts into the same session; nothing is
     * written to the live or persisted configuration until Save.
     */
    private static Screen create(Screen parent, boolean revealKey, VoiceDraft voiceDraft) {
        SettingsSession current = draft.session();
        if (current == null || !current.isOpen()) return parent;
        draft.applyPending();
        draft.nextRevision();
        pendingRebuild = null;
        providerKeyRevealed = revealKey;
        SettingsScreenLifecycle currentLifecycle = draft.lifecycle();
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
            categories.add(SettingsCategorySections.companionCategory(server));
            categories.add(SettingsCategorySections.chatCategory(server));
            categories.add(SettingsCategorySections.planningCategory(server));
            categories.add(SettingsCategorySections.tasksCategory(server));
            categories.add(SettingsCategorySections.worldCategory(server));
            categories.add(SettingsCategorySections.logsCategory(server));
            categories.add(SettingsCategorySections.tradingCategory(server));
            categories.add(SettingsCategorySections.resourcesCategory(server));
            categories.add(SettingsCategorySections.autonomyCategory(server));
            categories.add(SettingsCategorySections.memoryCategory(server));
            categories.add(SettingsCategorySections.miningCategory(server));
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
        categories.add(SettingsCategorySections.socialCategory(client));
        categories.add(VoiceSettingsCategory.build(parent, client, voice, new VoiceSettingsCategory.Host() {
            @Override
            public boolean applying() {
                return draft.applying();
            }

            @Override
            public void applyPending() {
                draft.applyPending();
            }

            @Override
            public void reopen(Screen parent, ClientConfig client, boolean sttReveal, boolean ttsReveal) {
                FriendaiConfigScreen.reopen(parent, client, sttReveal, ttsReveal);
            }

            @Override
            public long generation() {
                return draft.generation();
            }

            @Override
            public boolean current(long generation) {
                return draft.current(generation);
            }

            @Override
            public void confirmLink(Screen screen, String url) {
                draft.lifecycle().replace(() -> ConfirmLinkScreen.confirmLinkNow(screen, URI.create(url)));
            }
        }));
        // "Reset everything" sits at the top of the first settings tab and targets the same option instances
        // the per-category buttons do, so both kinds of reset reach the draft and need a Save.
        List<Option<?>> resetAllTargets = new ArrayList<>();
        for (ConfigCategory category : categories) resetAllTargets.addAll(resetTargets(category));
        for (int index = 0; index < categories.size(); index++) {
            ConfigCategory category = categories.get(index);
            ConfigCategory prepared = index == 0
                    ? withResetAll(withReset(category, reopen), resetAllTargets, reopen)
                    : withReset(category, reopen);
            ConfigCategory narrowed = filtered(prepared, searchQuery, advancedOptions);
            if (narrowed != null) builder.category(narrowed);
        }

        builder.save(() -> {
            current.voiceDraft(VoiceDraft.from(client));
            // Validate before publishing: validate() is also what clamps, and its first line is the only
            // honest way to say that a value was adjusted instead of dropping the change silently.
            draft.lastSaveProblem(firstProblem(current));
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
                if (draft.session() != current) return;
                currentLifecycle.removed(screen.popupControllerVisible);
                // A real exit (not a rebuild and not a dropdown popup) also drops the stale save report.
                if (!current.isOpen()) draft.lastSaveProblem(null);
            });
            ScreenEvents.afterTick(screen).register(ticked -> {
                if (draft.session() == current
                        && Minecraft.getInstance().gui.screen() == screen
                        && pendingRebuild != null) {
                    Runnable refresh = pendingRebuild;
                    pendingRebuild = null;
                    deferRebuild(refresh);
                }
            });
        });
        YetAnotherConfigLib config = builder.build();
        draft.collectPending(config.categories().stream()
                .flatMap(category -> category.groups().stream())
                .flatMap(group -> group.options().stream())
                .toList());
        settingsScreen = new YACLScreen(config, parent) {
            @Override
            public boolean pendingChanges() {
                return super.pendingChanges() || draft.session() == current && draft.draftChanged();
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
        draft.lifecycle().replace(() -> Minecraft.getInstance().setScreenAndShow(next));
        if (tab != null && next instanceof YACLScreen screen) {
            screen.tabNavigationBar.getTabs().stream()
                    .filter(candidate -> candidate.getTabTitle().equals(tab))
                    .findFirst()
                    .ifPresent(candidate -> screen.tabManager.setCurrentTab(candidate, false));
        }
    }

    /** Queue after YACL has finished closing a dropdown; execute() can run inline on the client thread. */
    private static void deferRebuild(Runnable rebuild) {
        if (draft.applying()) return;
        SettingsSession expected = draft.session();
        long revision = draft.revision();
        YACLScreen expectedScreen = settingsScreen;
        Minecraft.getInstance().schedule(() -> {
            if (draft.session() != expected || !expected.isOpen() || draft.revision() != revision) return;
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
        draft.clearUiOptions();
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
            if (!draft.applying()) reopen.run();
        });
        find.add(search);
        draft.uiOption(search);
        Option<Boolean> advanced = Option.<Boolean>createBuilder()
                .name(tr("friendai.config.nav.advanced"))
                .description(OptionDescription.of(tr("friendai.config.nav.advanced.tooltip")))
                .binding(true, () -> advancedOptions, value -> advancedOptions = value)
                .controller(BooleanControllerBuilder::create)
                .build();
        advanced.addListener((option, value) -> {
            if (!draft.applying()) reopen.run();
        });
        find.add(advanced);
        draft.uiOption(advanced);
        if (protectedFile) {
            // The file was damaged and the last good backup is in use; saving replaces the damaged file.
            find.add(LabelOption.create(Component.translatable("friendai.config.nav.recovered")));
        }
        if (draft.lastSaveProblem() != null) {
            find.add(LabelOption.create(Component.translatable(
                    "friendai.config.nav.save_problem", Component.literal(draft.lastSaveProblem()))));
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
        draft.uiOption(name);
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
        draft.uiOption(path);
        return OptionGroup.createBuilder()
                .name(tr("friendai.config.nav.transfer"))
                .option(path)
                .option(actionButton("friendai.config.nav.export", () -> exportSettings(client, server)))
                .option(actionButton("friendai.config.nav.import", () -> importSettings(client, server, reopen)))
                .build();
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
        draft.lastSaveProblem(problems.isEmpty() ? null : problems.getFirst());
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

        // Answers are delivered only to the screen that asked: same session, same revision.
        long generation = draft.generation();
        long revision = draft.revision();
        ProviderModelControls.Callbacks callbacks = new ProviderModelControls.Callbacks() {
            @Override
            public boolean current(long expectedGeneration, long expectedRevision) {
                return draft.current(expectedGeneration) && draft.revision() == expectedRevision;
            }

            @Override
            public void toast(Component message) {
                SettingsWidgets.toast(message);
            }

            @Override
            public void rebuilt() {
                deferRebuild(() -> show(create(parent, revealKey, voiceDraft)));
            }
        };
        Runnable loadModelsThenReopen = () -> ProviderModelControls.loadModels(
                pendingClient.get(), providerOpt.pendingValue(), generation, revision, callbacks);

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
                .action((screen, button) -> ProviderModelControls.testConnection(
                        pendingClient.get(),
                        providerOpt.pendingValue(),
                        modelOpt.pendingValue(),
                        generation,
                        revision,
                        callbacks))
                .build();

        String requestedProvider = providerOpt.pendingValue();
        ProviderModelControls.autoFetchIfNeeded(
                pendingClient.get(), requestedProvider, p.baseUrl, p.apiKey, registry, generation, revision, callbacks);
        OptionGroup advanced = OptionGroup.createBuilder()
                .name(scoped(tr("friendai.config.category.advanced"), "friendai.config.scope.server"))
                .collapsed(true)
                .option(baseUrlOpt)
                .build();
        // The brain offers tools on every acting turn, so a model that answers text instead of a
        // tool call breaks the companion's action layer; a zero tool-step budget makes a turn chat
        // only, and then the verdict does not matter.
        UserMessage toolNotice =
                ProviderModelControls.notice(p.provider, p.model, server.brain.maxToolStepsPerTurn > 0);
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

    /** Writes every widget's pending value into the config, so a rebuild can read it back. */
    private static void applyPending() {
        draft.applyPending();
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
}
