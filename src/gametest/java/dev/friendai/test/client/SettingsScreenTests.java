package dev.friendai.test.client;

import com.google.gson.JsonParser;
import dev.friendai.client.FriendaiClient;
import dev.friendai.client.config.FriendaiConfigScreen;
import dev.friendai.client.config.ModelCache;
import dev.friendai.features.speech.stt.SttPresets;
import dev.friendai.features.speech.tts.TtsPresets;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.testutil.MockHttpServer;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.YACLScreen;
import dev.isxander.yacl3.gui.controllers.PopupControllerScreen;
import dev.isxander.yacl3.gui.controllers.dropdown.DropdownStringController;
import dev.isxander.yacl3.gui.controllers.dropdown.DropdownStringControllerElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Real YACL screens in the isolated client-GameTest directory; never calls a paid service. */
public final class SettingsScreenTests implements FabricClientGameTest {
    private final List<String> failures = new ArrayList<>();
    private int passed;

    @Override
    public void runTest(ClientGameTestContext context) {
        runCases(context);
        var reload = context.computeOnClient(mc -> {
            mc.getLanguageManager().setSelected("ru_ru");
            return mc.reloadResourcePacks();
        });
        context.waitFor(mc -> reload.isDone());
        reload.join();
        runCases(context);
        context.setScreen(TitleScreen::new);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
        System.out.println("FRIENDAI_SETTINGS: all " + passed + " scenarios passed (English and Russian)");
    }

    private void runCases(ClientGameTestContext context) {
        check(context, "click a suggested model with custom input allowed", c -> {
            c.runOnClient(mc -> {
                ModelCache.put("anthropic", List.of("deepseek-v4-pro", "deepseek-v4-flash"));
                mc.setScreenAndShow(FriendaiConfigScreen.create(new TitleScreen()));
                var widget = (DropdownStringControllerElement) option("friendai.config.model")
                        .controller()
                        .provideWidget(screen(), Dimension.ofInt(20, 200, 300, 20));
                set("friendai.config.model", "dee");
                widget.createDropdownWidget();
                var popup = widget.dropdownWidget();
                int top = 0;
                while (top < screenHeight(mc) && !popup.isMouseOver(100, top)) top++;
                require(top < screenHeight(mc), "suggestions have no clickable area");
                int index = widget.computeMatchingValues().indexOf("deepseek-v4-flash");
                require(index >= 0, "expected suggestion is missing");
                double y = top + index * popup.entryHeight() + popup.entryHeight() / 2.0;
                mc.gui.screen().mouseMoved(100, y);
                mc.gui.screen().mouseClicked(new MouseButtonEvent(100, y, new MouseButtonInfo(0, 0)), false);
                require(
                        option("friendai.config.model").pendingValue().equals("deepseek-v4-flash"),
                        "click did not select the suggestion; actual="
                                + option("friendai.config.model").pendingValue());
                screen().finishOrSave();
                require(server().profiles.get("main").model.equals("deepseek-v4-flash"), "clicked model did not save");
            });
        });
        check(context, "all ordinary fields persist", c -> {
            c.runOnClient(mc -> {
                Map<String, Object> edits = Map.ofEntries(
                        Map.entry("friendai.config.base_url", "http://localhost:12345/v1"),
                        Map.entry("friendai.config.api_key", "test-key"),
                        Map.entry("friendai.config.model", "test-model"),
                        Map.entry("friendai.config.temperature", 1.2),
                        Map.entry("friendai.config.max_tokens", 2048),
                        Map.entry("friendai.config.companion.name", "Tester"),
                        Map.entry("friendai.config.companion.description", "Test description"),
                        Map.entry("friendai.config.companion.language", "ru"),
                        Map.entry("friendai.config.skills.enabled", false),
                        Map.entry("friendai.config.skills.approval", false),
                        Map.entry("friendai.config.skills.budget", 3200),
                        Map.entry("friendai.config.skills.hourly", 9),
                        Map.entry("friendai.config.agent.planner", "secondary"),
                        Map.entry("friendai.config.agent.planner_retries", 3),
                        Map.entry("friendai.config.chat.prefix", "!"),
                        Map.entry("friendai.config.chat.respond_to_name", false),
                        Map.entry("friendai.config.chat.listen_all", true),
                        Map.entry("friendai.config.chat.guests_enabled", false),
                        Map.entry("friendai.config.chat.hearing_radius", 48),
                        Map.entry("friendai.config.chat.guest_cooldown_ms", 9000),
                        Map.entry("friendai.config.memory.enabled", false),
                        Map.entry("friendai.config.memory.max_fact_chars", 240),
                        Map.entry("friendai.config.memory.max_facts_per_player", 35),
                        Map.entry("friendai.config.memory.max_world_facts", 90),
                        Map.entry("friendai.config.voice.enabled", false),
                        Map.entry("friendai.config.voice.language", "ru"),
                        Map.entry("friendai.config.voice.max_record", 60),
                        Map.entry("friendai.config.voice.tts.enabled", true),
                        Map.entry("friendai.config.voice.tts.volume", 0.4),
                        Map.entry("friendai.config.voice.tts.max_chars", 180),
                        Map.entry("friendai.config.voice.tts.speed", 1.3),
                        Map.entry("friendai.config.voice.tts.spatial", false),
                        Map.entry("friendai.config.voice.tts.speak_guests", true));
                edits.forEach(SettingsScreenTests::set);
                screen().finishOrSave();
                // Reopen from the persisted files, rather than inspecting the old widgets.
                mc.setScreenAndShow(FriendaiConfigScreen.create(new TitleScreen()));
                edits.forEach((key, value) -> {
                    if (!key.equals("friendai.config.api_key")) {
                        require(option(key).pendingValue().equals(value), "round trip failed: " + key);
                    }
                });
                require(server().profiles.get("main").apiKey.equals("test-key"), "masked key was not saved");
            });
        });
        check(context, "dropdown lifecycle", c -> {
            c.runOnClient(mc -> {
                YACLScreen screen = screen();
                var widget = (DropdownStringControllerElement) option("friendai.config.model")
                        .controller()
                        .provideWidget(screen, Dimension.ofInt(20, 60, 200, 20));
                set("friendai.config.model", "dropdown-test");
                widget.createDropdownWidget();
                require(mc.gui.screen() instanceof PopupControllerScreen, "dropdown did not open");
                mc.gui.screen().onClose();
                require(mc.gui.screen() == screen, "dropdown did not return to settings");
                set("friendai.config.chat.prefix", "#");
                screen.finishOrSave();
                require(server().chat.prefix.equals("#"), "save after dropdown failed");
            });
        });
        check(context, "edits survive voice rebuild", c -> {
            c.runOnClient(mc -> {
                set("friendai.config.companion.name", "Pending companion");
                set("friendai.config.chat.hearing_radius", 48);
                set("friendai.config.memory.max_world_facts", 75);
                set("friendai.config.voice.max_record", 45);
                set("friendai.config.voice.stt.provider", "openai");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                require(
                        option("friendai.config.companion.name").pendingValue().equals("Pending companion"),
                        "companion draft was lost");
                require(
                        option("friendai.config.chat.hearing_radius")
                                .pendingValue()
                                .equals(48),
                        "chat draft was lost");
                require(
                        option("friendai.config.memory.max_world_facts")
                                .pendingValue()
                                .equals(75),
                        "memory draft was lost");
                require(screen().pendingChanges(), "rebuild disabled Save for unsaved edits");
                screen().finishOrSave();
                require(server().companion.name.equals("Pending companion"), "rebuilt values were not saved");
                require(FriendaiClient.clientConfig().voice.stt.maxRecordSec == 45, "voice field was not saved");
            });
        });
        check(context, "cloud fields save without reveal", c -> {
            c.runOnClient(mc -> set("friendai.config.voice.stt.provider", "openai"));
            c.waitTicks(2);
            c.runOnClient(mc -> {
                set("friendai.config.voice.field.key", "test-key");
                set("friendai.config.voice.field.model", "draft-stt-model");
                screen().finishOrSave();
                var saved = FriendaiClient.clientConfig().voice.stt.cloud.get("openai");
                require("test-key".equals(saved.apiKey), "cloud key reverted on Save");
                require("draft-stt-model".equals(saved.model), "cloud model reverted on Save");
            });
        });
        check(context, "LLM rebuild keeps voice and other tabs", c -> {
            c.runOnClient(mc -> {
                set("friendai.config.voice.max_record", 55);
                set("friendai.config.chat.prefix", "!");
                set("friendai.config.model", "draft-model");
                button("friendai.config.api_key.show");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                require(
                        option("friendai.config.voice.max_record")
                                .pendingValue()
                                .equals(55),
                        "voice draft was lost");
                require(option("friendai.config.chat.prefix").pendingValue().equals("!"), "chat draft was lost");
                require(option("friendai.config.model").pendingValue().equals("draft-model"), "model draft was lost");
            });
        });
        check(context, "profile selection reloads its fields", c -> {
            c.runOnClient(mc -> {
                set("friendai.config.model", "edited-main");
                set("friendai.config.profile", "secondary");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                require(
                        option("friendai.config.model").pendingValue().equals("secondary-model"),
                        "profile fields did not switch");
                set("friendai.config.profile", "main");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> require(
                    option("friendai.config.model").pendingValue().equals("edited-main"),
                    "leaving a profile lost its edits"));
        });
        check(context, "documentation returns to editable settings", c -> {
            c.runOnClient(mc -> set("friendai.config.voice.tts.provider", "elevenlabs"));
            c.waitTicks(2);
            c.runOnClient(mc -> {
                button("friendai.config.voice.tts.docs");
                require(mc.gui.screen() instanceof ConfirmLinkScreen, "documentation confirmation missing");
            });
            require(
                    c.tryClickScreenButton("gui.cancel") || c.tryClickScreenButton("gui.no"),
                    "documentation Cancel missing");
            c.waitTicks(2);
            c.runOnClient(mc -> {
                set("friendai.config.chat.prefix", "docs");
                screen().finishOrSave();
                require(server().chat.prefix.equals("docs"), "return from documentation closed the session");
            });
        });
        check(context, "save twice", c -> {
            c.runOnClient(mc -> {
                set("friendai.config.chat.prefix", "first");
                screen().finishOrSave();
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                set("friendai.config.chat.prefix", "second");
                screen().finishOrSave();
                require(server().chat.prefix.equals("second"), "second Save failed");
            });
        });
        check(context, "cancel after rebuild", c -> {
            c.runOnClient(mc -> {
                set("friendai.config.chat.prefix", "discard-me");
                set("friendai.config.voice.stt.provider", "openai");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                screen().onClose();
                require(server().chat.prefix.equals("@"), "Cancel persisted server edits");
                require(
                        FriendaiClient.clientConfig().voice.stt.provider.equals("local"),
                        "Cancel published voice edits");
            });
        });
        check(context, "reset and undo across rebuilds", c -> {
            c.runOnClient(mc -> {
                set("friendai.config.companion.description", "Saved description");
                set("friendai.config.voice.max_record", 50);
                screen().finishOrSave();
                set("friendai.config.chat.prefix", "unsaved");
                button("friendai.config.api_key.show");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                screen().undo();
                require(
                        option("friendai.config.chat.prefix").pendingValue().equals("@"),
                        "Undo kept prior-screen edits");
                require(
                        option("friendai.config.voice.max_record")
                                .pendingValue()
                                .equals(50),
                        "Undo discarded saved edits");
                option("friendai.config.companion.description").requestSetDefault();
                option("friendai.config.voice.max_record").requestSetDefault();
                screen().finishOrSave();
                require(
                        server().companion.description.equals(new ServerConfig.CompanionSection().description),
                        "description reset used the saved value as default");
                require(FriendaiClient.clientConfig().voice.stt.maxRecordSec == 30, "numeric reset failed");
            });
        });
        check(context, "voice selection and active tab survive rebuild", c -> {
            c.runOnClient(mc -> {
                screen().tabManager
                        .setCurrentTab(screen().tabNavigationBar.getTabs().getLast(), false);
                set("friendai.config.voice.model", "tiny");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                require(
                        screen().tabManager.getCurrentTab()
                                == screen().tabNavigationBar.getTabs().getLast(),
                        "voice selection switched tabs");
                require(
                        option("friendai.config.voice.model").pendingValue().equals("tiny"),
                        "Whisper selection reverted");
                set("friendai.config.voice.tts.local.voice", "en_US-lessac-medium");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                require(
                        option("friendai.config.voice.tts.local.voice")
                                .pendingValue()
                                .equals("en_US-lessac-medium"),
                        "local voice selection reverted");
                screen().finishOrSave();
                require(
                        FriendaiClient.clientConfig().voice.stt.local.model.equals("tiny"),
                        "Whisper model was not saved");
            });
        });
        check(context, "audio device selections and reset", c -> {
            c.runOnClient(mc -> {
                for (String key : List.of("friendai.config.voice.device", "friendai.config.voice.tts.output_device")) {
                    var values = ((DropdownStringController) option(key).controller()).getAllowedValues();
                    set(key, values.getLast());
                }
                screen().finishOrSave();
                for (String key : List.of("friendai.config.voice.device", "friendai.config.voice.tts.output_device")) {
                    var values = ((DropdownStringController) option(key).controller()).getAllowedValues();
                    require(option(key).pendingValue().equals(values.getLast()), "device selection did not save");
                    option(key).requestSetDefault();
                }
                if (screen().pendingChanges()) screen().finishOrSave();
                require(
                        FriendaiClient.clientConfig().voice.stt.inputDevice == null,
                        "input default stored its translated label");
                require(
                        FriendaiClient.clientConfig().voice.tts.outputDevice == null,
                        "output default stored its translated label");
            });
        });
        check(context, "copying an edited key keeps Save enabled", c -> {
            c.runOnClient(mc -> set("friendai.config.voice.stt.provider", "openai"));
            c.waitTicks(2);
            c.runOnClient(mc -> {
                screen().finishOrSave();
                set("friendai.config.voice.field.key", "copy-only-key");
                String clipboard = mc.keyboardHandler.getClipboard();
                try {
                    button("friendai.config.voice.field.key.copy");
                    require(screen().pendingChanges(), "Copy consumed the only pending edit");
                    screen().finishOrSave();
                    require(
                            "copy-only-key"
                                    .equals(FriendaiClient.clientConfig()
                                            .voice
                                            .stt
                                            .cloud
                                            .get("openai")
                                            .apiKey),
                            "copied key was not saved");
                } finally {
                    mc.keyboardHandler.setClipboard(clipboard);
                }
            });
        });
        check(context, "cloud reveal and copy preserve pending key", c -> {
            c.runOnClient(mc -> set("friendai.config.voice.stt.provider", "openai"));
            c.waitTicks(2);
            c.runOnClient(mc -> {
                set("friendai.config.voice.field.key", "pending-test-key");
                button("friendai.config.voice.field.key.show");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> {
                require(
                        option("friendai.config.voice.field.key").pendingValue().equals("pending-test-key"),
                        "reveal lost the key");
                String clipboard = mc.keyboardHandler.getClipboard();
                try {
                    button("friendai.config.voice.field.key.copy");
                    require(mc.keyboardHandler.getClipboard().equals("pending-test-key"), "copy used a stale key");
                } finally {
                    mc.keyboardHandler.setClipboard(clipboard);
                }
                set("friendai.config.voice.stt.provider", "groq");
            });
            c.waitTicks(2);
            c.runOnClient(mc -> set("friendai.config.voice.stt.provider", "openai"));
            c.waitTicks(2);
            c.runOnClient(mc -> {
                require(
                        option("friendai.config.voice.field.key").pendingValue().equals("pending-test-key"),
                        "provider round trip lost the key");
                screen().finishOrSave();
                require(
                        !"pending-test-key"
                                .equals(FriendaiClient.clientConfig()
                                        .voice
                                        .stt
                                        .cloud
                                        .get("groq")
                                        .apiKey),
                        "switching providers copied the key");
            });
        });
        check(context, "LLM model loading preserves edits made during request", c -> {
            try (var http = new MockHttpServer()) {
                http.enqueueDelayed(
                        200, "{\"data\":[{\"id\":\"listed-model\"}]}".getBytes(StandardCharsets.UTF_8), 500);
                configureLlm(c, http);
                YACLScreen previous = c.computeOnClient(mc -> {
                    button("friendai.config.load_models");
                    set("friendai.config.chat.prefix", "during-request");
                    set("friendai.config.model", "typed-after-request");
                    return screen();
                });
                c.waitFor(mc -> mc.gui.screen() != previous);
                c.runOnClient(mc -> {
                    require(
                            option("friendai.config.chat.prefix").pendingValue().equals("during-request"),
                            "model fetch lost other tabs");
                    require(
                            option("friendai.config.model").pendingValue().equals("typed-after-request"),
                            "model fetch reverted later typing");
                    require(
                            ((DropdownStringController)
                                            option("friendai.config.model").controller())
                                    .getAllowedValues()
                                    .contains("listed-model"),
                            "loaded models were not shown");
                    require(
                            http.requests().getFirst().header("Authorization").equals("Bearer pending-llm-key"),
                            "fetch used saved credentials");
                });
            }
        });
        check(context, "LLM connection test uses pending values", c -> {
            try (var http = new MockHttpServer()) {
                http.enqueue(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
                configureLlm(c, http);
                c.runOnClient(mc -> button("friendai.config.test_connection"));
                c.waitFor(mc -> !http.requests().isEmpty());
                var request = http.requests().getFirst();
                require(request.path().equals("/chat/completions"), "wrong connection endpoint");
                require(
                        JsonParser.parseString(request.body())
                                .getAsJsonObject()
                                .get("model")
                                .getAsString()
                                .equals("pending-llm-model"),
                        "connection test used the saved model");
                c.runOnClient(mc -> require(
                        server().profiles.get("main").apiKey.isEmpty(), "connection test persisted credentials"));
            }
        });
        check(context, "voice test uses unsaved synthesis settings", c -> {
            try (var http = new MockHttpServer()) {
                // An error response prevents actual playback and still captures the resolved draft.
                http.enqueue(400, "{\"error\":{\"message\":\"test response\"}}");
                c.runOnClient(mc -> set("friendai.config.voice.tts.provider", "openai"));
                c.waitTicks(2);
                c.runOnClient(mc -> {
                    set("friendai.config.voice.field.key", "pending-tts-key");
                    set("friendai.config.voice.field.model", "pending-tts-model");
                    set("friendai.config.voice.field.voice", "nova");
                    set("friendai.config.voice.field.base_url", http.baseUrl());
                    button("friendai.config.voice.tts.test");
                });
                c.waitFor(mc -> !http.requests().isEmpty());
                var request = http.requests().getFirst();
                var body = JsonParser.parseString(request.body()).getAsJsonObject();
                require(request.path().equals("/audio/speech"), "wrong synthesis test endpoint");
                require(body.get("model").getAsString().equals("pending-tts-model"), "voice test used saved model");
                require(body.get("voice").getAsString().equals("nova"), "voice test used saved voice");
                c.runOnClient(mc -> require(
                        FriendaiClient.clientConfig().voice.tts.provider.equals("local"),
                        "voice test published settings"));
            }
        });
        check(context, "async model loading leaves documentation open", c -> {
            try (var http = new MockHttpServer()) {
                http.enqueueDelayed(200, "{\"data\":[{\"id\":\"modal-model\"}]}".getBytes(StandardCharsets.UTF_8), 500);
                c.runOnClient(mc -> set("friendai.config.voice.tts.provider", "openai"));
                c.waitTicks(2);
                configureLlm(c, http);
                c.runOnClient(mc -> {
                    button("friendai.config.load_models");
                    screen().tabManager
                            .setCurrentTab(screen().tabNavigationBar.getTabs().getLast(), false);
                    button("friendai.config.voice.tts.docs");
                });
                c.waitFor(mc -> !http.requests().isEmpty());
                c.waitTicks(20);
                c.runOnClient(mc ->
                        require(mc.gui.screen() instanceof ConfirmLinkScreen, "model fetch dismissed documentation"));
                require(
                        c.tryClickScreenButton("gui.cancel") || c.tryClickScreenButton("gui.no"),
                        "documentation Cancel missing");
                c.waitTicks(3);
                c.runOnClient(mc -> {
                    require(
                            ((DropdownStringController)
                                            option("friendai.config.model").controller())
                                    .getAllowedValues()
                                    .contains("modal-model"),
                            "model list was not refreshed on return");
                    set("friendai.config.chat.prefix", "after-docs");
                    screen().finishOrSave();
                    require(server().chat.prefix.equals("after-docs"), "documentation return was stale");
                });
            }
        });
        List<String> llmProviders = context.computeOnClient(mc ->
                ((DropdownStringController) option("friendai.config.provider").controller()).getAllowedValues());
        for (String id : llmProviders) {
            check(context, "LLM provider " + id, c -> {
                c.runOnClient(mc -> set("friendai.config.provider", id));
                c.waitTicks(2);
                c.runOnClient(mc -> {
                    require(option("friendai.config.provider").pendingValue().equals(id), "LLM switch reverted");
                    screen().finishOrSave();
                    require(server().profiles.get("main").provider.equals(id), "LLM provider was not saved");
                });
            });
        }
        for (String id : SttPresets.ids()) {
            check(context, "recognition provider " + id, c -> {
                c.runOnClient(mc -> set("friendai.config.voice.stt.provider", id));
                c.waitTicks(2);
                c.runOnClient(mc -> {
                    require(
                            option("friendai.config.voice.stt.provider")
                                    .pendingValue()
                                    .equals(id),
                            "wrong STT provider");
                    if (!id.equals("local")) set("friendai.config.voice.field.key", "test-" + id);
                    if (id.equals("openai") || id.equals("groq")) {
                        set("friendai.config.voice.field.model", "stt-model-" + id);
                        set("friendai.config.voice.field.base_url", "http://localhost:12345/stt");
                    } else if (id.equals("yandex")) {
                        set("friendai.config.voice.field.folder_id", "test-stt-folder");
                    }
                    set("friendai.config.voice.max_record", 40);
                    screen().finishOrSave();
                    require(FriendaiClient.clientConfig().voice.stt.provider.equals(id), "provider not saved");
                    if (!id.equals("local")) {
                        require(
                                ("test-" + id)
                                        .equals(FriendaiClient.clientConfig()
                                                .voice
                                                .stt
                                                .cloud
                                                .get(id)
                                                .apiKey),
                                "provider key not saved");
                        var saved =
                                FriendaiClient.clientConfig().voice.stt.cloud.get(id);
                        if (id.equals("yandex")) {
                            require("test-stt-folder".equals(saved.folderId), "STT folder not saved");
                        } else {
                            require(("stt-model-" + id).equals(saved.model), "STT model not saved");
                            require("http://localhost:12345/stt".equals(saved.baseUrl), "STT base URL not saved");
                        }
                    }
                });
            });
        }
        for (String id : TtsPresets.ids()) {
            check(context, "synthesis provider " + id, c -> {
                c.runOnClient(mc -> set("friendai.config.voice.tts.provider", id));
                c.waitTicks(2);
                c.runOnClient(mc -> {
                    require(
                            option("friendai.config.voice.tts.provider")
                                    .pendingValue()
                                    .equals(id),
                            "wrong TTS provider");
                    if (!id.equals("local")) set("friendai.config.voice.field.key", "test-" + id);
                    set("friendai.config.voice.tts.speed", 1.1);
                    if (id.equals("openai") || id.equals("elevenlabs")) {
                        set("friendai.config.voice.field.model", "tts-model-" + id);
                    }
                    if (id.equals("openai")) {
                        set("friendai.config.voice.field.voice", "nova");
                        set("friendai.config.voice.field.base_url", "http://localhost:12345/tts");
                    } else if (id.equals("elevenlabs")) {
                        set("friendai.config.voice.field.voice_id", "test-voice-id");
                    } else if (id.equals("yandex")) {
                        set("friendai.config.voice.field.voice", "filipp");
                        set("friendai.config.voice.field.folder_id", "test-tts-folder");
                        set("friendai.config.voice.field.language", "ru-RU");
                    }
                    set("friendai.config.voice.tts.volume", 0.5);
                    screen().finishOrSave();
                    require(FriendaiClient.clientConfig().voice.tts.provider.equals(id), "provider not saved");
                    if (!id.equals("local")) {
                        require(
                                ("test-" + id)
                                        .equals(FriendaiClient.clientConfig()
                                                .voice
                                                .tts
                                                .cloud
                                                .get(id)
                                                .apiKey),
                                "provider key not saved");
                        var saved =
                                FriendaiClient.clientConfig().voice.tts.cloud.get(id);
                        require(saved.speed == 1.1, "cloud speed not saved");
                        switch (id) {
                            case "openai" -> {
                                require("tts-model-openai".equals(saved.model), "OpenAI TTS model not saved");
                                require("nova".equals(saved.voice), "OpenAI TTS voice not saved");
                                require("http://localhost:12345/tts".equals(saved.baseUrl), "OpenAI TTS URL not saved");
                            }
                            case "elevenlabs" ->
                                require("test-voice-id".equals(saved.voice), "ElevenLabs voice ID not saved");
                            case "yandex" -> {
                                require("filipp".equals(saved.voice), "Yandex voice not saved");
                                require("test-tts-folder".equals(saved.folderId), "TTS folder not saved");
                                require("ru-RU".equals(saved.language), "TTS language not saved");
                            }
                        }
                    }
                });
            });
        }
    }

    private void check(ClientGameTestContext context, String name, Consumer<ClientGameTestContext> test) {
        try {
            context.runOnClient(mc -> {
                mc.setScreenAndShow(new TitleScreen());
                var configuration = FriendaiClient.runtime().configuration();
                configuration.save(ClientConfig.defaults());
                ServerConfig config = ServerConfig.defaults();
                config.profiles.get("main").apiKey = "";
                var secondary = new ServerConfig.ProfileConfig();
                secondary.model = "secondary-model";
                secondary.apiKey = "";
                config.profiles.put("secondary", secondary);
                configuration.saveServer(config);
                mc.setScreenAndShow(FriendaiConfigScreen.create(mc.gui.screen()));
            });
            test.accept(context);
            passed++;
            System.out.println("FRIENDAI_SETTINGS PASS: " + name);
        } catch (Throwable error) {
            failures.add(name + ": " + error);
            System.err.println("FRIENDAI_SETTINGS FAIL: " + name + ": " + error);
        }
    }

    private static YACLScreen screen() {
        if (!(Minecraft.getInstance().gui.screen() instanceof YACLScreen screen)) {
            throw new AssertionError("Expected Friendai settings, got "
                    + Minecraft.getInstance().gui.screen());
        }
        return screen;
    }

    private static Option<?> option(String key) {
        return screen().config.categories().stream()
                .flatMap(category -> category.groups().stream())
                .flatMap(group -> group.options().stream())
                .filter(option -> option.name().getContents() instanceof TranslatableContents text
                        && text.getKey().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing option " + key));
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(String key, T value) {
        ((Option<T>) option(key)).requestSet(value);
    }

    private static void button(String key) {
        ButtonOption button = (ButtonOption) option(key);
        button.action().accept(screen(), button);
    }

    private static ServerConfig server() {
        return FriendaiClient.runtime().configuration().loadServer();
    }

    private static void configureLlm(ClientGameTestContext context, MockHttpServer http) {
        context.runOnClient(mc -> {
            set("friendai.config.provider", "openai_compat");
            set("friendai.config.base_url", http.baseUrl());
            set("friendai.config.api_key", "pending-llm-key");
            set("friendai.config.model", "pending-llm-model");
        });
        context.waitTicks(2);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int screenHeight(Minecraft mc) {
        return mc.getWindow().getGuiScaledHeight();
    }
}
