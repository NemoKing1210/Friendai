package dev.friedai.client.config;

import dev.friedai.brain.ErrorMessages;
import dev.friedai.client.FriedaiClient;
import dev.friedai.config.ClientConfig;
import dev.friedai.config.ConfigStore;
import dev.friedai.config.SecretResolver;
import dev.friedai.config.ServerConfig;
import dev.friedai.core.Components;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.llm.LlmClient;
import dev.friedai.llm.http.HttpTransport;
import dev.friedai.llm.provider.LlmProfile;
import dev.friedai.llm.provider.ProviderPreset;
import dev.friedai.llm.provider.ProviderRegistry;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The Friedai settings screen, built with YACL. Only loaded when YACL is installed. */
public final class FriedaiConfigScreen {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId();
    private static final List<String> WHISPER_MODELS = List.of("tiny", "base", "small");

    private FriedaiConfigScreen() {}

    private static Component tr(String key) {
        return Component.translatable(key);
    }

    public static Screen create(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        boolean serverEditable = mc.getSingleplayerServer() != null || mc.level == null;

        ConfigStore<ServerConfig> serverStore = new ConfigStore<>(
                FabricLoader.getInstance().getConfigDir().resolve("friedai-server.json"),
                ServerConfig.class, ServerConfig::defaults, 1, (json, from) -> json);
        ServerConfig server = null;
        if (serverEditable) {
            server = serverStore.load();
            server.validate();
        }
        ClientConfig client = FriedaiClient.clientConfig();

        YetAnotherConfigLib.Builder builder = YetAnotherConfigLib.createBuilder().title(tr("friedai.config.title"));
        if (server != null) {
            builder.category(providerCategory(parent, server));
            builder.category(companionCategory(server));
            builder.category(chatCategory(server));
        } else {
            builder.category(ConfigCategory.createBuilder()
                    .name(tr("friedai.config.category.server"))
                    .option(LabelOption.create(tr("friedai.config.server_only")))
                    .build());
        }
        builder.category(voiceCategory(client));

        ServerConfig savedServer = server;
        builder.save(() -> {
            if (savedServer != null) {
                serverStore.save(savedServer);
                var integrated = mc.getSingleplayerServer();
                if (integrated != null) {
                    integrated.execute(() -> FriedaiServerState.get(integrated).applyConfig(savedServer));
                }
            }
            client.validate();
            FriedaiClient.clientConfigStore().save(client);
            FriedaiClient.setClientConfig(client);
        });
        return builder.build().generateScreen(parent);
    }

    private static Option<String> stringOption(String nameKey, java.util.function.Supplier<String> getter, String def, java.util.function.Consumer<String> setter) {
        return Option.<String>createBuilder()
                .name(tr(nameKey))
                .binding(def, getter, setter)
                .controller(StringControllerBuilder::create)
                .build();
    }

    private static ConfigCategory providerCategory(Screen parent, ServerConfig server) {
        ServerConfig.ProfileConfig p = server.profiles.computeIfAbsent(server.activeProfile, k -> new ServerConfig.ProfileConfig());
        ProviderRegistry registry = ProviderRegistry.withBuiltins(
                new HttpTransport(HttpClient.newHttpClient(), Duration.ofSeconds(30), 1, Duration.ofMillis(300)));
        List<String> providerIds = registry.presets().stream().map(ProviderPreset::id).toList();

        Option<String> profileOpt = Option.<String>createBuilder()
                .name(tr("friedai.config.profile"))
                .binding(server.activeProfile, () -> server.activeProfile, v -> server.activeProfile = v)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(new ArrayList<>(server.profiles.keySet())).allowAnyValue(false))
                .build();
        Option<String> providerOpt = Option.<String>createBuilder()
                .name(tr("friedai.config.provider"))
                .binding(p.provider, () -> p.provider, v -> p.provider = v)
                .controller(o -> DropdownStringControllerBuilder.create(o).values(providerIds).allowAnyValue(false))
                .build();
        Option<String> baseUrlOpt = Option.<String>createBuilder()
                .name(tr("friedai.config.base_url"))
                .binding("", () -> p.baseUrl == null ? "" : p.baseUrl, v -> p.baseUrl = v.isBlank() ? null : v.strip())
                .controller(StringControllerBuilder::create)
                .build();
        Option<String> apiKeyOpt = Option.<String>createBuilder()
                .name(tr("friedai.config.api_key"))
                .description(OptionDescription.of(tr("friedai.config.api_key.tooltip")))
                .binding("", () -> SecretResolver.mask(p.apiKey), v -> {
                    // The field shows a mask; leaving it untouched must not overwrite the real key.
                    if (!v.equals(SecretResolver.mask(p.apiKey))) {
                        p.apiKey = v;
                    }
                })
                .controller(StringControllerBuilder::create)
                .build();
        Option<String> modelOpt = Option.<String>createBuilder()
                .name(tr("friedai.config.model"))
                .binding(p.model, () -> p.model, v -> p.model = v)
                .controller(o -> DropdownStringControllerBuilder.create(o)
                        .values(ModelCache.get(p.provider)).allowAnyValue(true).allowEmptyValue(false))
                .build();
        Option<Double> temperatureOpt = Option.<Double>createBuilder()
                .name(tr("friedai.config.temperature"))
                .binding(0.7, () -> p.temperature == null ? 0.7 : p.temperature, v -> p.temperature = v)
                .controller(o -> DoubleFieldControllerBuilder.create(o).range(0.0, 2.0))
                .build();
        Option<Integer> maxTokensOpt = Option.<Integer>createBuilder()
                .name(tr("friedai.config.max_tokens"))
                .binding(1024, () -> p.maxTokens, v -> p.maxTokens = v)
                .controller(o -> IntegerFieldControllerBuilder.create(o).range(16, 65536))
                .build();

        java.util.function.Supplier<LlmClient> pendingClient = () -> {
            String pendingKey = apiKeyOpt.pendingValue();
            String key = pendingKey.equals(SecretResolver.mask(p.apiKey)) ? p.apiKey : pendingKey;
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

        ButtonOption loadModels = ButtonOption.createBuilder()
                .name(tr("friedai.config.load_models"))
                .action((screen, button) -> {
                    String provider = providerOpt.pendingValue();
                    pendingClient.get().listModels().whenComplete((models, error) -> Minecraft.getInstance().execute(() -> {
                        if (error != null) {
                            toast(Components.of(ErrorMessages.forUser(error)));
                            return;
                        }
                        ModelCache.put(provider, models);
                        toast(Component.translatable("friedai.config.models_loaded", models.size()));
                        Minecraft.getInstance().setScreenAndShow(create(parent));
                    }));
                })
                .build();
        ButtonOption testConnection = ButtonOption.createBuilder()
                .name(tr("friedai.config.test_connection"))
                .action((screen, button) -> pendingClient.get().testConnection().whenComplete((result, error) ->
                        Minecraft.getInstance().execute(() -> toast(error != null
                                ? Components.of(ErrorMessages.forUser(error))
                                : Components.of(result)))))
                .build();

        return ConfigCategory.createBuilder()
                .name(tr("friedai.config.category.provider"))
                .option(profileOpt)
                .option(providerOpt)
                .option(baseUrlOpt)
                .option(apiKeyOpt)
                .option(modelOpt)
                .option(temperatureOpt)
                .option(maxTokensOpt)
                .option(loadModels)
                .option(testConnection)
                .build();
    }

    private static ConfigCategory companionCategory(ServerConfig server) {
        var c = server.companion;
        return ConfigCategory.createBuilder()
                .name(tr("friedai.config.category.companion"))
                .option(stringOption("friedai.config.companion.name", () -> c.name, "Friedai", v -> c.name = v.isBlank() ? "Friedai" : v.strip()))
                .option(stringOption("friedai.config.companion.persona", () -> c.persona, c.persona, v -> c.persona = v))
                .option(Option.<String>createBuilder()
                        .name(tr("friedai.config.companion.language"))
                        .description(OptionDescription.of(tr("friedai.config.companion.language.tooltip")))
                        .binding("auto", () -> c.language, v -> c.language = v.isBlank() ? "auto" : v.strip())
                        .controller(StringControllerBuilder::create)
                        .build())
                .build();
    }

    private static ConfigCategory chatCategory(ServerConfig server) {
        var c = server.chat;
        return ConfigCategory.createBuilder()
                .name(tr("friedai.config.category.chat"))
                .option(stringOption("friedai.config.chat.prefix", () -> c.prefix, "@", v -> c.prefix = v))
                .option(booleanOption("friedai.config.chat.respond_to_name", true, () -> c.respondToName, v -> c.respondToName = v))
                .option(booleanOption("friedai.config.chat.listen_all", false, () -> c.listenAll, v -> c.listenAll = v))
                .build();
    }

    private static ConfigCategory voiceCategory(ClientConfig client) {
        var v = client.voice;
        return ConfigCategory.createBuilder()
                .name(tr("friedai.config.category.voice"))
                .option(booleanOption("friedai.config.voice.enabled", true, () -> v.enabled, x -> v.enabled = x))
                .option(Option.<String>createBuilder()
                        .name(tr("friedai.config.voice.model"))
                        .binding("base", () -> v.whisperModel, x -> v.whisperModel = x)
                        .controller(o -> DropdownStringControllerBuilder.create(o).values(WHISPER_MODELS).allowAnyValue(false))
                        .build())
                .option(Option.<String>createBuilder()
                        .name(tr("friedai.config.voice.language"))
                        .binding("auto", () -> v.language, x -> v.language = x.isBlank() ? "auto" : x.strip())
                        .controller(StringControllerBuilder::create)
                        .build())
                .build();
    }

    private static Option<Boolean> booleanOption(
            String nameKey, boolean def, java.util.function.Supplier<Boolean> getter, java.util.function.Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(tr(nameKey))
                .binding(def, getter, setter)
                .controller(BooleanControllerBuilder::create)
                .build();
    }

    private static void toast(Component message) {
        Minecraft mc = Minecraft.getInstance();
        SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, tr("friedai.config.title"), message);
    }
}
