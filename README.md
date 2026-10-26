# Friendai

An open-source Fabric mod that adds an AI companion to Minecraft. Talk to it in chat or by voice, and it will follow you, fetch items, walk to places and mine blocks for you.

> "Friendai, mine 10 logs" — the companion answers in chat, walks to the trees, chops them, picks up the drops and reports back.

## Requirements

- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) and [YACL](https://modrinth.com/mod/yacl) for the in-game settings screen. Without them the mod works and settings are edited in the config files.

The mod must be installed on both the client and the server (the integrated server in singleplayer counts).

## Quick start

1. Install the mod and set up a provider (see below).
2. In a world, run `/friendai summon`. Your companion appears next to you.
3. Talk to it:
   - in chat with a prefix: `@ come here`
   - in chat by name: `Friendai, mine 10 logs` or `Friendai, chop the tree next to me`
   - by voice: hold **V** (push-to-talk, rebindable in Controls) and speak.
4. `/friendai stop` cancels what it is doing, `/friendai dismiss` sends it away, `/friendai status` shows the provider and current task.

The same controls are in the **pause menu (Esc)**: the companion icon next to the report-bug icon opens a screen to summon, dismiss and stop your companion and to see its current task and recent tasks. Operators also get the brain log (messages, tool calls, errors) and debug tools: what the companion sees, its current prompt, a manual message and a manual tool run.

What it can do: follow you, stay, go to a point, pick up items, give you items, look around, show its inventory, stop, and mine N blocks of a kind.

The companion keeps the chunks around itself loaded, so it keeps working when you walk away, and it resumes at its last position after a server restart. `companion.chunkLoading.radius` sets how many chunks around it keep ticking (0–4); the default of 1 keeps a 3×3 area ticking inside a 5×5 loaded area, and the dimension itself stays active. Turn this off with `companion.chunkLoading.enabled` in `config/friendai-server.json`.

## Setting up a provider

Open **Mod Menu → Friendai → Provider**, or edit `config/friendai-server.json`. On a dedicated server edit the file and run `/friendai reload`.

| Preset id | Service | Notes |
|---|---|---|
| `openai` | OpenAI | Responses API |
| `openai_compat` | Any OpenAI-compatible endpoint (OpenRouter, Groq, DeepSeek, LM Studio, …) | `baseUrl` is required |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `ollama` | Ollama (native API) | no API key |
| `opencode_zen` | OpenCode Zen | routes by model name |
| `command_code` | Command Code | routes by model name |

Keep API keys out of the file by referencing an environment variable:

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

The key is never written to logs or chat. "Test connection" in the settings screen checks that the provider answers and that the model supports tool calling.

## Voice

Hold **V** to talk. Open **Settings → Voice → Recognition** and choose where speech is turned into text:

- **Local (Whisper, offline)** — [whisper.cpp](https://github.com/ggerganov/whisper.cpp) runs one of the `tiny`, `base` or `small` models on your CPU, free of charge. Press **Download model**; models are stored in `<game dir>/friendai/models/`.
- **OpenAI (paid)** — an OpenAI-compatible transcription API (`gpt-transcribe` by default). The base URL can point at any OpenAI-compatible server, including a local one, which needs no key.
- **Groq (paid)** — the same API through Groq (`whisper-large-v3` by default).
- **Yandex SpeechKit (paid)** — an API key, the cloud folder id and a concrete language tag.

The transcript is sent to the server as a chat message. Local recognition keeps the audio on your machine; a cloud provider uploads the recording, and only while that provider is selected.

Both typed chat and push-to-talk only reach a companion in the same dimension within the server's hearing radius (32 blocks by default, `chat.hearingRadius`). Farther away the companion neither hears you nor answers, and says so instead of staying silent.

### Speech output

The companion can also speak its replies. Synthesis is off by default; turn on **Speak replies** in **Settings → Voice → Synthesis** and choose a provider:

- **Local (free, offline)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runs a Piper voice on your CPU. Five voices are offered: Irina and Dmitri (Russian), Lessac and LibriTTS-R (English) and Alba (British English). Pick one and press **Download voice**; voices are stored in `<game dir>/friendai/models/tts/` (about 67–82 MB each). Russian systems get Irina, everything else Lessac. Each voice carries its own license, shown under the dropdown: the model files are mostly MIT, the LibriTTS-R voice is CC BY 4.0, and the training-data terms vary.
- **OpenAI (paid)** — the OpenAI speech API with a key, a voice and a speed. The base URL can point at any OpenAI-compatible server, including a local one, which needs no key.
- **ElevenLabs (paid)** — the ElevenLabs text-to-speech API; a key, a voice id and a speed.
- **Yandex SpeechKit (paid)** — an API key, the cloud folder id, a voice, a language tag, a speed and 16 kHz PCM output.

Only the reply text leaves your machine, and only for cloud synthesis; local voices never send it anywhere. For cloud recognition only the recording leaves. Keys are client-side for both directions: they live in the client config, may reference an environment variable (`env:OPENAI_API_KEY`), travel only in an HTTP header, and never appear in logs, chat or error messages. Friendai never retries a paid request automatically.

Volume, the output device, the maximum length of a spoken phrase and speaking replies addressed to other players are on the same screen. **Voice comes from the companion** is on by default: every reply plays from the companion's position, pans left or right as you turn, and fades with distance until it is silent at the hearing radius. Turn it off to hear replies centred at full volume. **Test voice** speaks a sample sentence. Pressing push-to-talk or the **Stop speaking** key (unbound by default; set it in Controls) cuts a reply off.

## Resource knowledge and search

The server keeps a separate resource atlas for each owner in `<world>/friendai/knowledge/<owner-uuid>.json`. It records block locations only after the companion has a clear sight ray or confirms its own successful mining break. Counts are historical observations; a remembered site can be covered or depleted, so `known_resources` reports age and uncertainty and never promises the old count is still available. Queries use saved evidence only and do not load chunks.

Container contents are cached only after the companion opens a container during an authorized check or transfer. `obtain_item` may use a recent same-dimension snapshot within 16 blocks to choose a chest source, then it opens that container and verifies the live contents before taking items. A closed container is never inspected during discovery.

`search_resource` lets a trusted player request a bounded search. The companion chooses deterministic surface viewpoints and reports a resource only after direct sight. It does not infer a hidden ore vein from terrain. `strategy=mine` can use the existing guarded mining route when a suitable durable pickaxe and 16 torches are available. `strategy=cave` requires a recently observed, unobstructed open-air sight corridor descending through an entrance to a visible interior wall; without that evidence it refuses to depart. Entrance evidence is transient, owner-scoped, expires after five minutes, and does not reveal resources by itself. Repeated requests for the same owner, dimension and goal reuse the saved budget. Active search work is interrupted on owner logout or world reload rather than replayed automatically.

Search policy is under `tasks.resources` in `config/friendai-server.json`: local searches default to 64 blocks and 2,400 ticks. Expeditions default to disabled and require both `allowExpeditions` and companion chunk loading. They hold at most the next-leg chunk ticket; with chunk loading disabled, the companion stops at its current loaded frontier. The atlas, coverage and executed trails are owner-scoped and bounded.

## Goals and planning

For an objective with several parts ("build a house and bring iron") the companion records a goal with how it will know it is done, and the goal survives plans, failures, death and restarts. It keeps working on it through the task queue, reads the facts of every finished task (items gained and lost, what is still unmet) and, when a step fails, gets a diagnosis and a remedy it has not tried yet. After a few failed repairs it stops and asks you what blocks it.

A complex request, or a failed plan with replans left, is first drafted by a separate planner call that returns a validated plan; the plan is queued with your access level and can never use a tool you may not use. Settings live under `agent` in `config/friendai-server.json`:

| Setting | Default | Meaning |
|---|---|---|
| `toolRouting` | `true` | Send the model only the tool families a request needs; it can unlock more with `request_tools`. |
| `autonomy` | `assistive` | `passive` never starts on its own; `assistive` nudges an idle companion toward an unfinished goal; `autonomous` may also ask what to do next (it still never acts unprompted). |
| `maxReplans` | `3` | Replans per goal before the companion asks for help (0–10). |
| `goalTickSec` | `30` | Seconds between nudges toward an unfinished goal (10–600). |
| `promptBudget` | `24000` | Character budget of the system prompt; `0` means unlimited. |
| `planner` | none | Name of a profile to use for planning; empty uses the active profile. |
| `plannerTimeoutSec` | `60` | How long a planner call may take (10–300). |

## Languages

The interface is available in English and Russian. The companion replies in the language you write in, or in a fixed language if you set one. To add a language, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Building

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## License

[MIT](LICENSE)
