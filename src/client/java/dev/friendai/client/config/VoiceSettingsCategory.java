package dev.friendai.client.config;

import static dev.friendai.client.config.SettingsWidgets.blankToNull;
import static dev.friendai.client.config.SettingsWidgets.booleanOption;
import static dev.friendai.client.config.SettingsWidgets.nullToEmpty;
import static dev.friendai.client.config.SettingsWidgets.progressToast;
import static dev.friendai.client.config.SettingsWidgets.scoped;
import static dev.friendai.client.config.SettingsWidgets.stringOption;
import static dev.friendai.client.config.SettingsWidgets.toast;
import static dev.friendai.client.config.SettingsWidgets.tr;
import static dev.friendai.client.config.SettingsWidgets.trimmedToNull;

import dev.friendai.agent.turn.ErrorMessages;
import dev.friendai.client.FriendaiClient;
import dev.friendai.client.audio.LocalSttBundleManager;
import dev.friendai.client.audio.VoiceModelManager;
import dev.friendai.client.tts.SpeechPlayback;
import dev.friendai.client.tts.SpeechRuntime;
import dev.friendai.client.voice.MicCapture;
import dev.friendai.client.voice.SpeechToText;
import dev.friendai.client.voice.VoiceRuntime;
import dev.friendai.common.Components;
import dev.friendai.features.speech.CloudField;
import dev.friendai.features.speech.PcmConverter;
import dev.friendai.features.speech.ResolvedTts;
import dev.friendai.features.speech.VoiceErrors;
import dev.friendai.features.speech.VoicePreset;
import dev.friendai.features.speech.VoiceReadiness;
import dev.friendai.features.speech.stt.LocalSttCatalog;
import dev.friendai.features.speech.stt.LocalSttCatalog.LocalSttModel;
import dev.friendai.features.speech.stt.SttPresets;
import dev.friendai.features.speech.tts.PlaybackBackends;
import dev.friendai.features.speech.tts.TtsPresets;
import dev.friendai.features.speech.tts.VoiceModel;
import dev.friendai.features.speech.tts.VoiceModels;
import dev.friendai.features.speech.wake.WakeModelCatalog;
import dev.friendai.foundation.session.ClientSessionId;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.SecretResolver;
import dev.friendai.infrastructure.config.VoiceConfig;
import dev.friendai.infrastructure.config.VoiceDraft;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Voice tab: recognition, synthesis, the local model downloads and the provider fields that belong to the
 * selected preset. Widgets write into the open draft; nothing reaches the live configuration before Save.
 */
final class VoiceSettingsCategory {
    /** What the tab needs from the screen: the rebuild it triggers, the draft flush and the listener gate. */
    interface Host {
        boolean applying();

        void applyPending();

        void reopen(Screen parent, ClientConfig client, boolean sttReveal, boolean ttsReveal);

        /** The open session's generation, or -1 when the screen has none. */
        long generation();

        /** True while the screen that started the download is still the open one. */
        boolean current(long generation);

        /** Opens a documentation link through the screen lifecycle, so a rebuild keeps the session. */
        void confirmLink(Screen screen, String url);
    }

    private VoiceSettingsCategory() {}

    static ConfigCategory build(Screen parent, ClientConfig client, VoiceDraft draft, Host host) {
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
                VoiceSettingsCategory::sttProviderLabel);
        recognition.add(sttEnabled);
        recognition.add(sttProvider);
        sttProvider.addListener((option, value) -> {
            if (!host.applying()) {
                host.reopen(parent, client, sttReveal, ttsReveal);
            }
        });

        if (sttPreset.local()) {
            recognition.addAll(localRecognitionOptions(parent, client, stt, sttReveal, ttsReveal, host));
        } else {
            ClientConfig.CloudSettings sttCloud = cloud(stt.cloud, sttPreset);
            recognition.addAll(cloudOptions(
                    sttPreset, sttCloud, sttReveal, () -> host.reopen(parent, client, !sttReveal, ttsReveal)));
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
                .action((screen, button) -> testRecording(client, host))
                .build();
        recognition.add(sttLanguage);
        recognition.add(inputDeviceOption(stt));
        recognition.add(maxRecord);
        recognition.addAll(wakeOptions(client));
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
                VoiceSettingsCategory::ttsProviderLabel);
        synthesis.add(ttsEnabled);
        synthesis.add(booleanOption(
                "friendai.config.voice.tts.stream",
                "friendai.config.voice.tts.stream.tooltip",
                true,
                () -> client.voice.streamReplies,
                value -> client.voice.streamReplies = value));
        synthesis.add(ttsProvider);
        ttsProvider.addListener((option, value) -> {
            if (!host.applying()) {
                host.reopen(parent, client, sttReveal, ttsReveal);
            }
        });

        if (ttsPreset.local()) {
            synthesis.addAll(localSynthesisOptions(parent, client, tts, sttReveal, ttsReveal, host));
        } else {
            ClientConfig.CloudSettings ttsCloud = cloud(tts.cloud, ttsPreset);
            synthesis.addAll(cloudOptions(
                    ttsPreset, ttsCloud, ttsReveal, () -> host.reopen(parent, client, sttReveal, !ttsReveal)));
            // A cloud voice without a key cannot speak; the runtime's own message names the field.
            synthesis.add(statusLabel(synthesisReadiness(tts), "friendai.config.status.ready"));
            synthesis.add(cloudSpeedOption(ttsCloud, ttsPreset.id()));
            if (ttsPreset.paid()) {
                synthesis.add(LabelOption.create(tr("friendai.config.voice.tts.paid_notice")));
            }
            if (ttsPreset.docsUrl() != null) {
                synthesis.add(docsButton(ttsPreset, host));
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
                    host.applyPending();
                    SpeechRuntime.test(tts);
                })
                .build();
        Option<Boolean> spatial = Option.<Boolean>createBuilder()
                .name(tr("friendai.config.voice.tts.spatial"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.spatial.tooltip")))
                .binding(true, () -> tts.spatial, value -> tts.spatial = value)
                .controller(BooleanControllerBuilder::create)
                .build();
        Option<String> playback = Option.<String>createBuilder()
                .name(tr("friendai.config.voice.tts.playback"))
                .description(OptionDescription.of(tr("friendai.config.voice.tts.playback.tooltip")))
                .binding(PlaybackBackends.BUILTIN, () -> tts.playback, value -> tts.playback = value)
                .controller(o -> CyclingListControllerBuilder.<String>create(o)
                        .values(PlaybackBackends.ids())
                        .valueFormatter(VoiceSettingsCategory::playbackLabel))
                .build();
        synthesis.add(volume);
        synthesis.add(maxChars);
        synthesis.add(outputDeviceOption(tts));
        synthesis.add(spatial);
        synthesis.add(playback);
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

    /**
     * Hands-free listening: off by default, one of the phrases the installed bundle can actually detect, and how much
     * pre-wake audio the bounded ring keeps. The phrase list is the catalogue's, because the spotter matches tokens,
     * not arbitrary text.
     */
    private static List<Option<?>> wakeOptions(ClientConfig client) {
        WakeModelCatalog.WakeModel model = WakeModelCatalog.defaultModel();
        List<String> phrases =
                model.phrases().stream().map(WakeModelCatalog.WakePhrase::text).toList();
        Option<Boolean> enabled = booleanOption(
                "friendai.config.voice.wake.enabled",
                "friendai.config.voice.wake.enabled.tooltip",
                false,
                () -> client.voice.wake.enabled,
                value -> client.voice.wake.enabled = value);
        Option<String> phrase = Option.<String>createBuilder()
                .name(tr("friendai.config.voice.wake.phrase"))
                .description(OptionDescription.of(tr("friendai.config.voice.wake.phrase.tooltip")))
                .binding(
                        model.defaultPhrase().text(),
                        () -> client.voice.wake.phrase,
                        value -> client.voice.wake.phrase = value)
                .controller(o -> CyclingListControllerBuilder.<String>create(o)
                        .values(phrases)
                        .valueFormatter(Component::literal))
                .build();
        Option<Integer> preRoll = Option.<Integer>createBuilder()
                .name(tr("friendai.config.voice.wake.pre_roll"))
                .description(OptionDescription.of(tr("friendai.config.voice.wake.pre_roll.tooltip")))
                .binding(2, () -> client.voice.wake.preRollSec, value -> client.voice.wake.preRollSec = value)
                .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 5))
                .build();
        return List.of(enabled, phrase, preRoll);
    }

    private static List<Option<?>> localRecognitionOptions(
            Screen parent,
            ClientConfig client,
            ClientConfig.SttSection stt,
            boolean sttReveal,
            boolean ttsReveal,
            Host host) {
        List<String> ids = new ArrayList<>();
        for (LocalSttModel entry : LocalSttCatalog.all()) {
            ids.add(entry.id());
        }
        // An id this build does not know is kept selectable instead of being rewritten by the widget; its row
        // then explains that it is not part of the catalogue.
        if (stt.local.model != null && !stt.local.model.isBlank() && !ids.contains(stt.local.model)) {
            ids.add(stt.local.model);
        }
        Option<String> model = Option.<String>createBuilder()
                .name(tr("friendai.config.voice.model"))
                .description(OptionDescription.of(tr("friendai.config.voice.model.tooltip")))
                .binding("base", () -> stt.local.model, value -> stt.local.model = value)
                .controller(o ->
                        DropdownStringControllerBuilder.create(o).values(ids).allowAnyValue(false))
                .build();
        // The row describes the pending selection, while readiness comes from the same runtime decision a
        // recording makes. A missing model is reported with the runtime's own message so the row and a
        // failed recording say the same words.
        LabelOption status = recognitionStatus(model.pendingValue());
        ButtonOption download = ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.download"))
                .action((screen, button) -> {
                    LocalSttModel selected =
                            LocalSttCatalog.byId(model.pendingValue()).orElse(null);
                    if (selected == null) {
                        toast(tr("friendai.config.voice.model.unknown"));
                        return;
                    }
                    int[] lastStep = {-1};
                    long generation = host.generation();
                    VoiceRuntime.manager()
                            .install(selected, new LocalSttBundleManager.ProgressListener() {
                                @Override
                                public void fileStarted(LocalSttCatalog.LocalModelFile file) {}

                                @Override
                                public void bytesWritten(long written, long total) {
                                    int percent = total <= 0 ? 0 : (int) Math.min(100, written * 100 / total);
                                    if (percent / 10 == lastStep[0]) {
                                        return;
                                    }
                                    lastStep[0] = percent / 10;
                                    Minecraft.getInstance().execute(() -> {
                                        if (!host.current(generation)) return;
                                        toast(Component.translatable("friendai.config.voice.downloading", percent));
                                    });
                                }
                            })
                            .whenComplete((path, error) -> Minecraft.getInstance()
                                    .execute(() -> {
                                        if (!host.current(generation)) return;
                                        if (error != null) {
                                            toast(Components.of(ErrorMessages.forUser(error)));
                                        } else {
                                            VoiceRuntime.invalidate();
                                            toast(tr("friendai.config.voice.status.installed"));
                                            host.reopen(parent, client, sttReveal, ttsReveal);
                                        }
                                    }));
                })
                .build();
        // The installed label and the download target follow the selection.
        model.addListener((option, value) -> {
            if (!host.applying()) {
                host.reopen(parent, client, sttReveal, ttsReveal);
            }
        });
        return List.of(model, status, download);
    }

    /** The selected catalog row: the runtime's refusal, or the model's size, languages, mode and licence. */
    private static LabelOption recognitionStatus(String modelId) {
        String effective = modelId == null ? FriendaiClient.clientConfig().voice.stt.local.model : modelId;
        VoiceRuntime.SttStatus status = VoiceRuntime.sttStatus(FriendaiClient.clientConfig().voice.stt, effective);
        if (status.problem() != null) {
            return LabelOption.create(Components.of(status.problem()));
        }
        LocalSttModel selected = LocalSttCatalog.byId(effective).orElse(null);
        if (selected == null) {
            return LabelOption.create(tr("friendai.config.voice.model.unknown"));
        }
        String languages = selected.languages().size() == 1
                        && LocalSttCatalog.MULTILINGUAL.equals(
                                selected.languages().get(0))
                ? tr("friendai.config.voice.model.multilingual").getString()
                : String.join(", ", selected.languages());
        String mode = selected.streaming()
                ? tr("friendai.config.voice.model.mode.streaming").getString()
                : tr("friendai.config.voice.model.mode.offline").getString();
        return LabelOption.create(Component.translatable(
                "friendai.config.voice.model.summary",
                selected.displayName(),
                selected.totalBytes() / 1_000_000L,
                languages,
                mode,
                selected.license()));
    }

    private static List<Option<?>> localSynthesisOptions(
            Screen parent,
            ClientConfig client,
            ClientConfig.TtsSection tts,
            boolean sttReveal,
            boolean ttsReveal,
            Host host) {
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
                        .valueFormatter(VoiceSettingsCategory::localVoiceLabel))
                .build();
        LabelOption status = statusLabel(synthesisReadiness(tts), "friendai.config.voice.tts.local.status.installed");
        LabelOption license = LabelOption.create(
                Component.translatable("friendai.config.voice.tts.local.license", effective.license()));
        ButtonOption download = ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.tts.download"))
                .action((screen, button) -> {
                    int[] lastDownloadStep = {-1};
                    int[] lastInstallStep = {-1};
                    long generation = host.generation();
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
                                        if (!host.current(generation)) return;
                                        if (error != null) {
                                            toast(Components.of(ErrorMessages.forUser(error)));
                                        } else {
                                            SpeechRuntime.invalidate();
                                            toast(tr("friendai.config.voice.tts.local.status.installed"));
                                            host.reopen(parent, client, sttReveal, ttsReveal);
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
            if (!host.applying()) {
                host.reopen(parent, client, sttReveal, ttsReveal);
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

    private static ButtonOption docsButton(VoicePreset preset, Host host) {
        return ButtonOption.createBuilder()
                .name(tr("friendai.config.voice.tts.docs"))
                .action((screen, button) -> host.confirmLink(screen, preset.docsUrl()))
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

    /** The entry for one provider; entries are per provider so one key never moves to another. */
    private static ClientConfig.CloudSettings cloud(Map<String, ClientConfig.CloudSettings> cloud, VoicePreset preset) {
        return cloud.computeIfAbsent(preset.id(), id -> new ClientConfig.CloudSettings());
    }

    /** Records three seconds from the microphone and shows what was recognized. */
    private static void testRecording(ClientConfig client, Host host) {
        host.applyPending();
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
            MicCapture.Capture capture = null;
            try {
                capture = mic.start(stt.inputDevice, 5);
                Thread.sleep(3_000);
                byte[] pcm = capture == null ? null : mic.stop(capture);
                if (pcm == null || !VoiceRuntime.isCurrentOperation(sessionId, generation)) return;
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
                if (capture != null) mic.stop(capture);
                Minecraft.getInstance().execute(() -> {
                    if (VoiceRuntime.isCurrentOperation(sessionId, generation)) {
                        toast(Components.of(UserMessage.of("friendai.voice.error.no_mic")));
                    }
                });
            }
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

    private static Component playbackLabel(String id) {
        return PlaybackBackends.VOICECHAT.equals(id)
                ? tr("friendai.config.voice.tts.playback.voicechat")
                : tr("friendai.config.voice.tts.playback.builtin");
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
}
