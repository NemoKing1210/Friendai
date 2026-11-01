package dev.friendai.client.config;

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
import dev.friendai.features.speech.CloudField;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.VoiceErrors;
import dev.friendai.features.speech.VoicePreset;
import dev.friendai.features.speech.stt.SttPresets;
import dev.friendai.features.speech.tts.TtsPresets;
import dev.friendai.features.speech.tts.VoiceModel;
import dev.friendai.features.speech.tts.VoiceModels;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.SecretResolver;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.infrastructure.config.SettingsSession;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Friendai settings screen, built with YACL. Only loaded when YACL is installed. */
public final class FriendaiConfigScreen {
    private static final List<String> WHISPER_MODELS = List.of("tiny", "base", "small");

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

    private FriendaiConfigScreen() {}

    private static Component tr(String key) {
        return Component.translatable(key);
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
        if (server != null) {
            builder.category(providerCategory(parent, server, revealKey, voice));
            builder.category(companionCategory(server));
            builder.category(chatCategory(server));
            builder.category(memoryCategory(server));
            builder.category(miningCategory(server));
        } else {
            builder.category(ConfigCategory.createBuilder()
                    .name(tr("friendai.config.category.server"))
                    .option(LabelOption.create(tr(
                            serverConfigReadOnly
                                    ? serverConfigProtected
                                            ? "friendai.config.server_protected"
                                            : "friendai.config.server_unsupported"
                                    : "friendai.config.server_only")))
                    .build());
        }
        builder.category(voiceCategory(parent, client, voice));

        builder.save(() -> {
            current.voiceDraft(VoiceDraft.from(client));
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
                if (session == current) currentLifecycle.removed(screen.popupControllerVisible);
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

    private static Option<String> stringOption(
            String nameKey,
            java.util.function.Supplier<String> getter,
            String def,
            java.util.function.Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(tr(nameKey))
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
                .binding(new ServerConfig.ProfileConfig().provider, () -> p.provider, v -> p.provider = v)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(providerIds)
                        .allowAnyValue(false))
                .build();
        Option<String> baseUrlOpt = Option.<String>createBuilder()
                .name(tr("friendai.config.base_url"))
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
                .binding(new ServerConfig.ProfileConfig().model, () -> p.model, v -> p.model = v)
                .customController(o -> new SuggestedStringController(o, ModelCache.get(p.provider)))
                .build();
        Option<Double> temperatureOpt = Option.<Double>createBuilder()
                .name(tr("friendai.config.temperature"))
                .binding(0.7, () -> p.temperature == null ? 0.7 : p.temperature, v -> p.temperature = v)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(0.0, 2.0))
                .build();
        Option<Integer> maxTokensOpt = Option.<Integer>createBuilder()
                .name(tr("friendai.config.max_tokens"))
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
                            toast(error != null ? Components.of(ErrorMessages.forUser(error)) : Components.of(result));
                        })))
                .build();

        return ConfigCategory.createBuilder()
                .name(tr("friendai.config.category.provider"))
                .option(profileOpt)
                .option(providerOpt)
                .option(baseUrlOpt)
                .option(apiKeyOpt)
                .option(toggleKey)
                .option(getKey)
                .option(modelOpt)
                .option(loadModels)
                .option(temperatureOpt)
                .option(maxTokensOpt)
                .option(testConnection)
                .build();
    }

    private static ConfigCategory companionCategory(ServerConfig server) {
        var c = server.companion;
        List<String> plannerProfiles = new ArrayList<>();
        plannerProfiles.add("");
        plannerProfiles.addAll(server.profiles.keySet());
        return ConfigCategory.createBuilder()
                .name(tr("friendai.config.category.companion"))
                .option(stringOption(
                        "friendai.config.companion.name",
                        () -> c.name,
                        "Friendai",
                        v -> c.name = v.isBlank() ? "Friendai" : v.strip()))
                .option(stringOption(
                        "friendai.config.companion.description",
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
                        true,
                        () -> server.skills.enabled,
                        v -> server.skills.enabled = v))
                .option(booleanOption(
                        "friendai.config.skills.approval",
                        true,
                        () -> server.skills.requireApproval,
                        v -> server.skills.requireApproval = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.budget"))
                        .binding(4096, () -> server.skills.promptBudget, v -> server.skills.promptBudget = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(200, 6000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.hourly"))
                        .binding(6, () -> server.skills.modelWritesPerHour, v -> server.skills.modelWritesPerHour = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(0, 60))
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
                .build();
    }

    private static ConfigCategory chatCategory(ServerConfig server) {
        var c = server.chat;
        return ConfigCategory.createBuilder()
                .name(tr("friendai.config.category.chat"))
                .option(stringOption("friendai.config.chat.prefix", () -> c.prefix, "@", v -> c.prefix = v))
                .option(booleanOption(
                        "friendai.config.chat.respond_to_name", true, () -> c.respondToName, v -> c.respondToName = v))
                .option(booleanOption(
                        "friendai.config.chat.listen_all", false, () -> c.listenAll, v -> c.listenAll = v))
                .option(booleanOption(
                        "friendai.config.chat.guests_enabled", true, () -> c.guestsEnabled, v -> c.guestsEnabled = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.hearing_radius"))
                        .binding(64, () -> c.hearingRadius, v -> c.hearingRadius = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(4, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.guest_cooldown_ms"))
                        .binding(5000, () -> c.guestCooldownMs, v -> c.guestCooldownMs = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(0, 60000))
                        .build())
                .build();
    }

    private static ConfigCategory memoryCategory(ServerConfig server) {
        var m = server.memory;
        return ConfigCategory.createBuilder()
                .name(tr("friendai.config.category.memory"))
                .option(booleanOption("friendai.config.memory.enabled", true, () -> m.enabled, v -> m.enabled = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_fact_chars"))
                        .binding(512, () -> m.maxFactChars, v -> m.maxFactChars = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(40, 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_facts_per_player"))
                        .binding(128, () -> m.maxFactsPerPlayer, v -> m.maxFactsPerPlayer = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_world_facts"))
                        .binding(128, () -> m.maxWorldFacts, v -> m.maxWorldFacts = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 200))
                        .build())
                .build();
    }

    /** A valuable drop's protection: whether it is on, what counts as valuable and how many blocks it may cost. */
    private static ConfigCategory miningCategory(ServerConfig server) {
        ServerConfig.MiningSection m = server.mining;
        return ConfigCategory.createBuilder()
                .name(tr("friendai.config.category.mining"))
                .option(booleanOption(
                        "friendai.config.mining.protect_drops", true, () -> m.protectDrops, v -> m.protectDrops = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.mining.catch_blocks"))
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

    private static ConfigCategory voiceCategory(Screen parent, ClientConfig client, VoiceDraft draft) {
        ClientConfig.SttSection stt = client.voice.stt;
        ClientConfig.TtsSection tts = client.voice.tts;
        boolean sttReveal = draft.sttReveal();
        boolean ttsReveal = draft.ttsReveal();
        VoicePreset sttPreset = SttPresets.require(stt.provider);
        VoicePreset ttsPreset = TtsPresets.require(tts.provider);

        List<Option<?>> recognition = new ArrayList<>();
        List<Option<?>> synthesis = new ArrayList<>();

        Option<Boolean> sttEnabled =
                booleanOption("friendai.config.voice.enabled", true, () -> stt.enabled, value -> stt.enabled = value);
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
                .binding("auto", () -> stt.language, value -> stt.language = value.isBlank() ? "auto" : value.strip())
                .controller(StringControllerBuilder::create)
                .build();
        Option<Integer> maxRecord = Option.<Integer>createBuilder()
                .name(tr("friendai.config.voice.max_record"))
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
                "friendai.config.voice.tts.enabled", false, () -> tts.enabled, value -> tts.enabled = value);
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
                .binding(1.0, () -> tts.volume, value -> tts.volume = value)
                .controller(o ->
                        DoubleSliderControllerBuilder.create(o).range(0.0, 1.0).step(0.05))
                .build();
        Option<Integer> maxChars = Option.<Integer>createBuilder()
                .name(tr("friendai.config.voice.tts.max_chars"))
                .binding(300, () -> tts.maxChars, value -> tts.maxChars = value)
                .controller(o -> IntegerFieldControllerBuilder.create(o).range(20, 500))
                .build();
        Option<Boolean> speakGuests = booleanOption(
                "friendai.config.voice.tts.speak_guests",
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
                .name(tr("friendai.config.category.voice"))
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
                .binding("base", () -> stt.local.model, value -> stt.local.model = value)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(WHISPER_MODELS)
                        .allowAnyValue(false))
                .build();
        boolean installed = VoiceRuntime.manager()
                .isInstalled(WhisperModel.byId(stt.local.model).spec());
        LabelOption status = LabelOption.create(
                tr(installed ? "friendai.config.voice.status.installed" : "friendai.config.voice.status.missing"));
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
                .binding(
                        "",
                        () -> tts.local.voice == null ? "" : tts.local.voice,
                        value -> tts.local.voice = value.isBlank() ? null : value)
                .controller(o -> CyclingListControllerBuilder.<String>create(o)
                        .values(ids)
                        .valueFormatter(FriendaiConfigScreen::localVoiceLabel))
                .build();
        LabelOption status = LabelOption.create(tr(
                SpeechRuntime.models().isInstalled(effective)
                        ? "friendai.config.voice.tts.local.status.installed"
                        : "friendai.config.voice.tts.local.status.missing"));
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
                    .binding("", () -> nullToEmpty(settings.voice), value -> settings.voice = blankToNull(value))
                    .controller(StringControllerBuilder::create)
                    .build();
        }
        return Option.<String>createBuilder()
                .name(tr("friendai.config.voice.field.voice"))
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
        return speedOption(min, max, () -> settings.speed, value -> settings.speed = value);
    }

    private static Option<Double> speedOption(
            double min,
            double max,
            java.util.function.Supplier<Double> getter,
            java.util.function.Consumer<Double> setter) {
        return Option.<Double>createBuilder()
                .name(tr("friendai.config.voice.tts.speed"))
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
        return LabelOption.create(
                status.ready() ? tr("friendai.config.voice.stt.status.ready") : Components.of(status.problem()));
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
                if (option.changed()) draftChanged = true;
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
