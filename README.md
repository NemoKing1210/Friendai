# Friendai

**English** · [Русский](README.ru.md) · [Українська](README.uk.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Español](README.es.md) · [Português (Brasil)](README.pt-BR.md) · [简体中文](README.zh-CN.md)

![Minecraft 26.3](https://img.shields.io/badge/Minecraft-26.3-62B47A)
![Fabric](https://img.shields.io/badge/Fabric-Loader-DBB69C)
![Java 25](https://img.shields.io/badge/Java-25-ED8B00)
![MIT](https://img.shields.io/badge/License-MIT-blue)

<img src="src/main/resources/assets/friendai/icon.png" alt="Friendai icon" width="128" align="right">

An open-source Fabric mod that adds an AI companion to Minecraft. Talk to it in chat or by voice, and it will follow you, fetch items, walk to places and mine blocks for you.

> "Friendai, mine 10 logs" — the companion answers in chat, walks to the trees, chops them, picks up the drops and reports back.

## Contents

- [What it can do](#what-it-can-do)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Setting up a provider](#setting-up-a-provider)
- [Voice](#voice)
- [Goals and planning](#goals-and-planning)
- [Trading](#trading)
- [Resource knowledge and search](#resource-knowledge-and-search)
- [Languages](#languages)
- [Configuration upgrades](#configuration-upgrades)
- [Creative mode](#creative-mode)
- [Download and releases](#download-and-releases)
- [Building](#building)
- [Contributing](#contributing)

## What it can do

| Area | What the companion does |
|---|---|
| Follow and stay | Follows at a walking distance, stays where told, walks to a point and comes back. |
| Fetch and deliver | Picks up drops, takes items from containers it has checked, and hands items to you. |
| Mine and build | Mines a counted block kind, fells a standing tree, places blueprints and builds stairs. |
| Explore | Looks around, searches a bounded area for a resource, and routes through water and caves. |
| Fight | Guards you, targets hostile mobs, and retreats or eats when hurt. |
| Keep working | Loads the chunks around itself, survives a restart, and resumes its last task. |
| Remember | Keeps owner-scoped memories, places, resource sightings, goals and trading notes. |
| Speak | Answers in chat or by voice, and can speak replies with a local or cloud voice. |

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

## Configuration upgrades

Supported older configuration files upgrade automatically: server schemas 3–15 to 16, and client schemas 1–3 to 4. Provider profiles, API keys, models, devices and explicit settings are retained; new fields receive validated defaults. Renamed settings are converted explicitly. Unknown extension fields survive upgrades and subsequent saves, including reordered routing rules. If several edited array entries carry extra fields and their identity becomes ambiguous, saving is refused rather than guessing which entry owns those fields; edit that file directly while the game/server is stopped.

Before replacement, the mod validates the result and saves the exact original bytes beside the file as `<filename>.v<old-version>.<unique-id>.bak`. Backups are never overwritten by another upgrade. Writes use atomic replacement; configuration files are limited to 8 MiB. If an upgrade, backup or replacement fails, the original stays intact and read-only while defaults are used in memory. Correct the problem and reopen the server settings screen or restart the game/server to retry.

Unknown, unversioned and future formats are never guessed or downgraded. Use the matching mod version, or keep a backup and deliberately replace the file. To restore an upgrade backup, stop the game/server first and use a mod version that understands that backup's schema. Backups contain the same credentials as the original; protect them as you protect the config. Owner records and world saves use their separate persistence rules.

## Creative mode

Open the companion screen from the pause menu and turn on **Creative** in the Control tab. It is off by default. A dedicated-server owner must be an operator to enable it; in singleplayer, the world owner may enable it even when cheats are off. The owner can turn it off at any time, and the preference survives dismissal, resummoning and world reloads.

In creative mode the companion ignores ordinary damage and hunger, can fly around collisions, breaks permitted blocks instantly without normal drops, and does not consume building materials. Protection rules still apply. Use the owner-only `create_item` tool to deliver registered items with their default components to the companion or a nearby owner; delivery stops at the destination inventory's capacity and reports any remainder. `fly_to` follows a bounded route through loaded chunks.

The owner-only `execute_command` tool accepts a limited gameplay allowlist: teleport the companion, change or query time/weather, give items to the owner, apply or clear effects on the companion, and enchant the owner's held item. The owner must be online and retain normal command permission when a command runs. Singleplayer activation with cheats off does not grant command permission. The tool rejects `execute`, `function`, `schedule`, other command roots and player targets other than the owner. Turning creative off cancels queued creative work and restores survival movement, hunger and damage rules; items already created remain in inventories.

## Setting up a provider

Open **Mod Menu → Friendai → Provider**, or edit `config/friendai-server.json`. On a dedicated server edit the file and run `/friendai reload`.

| Preset id | Service | Notes |
|---|---|---|
| `openai` | OpenAI | Responses API |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `openrouter` | OpenRouter | one key for many models |
| `deepseek` | DeepSeek | |
| `groq` | Groq | fast hosted models |
| `mistral` | Mistral | |
| `xai` | xAI | |
| `together` | Together AI | |
| `fireworks` | Fireworks AI | |
| `cerebras` | Cerebras | |
| `github_models` | GitHub Models | GitHub token |
| `openai_compat` | Any OpenAI-compatible endpoint (LM Studio, vLLM, …) | `baseUrl` is required |
| `ollama` | Ollama (native API) | no API key |
| `opencode_zen` | OpenCode Zen | routes by model name |
| `command_code` | Command Code | routes by model name |

Keep API keys out of the file by referencing an environment variable:

| If you want… | Choose |
|---|---|
| Free and local, no key | `ollama` |
| One key for many models | `openrouter` |
| Fast hosted replies | `groq` |
| Strong tool calling | `anthropic` or `openai` |
| Speech recognition | `google`, `deepgram`, `openai` or `groq` |
| Speech synthesis | `google`, `elevenlabs` or `openai` |

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

The key is never written to logs or chat. "Test connection" in the settings screen checks that the provider answers and that the model supports tool calling.

## Voice

Hold **V** to talk. Open **Settings → Voice → Recognition** and choose where speech is turned into text:

- **Local (Whisper, offline)** — [whisper.cpp](https://github.com/ggerganov/whisper.cpp) runs one of the `tiny`, `base` or `small` models on your CPU, free of charge. Press **Download model**; models are stored in `<game dir>/friendai/models/`.
- **OpenAI (paid)** — an OpenAI-compatible transcription API (`gpt-transcribe` by default). The base URL can point at any OpenAI-compatible server, including a local one, which needs no key.
- **Groq (paid)** — the same API through Groq (`whisper-large-v3` by default).
- **Google Cloud Speech-to-Text (paid)** — a key and a language; the recording is sent as LINEAR16.
- **Deepgram (paid)** — a key, a model and an optional language.
- **Yandex SpeechKit (paid)** — an API key, the cloud folder id and a concrete language tag.

The transcript is sent to the server as a chat message. Local recognition keeps the audio on your machine; a cloud provider uploads the recording, and only while that provider is selected.

Both typed chat and push-to-talk only reach a companion in the same dimension within the server's hearing radius (32 blocks by default, `chat.hearingRadius`). Farther away the companion neither hears you nor answers, and says so instead of staying silent.

### Speech output

The companion can also speak its replies. Synthesis is off by default; turn on **Speak replies** in **Settings → Voice → Synthesis** and choose a provider:

- **Local (free, offline)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runs a Piper voice on your CPU. Five voices are offered: Irina and Dmitri (Russian), Lessac and LibriTTS-R (English) and Alba (British English). Pick one and press **Download voice**; voices are stored in `<game dir>/friendai/models/tts/` (about 67–82 MB each). Russian systems get Irina, everything else Lessac. Each voice carries its own license, shown under the dropdown: the model files are mostly MIT, the LibriTTS-R voice is CC BY 4.0, and the training-data terms vary.
- **OpenAI (paid)** — the OpenAI speech API with a key, a voice and a speed. The base URL can point at any OpenAI-compatible server, including a local one, which needs no key.
- **Google Cloud Text-to-Speech (paid)** — a key, a voice, a language and a speed; audio comes back as LINEAR16.
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

## Trading

The owner can ask the companion to find loaded villagers, wandering traders and adult piglins, inspect exact vanilla offers, query remembered prices and preview explicit spending limits. Market notes are saved per owner under `<world>/friendai/trading/`; quotes and locations expire independently, and every purchase rechecks the live merchant offer. Orders use the companion's own reputation and inventory and never refresh stock or change professions by force.

Merchant orders require per-item caps and reserves. Queue retries share the original owner turn's scope and cannot increase its limits; pending purchases are not replayed after a restart. `obtain_item` considers a merchant only when that same owner turn already supplied an explicit trade budget and payment fingerprint. It only plans component-neutral outputs; exact component trades use `trade_item`.

The `barter` tool uses a real adult piglin and vanilla gold interaction. Its outputs are random, so the companion reports only drops attributed to that attempt and stops rather than paying again if an accepted ingot has no observed result. It defaults to at most eight gold ingots per order. Trading controls are owner-private in the Traders tab. Server defaults live under `tasks.trading` in `config/friendai-server.json`; `enabled` can turn all trading tools and UI actions off.

## Languages

The interface is available in English, Russian, German (`de_de`), French (`fr_fr`), Spanish (`es_es`), Brazilian Portuguese (`pt_br`), Simplified Chinese (`zh_cn`) and Ukrainian (`uk_ua`). The companion replies in the language you write in, or in a fixed language if you set one. Local Piper voices exist only for Russian and English; a translated interface does not add a local voice, so cloud synthesis or English/Russian speech is still required for other languages. To add a language, see [CONTRIBUTING.md](CONTRIBUTING.md).

| Language | Code | Interface | Local voice |
|---|---|---|---|
| English | `en_us` | yes | yes |
| Russian | `ru_ru` | yes | yes |
| German | `de_de` | yes | no |
| French | `fr_fr` | yes | no |
| Spanish | `es_es` | yes | no |
| Brazilian Portuguese | `pt_br` | yes | no |
| Simplified Chinese | `zh_cn` | yes | no |
| Ukrainian | `uk_ua` | yes | no |

## Download and releases

Releases are published on the [Releases page](https://github.com/NemoKing1210/Friendai/releases). Each one carries
exactly one mod jar, `friendai-<version>.jar`, and `friendai-<version>.jar.sha256`; download both, check the checksum
(`sha256sum -c friendai-<version>.jar.sha256`, or `shasum -a 256 -c` on macOS), and put the jar in `mods/` on the client
and on the server. The release notes are the `CHANGELOG.md` section for that version.

Only the maintainer creates a tag. Pushing `vX.Y.Z` starts the release workflow, which refuses a tag that does not match
`mod_version`, a commit that is not on `main`, or a commit whose CI run is not green; it then runs the full build and
publishes the verified jar. See `CONTRIBUTING.md` for the dry run and the local checks.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) for the branch, commit, test and translation rules.

## Building

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## License

[MIT](LICENSE)
