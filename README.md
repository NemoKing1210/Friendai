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
- [Settings screen](#settings-screen)
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

Supported older configuration files upgrade automatically: server schemas 3–21 to 22, and client schemas 1–9 to 10. Provider profiles, API keys, models, devices and explicit settings are retained; new fields receive validated defaults. Renamed settings are converted explicitly. Unknown extension fields survive upgrades and subsequent saves, including reordered routing rules. If several edited array entries carry extra fields and their identity becomes ambiguous, saving is refused rather than guessing which entry owns those fields; edit that file directly while the game/server is stopped.

Before replacement, the mod validates the result and saves the exact original bytes beside the file as `<filename>.v<old-version>.<unique-id>.bak`. Backups are never overwritten by another upgrade. Writes use atomic replacement; configuration files are limited to 8 MiB. If an upgrade, backup or replacement fails, the original stays intact and read-only while defaults are used in memory. Correct the problem and reopen the server settings screen or restart the game/server to retry.

Unknown, unversioned and future formats are never guessed or downgraded. Use the matching mod version, or keep a backup and deliberately replace the file. To restore an upgrade backup, stop the game/server first and use a mod version that understands that backup's schema. Backups contain the same credentials as the original; protect them as you protect the config. Owner records and world saves use their separate persistence rules.

## Settings screen

With [Mod Menu](https://modrinth.com/mod/modmenu) and [YACL](https://modrinth.com/mod/yacl) installed, **Mod Menu → Friendai** opens the settings screen. Both are optional: without them the mod runs the same, and every setting comes from `config/friendai-server.json` and `config/friendai-client.json`, which you edit by hand and apply with `/friendai reload` or a restart.

The screen is split into tabs: Provider, Companion, Chat, Planning, Tasks, World, Logs, Trading, Resources, Free time, Memory, Mining, Display and Voice, with a **Search and profiles** tab first. A client that may not edit the server settings gets a single Server tab reading "These settings are configured on the server." instead of the server tabs.

The screen is reachable with the keyboard alone: Tab and Shift+Tab walk the search field, the Basic/Advanced switch, the tab rail and every option, and Enter activates the focused control. Layouts are checked in all eight interface languages at the largest GUI scale; if a tooltip is cut on a very small window, the same text is in `architecture/settings-inventory.md`.

Most options carry a tooltip with the default and the accepted range. Reset returns one option to its default; Cancel and Undo discard the whole edit. `architecture/settings-inventory.md` lists every field with its default, clamp, screen key and whether it has a tooltip.

The **Search and profiles** tab holds the search field, the Basic/Advanced switch and the profile and import/export controls, so clearing a filter is always one click away. Search narrows each settings tab to the options whose label, tooltip or key contains every word you typed, in the interface language you are using, ignoring case and accents; Basic hides keys, URLs, budgets, radii and internals, and Advanced shows everything. Three resets exist and each one only changes the draft until you press Save: the reset button on a row returns that one option, **Reset this category** at the top of a tab returns every setting in that tab, and **Reset everything** at the top of the first settings tab returns every setting in the screen.

## Logs

The server writes a durable log of what the companion did: one JSON object per line with a level, a category, a sequence id and a `cause` link to the record that started it, so a failure can be followed back to the turn that caused it. Each world keeps its own tree, `server/<date>/` and `owners/<uuid>/<date>/` under `<world>/friendai/logs/`, so two saves never mix and the companion in one world cannot search another. The client's own records stay in `<gameDir>/logs/friendai/client/`, beside an `index.jsonl` that names the world each game session belonged to. A readable `.md` digest and a `README.md` sit in each tree.

The world's tree is inside the save: `saves/<world>/friendai/logs` under the folder the launcher uses, and `<server directory>/<level-name>/friendai/logs` on a dedicated server. The shared root `<gameDir>/logs/friendai` keeps the client records and the world index. With the official launcher the game directory is `%APPDATA%\.minecraft` on Windows and `~/Library/Application Support/minecraft` on macOS; per-instance launchers keep it inside the instance, for example `%APPDATA%\ModrinthApp\profiles\<profile>` or `%APPDATA%\PrismLauncher\instances\<instance>`. **Open log folder** and `/friendai logs path` print the exact world path, which is the reliable answer when a modpack uses a custom directory.

On Windows, find recent owner-session digests in a Modrinth profile with PowerShell (replace `<profile>` and `<uuid>`; keep the path quoted when the profile name has spaces):

```powershell
$ownerLogs = Join-Path $env:APPDATA 'ModrinthApp\profiles\<profile>\saves\<world>\friendai\logs\owners\<uuid>'
Get-ChildItem $ownerLogs -Recurse -File -Include *.md,*.jsonl |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 10 FullName, LastWriteTime, Length
Get-Content (Get-ChildItem $ownerLogs -Recurse -File -Filter *.md |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName)
```

The `.md` file is a readable digest; use its matching `.jsonl` for raw records. `/friendai logs path` and **Copy log path** are authoritative when a launcher uses a non-default game directory, and both point at the current world's tree rather than at the shared root.

The **Logs** settings category (or `logging` in `friendai-server.json`) chooses the detail level: `off` writes nothing to disk, `normal` (the default) records metadata, `detailed` adds prompt, reply and tool-result bodies after redaction, and `trace` adds per-step detail. It also controls the categories, per-file and total size caps, retention days and whether digests are written. Configured secrets are replaced with `***` before anything is written.

From the game, **Open log folder** and **Copy log path** sit in the Logs category and in the companion menu's Log tab, together with **Pause** for the live view. In singleplayer the buttons open the current world's folder; in the menu or on a dedicated server they open the client's own tree, because this machine never holds the server's files. `/friendai logs path` prints the world path for the operator, `/friendai logs level <off|normal|detailed|trace>` changes it live, `/friendai logs prune` applies retention now, and `/friendai logs export` writes the `/friendai diagnose` bundle with the log path in its summary. The companion itself can search the durable files with `logs_search` (owner only) after a failure.

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

- **Local (offline)** — the bundled whisper.cpp and sherpa-onnx runtimes transcribe on your CPU, free of charge. Pick a model in **Settings → Voice → Recognition** and press **Download model**:
  - **Whisper `tiny`, `base`, `small`** — [whisper.cpp](https://github.com/ggerganov/whisper.cpp), multilingual, stored in `<game dir>/friendai/models/`. Existing installations keep working without a new download.
  - **Parakeet V3** — 671 MB, 25 European languages including English, German, French, Spanish, Russian and Ukrainian, offline, CC BY 4.0. The most accurate multilingual entry; about 790 MB of process memory while loaded (measured on Windows 11 x64, JDK 25, two threads).
  - **Nemotron 3.5** — 682 MB, 19 transcription-ready locales, streaming, OpenMDW-1.1. While you hold the key the HUD shows a replaceable partial transcript before release; about 810 MB.
  - **GigaAM v3** — 232 MB, Russian with punctuation from the model, offline, MIT; about 340 MB.

  New bundles live in `<game dir>/friendai/models/stt/<id>/`. Every file is checked against the pinned upstream revision, exact byte size and SHA-256 before the model can be used, and an interrupted download never replaces a working installation. Memory figures are measured whole-process working sets, not a guarantee for every machine. Parakeet and GigaAM detect the language themselves; Nemotron receives the configured language, or detects it when the setting is `auto`. A language a model does not list is refused before recording instead of being silently changed, and one model is never substituted for another.
- **OpenAI (paid)** — an OpenAI-compatible transcription API (`gpt-transcribe` by default). The base URL can point at any OpenAI-compatible server, including a local one, which needs no key.
- **Groq (paid)** — the same API through Groq (`whisper-large-v3` by default).
- **Google Cloud Speech-to-Text (paid)** — a key and a language; the recording is sent as LINEAR16.
- **Deepgram (paid)** — a key, a model and an optional language.
- **Yandex SpeechKit (paid)** — an API key, the cloud folder id and a concrete language tag.

The transcript is sent to the server as a chat message. Local recognition keeps the audio on your machine; a cloud provider uploads the recording, and only while that provider is selected.

**Wake word (off by default).** In **Settings → Voice → Recognition**, enable the wake word and choose one of the bundled phrases. The client then keeps a small local KeywordSpotter armed and shows a listening indicator; only the post-wake utterance follows the configured local or cloud recognition route. The wake detector itself is local and the 5.5 MB model is downloaded once. Pre-wake audio stays in a short volatile ring (default 2 s, at most 5 s) and is never uploaded, written to disk or kept after the utterance. Disarming, opening a screen, disconnect, push-to-talk or the companion speaking stops monitoring; a cancelled or false-positive detection sends no turn.

Both typed chat and push-to-talk only reach a companion in the same dimension within the server's hearing radius (32 blocks by default, `chat.hearingRadius`). Farther away the companion neither hears you nor answers, and says so instead of staying silent.

### Speech output

The companion can also speak its replies. Synthesis is off by default; turn on **Speak replies** in **Settings → Voice → Synthesis** and choose a provider:

**Speak while the reply is arriving** (on by default) lets the first safe sentence play before the model finishes. Turn it off to hear the whole reply after it completes; the first sentence is also held when the guard cannot decide it safely.

- **Local (free, offline)** — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runs a Piper voice on your CPU. Five voices are offered: Irina and Dmitri (Russian), Lessac and LibriTTS-R (English) and Alba (British English). Pick one and press **Download voice**; voices are stored in `<game dir>/friendai/models/tts/` (about 67–82 MB each). Russian systems get Irina, everything else Lessac. Each voice carries its own license, shown under the dropdown: the model files are mostly MIT, the LibriTTS-R voice is CC BY 4.0, and the training-data terms vary.
- **OpenAI (paid)** — the OpenAI speech API with a key, a voice and a speed. The base URL can point at any OpenAI-compatible server, including a local one, which needs no key.
- **Google Cloud Text-to-Speech (paid)** — a key, a voice, a language and a speed; audio comes back as LINEAR16.
- **ElevenLabs (paid)** — the ElevenLabs text-to-speech API; a key, a voice id and a speed.
- **Yandex SpeechKit (paid)** — an API key, the cloud folder id, a voice, a language tag, a speed and 16 kHz PCM output.

Only the reply text leaves your machine, and only for cloud synthesis; local voices never send it anywhere. For cloud recognition only the recording leaves. Keys are client-side for both directions: they live in the client config, may reference an environment variable (`env:OPENAI_API_KEY`), travel only in an HTTP header, and never appear in logs, chat or error messages. Friendai never retries a paid request automatically.

Replies from a provider that supports streaming (today the OpenAI-compatible chat API; every other protocol buffers) are delivered as ordered sentences: the client keeps them in order, drops duplicates and a sentence from an older session, and reports one toast if the stream is cut off before its last sentence. **Stream replies** (Settings → Voice → Synthesis, on by default) can be turned off to buffer the whole reply on the client before speaking.

Volume, the output device, the maximum length of a spoken phrase and speaking replies addressed to other players are on the same screen. **Voice comes from the companion** is on by default: every reply plays from the companion's position, pans left or right as you turn, and fades with distance until it is silent at the hearing radius. Turn it off to hear replies centred at full volume. **Test voice** speaks a sample sentence. Pressing push-to-talk or the **Stop speaking** key (unbound by default; set it in Controls) cuts a reply off.

## Resource knowledge and search

The server keeps a separate resource atlas for each owner in `<world>/friendai/knowledge/<owner-uuid>.json`. It records block locations only after the companion has a clear sight ray or confirms its own successful mining break. Counts are historical observations; a remembered site can be covered or depleted, so `known_resources` reports age and uncertainty and never promises the old count is still available. Queries use saved evidence only and do not load chunks.

Container contents are cached only after the companion opens a container during an authorized check or transfer. That bounded record backs `find_stored_item` (where it last saw an item, with age), coordinate-free `take_items`/`store_items` and `move_items` between two containers; `obtain_item` may use a recent same-dimension snapshot within 16 blocks to choose a chest source, then it opens that container and verifies the live contents before taking items. A closed container is never inspected during discovery, and a stale record is always revalidated on arrival.

`search_resource` lets a trusted player request a bounded search. The companion chooses deterministic surface viewpoints and reports a resource only after direct sight. It does not infer a hidden ore vein from terrain. `strategy=mine` can use the existing guarded mining route when a suitable durable pickaxe and 16 torches are available. `strategy=cave` requires a recently observed, unobstructed open-air sight corridor descending through an entrance to a visible interior wall; without that evidence it refuses to depart. Entrance evidence is transient, owner-scoped, expires after five minutes, and does not reveal resources by itself. Repeated requests for the same owner, dimension and goal reuse the saved budget. Active search work is interrupted on owner logout or world reload rather than replayed automatically.

Search policy is under `tasks.resources` in `config/friendai-server.json`: local searches default to 64 blocks and 2,400 ticks. Expeditions default to disabled and require both `allowExpeditions` and companion chunk loading. They hold at most the next-leg chunk ticket; with chunk loading disabled, the companion stops at its current loaded frontier. The atlas, coverage and executed trails are owner-scoped and bounded.

`excavate` mines a bounded shape rather than a resource count: a box between two corners, a tunnel, a descending stair, a shaft or a strip mine with alternating branches. One call covers the whole region; `count` stops early and `until_item`/`until_count` stop once that many items were actually taken. Cell by cell it uses the same protection, support, lava, drop-guard, tool and inventory rules as `mine_blocks`; containers, lava and protected places are never broken by default. A long excavation writes a bounded checkpoint and resumes after a reload without re-mining cells it already took.

## Goals and planning

For an objective with several parts ("build a house and bring iron") the companion records a goal with how it will know it is done, and the goal survives plans, failures, death and restarts. It keeps working on it through the task queue, reads the facts of every finished task (items gained and lost, what is still unmet) and, when a step fails, gets a diagnosis and a remedy it has not tried yet. After a few failed repairs it stops and asks you what blocks it.

A complex request, an explicit request for a plan, a live troubled plan, an idle unmet goal or a free-time shift is first drafted by a separate planner call that returns a validated plan; the plan is queued with your access level and can never use a tool you may not use. The planner is consulted at most once per turn, before the model answers, and never again after the model has queued a plan of its own. Settings live under `agent` in `config/friendai-server.json`:

| Setting | Default | Meaning |
|---|---|---|
| `toolRouting` | `true` | Send the model only the tool families a request needs; it can unlock more with `request_tools`. |
| `autonomy` | `assistive` | `passive` never starts on its own; `assistive` nudges an idle companion toward an unfinished goal; `autonomous` may also ask what to do next (it still never acts unprompted). |
| `maxReplans` | `3` | Replans per goal before the companion asks for help (0–10). |
| `goalTickSec` | `30` | Seconds between nudges toward an unfinished goal (10–600). |
| `promptBudget` | `24000` | Character budget of the system prompt; `0` means unlimited. |
| `planner` | none | Name of a profile to use for planning; empty uses the active profile. |
| `plannerMode` | `auto` | `auto` plans a complex or explicitly requested order, a troubled plan and an unmet goal; `off` never asks the planner about an order; `always` asks about every actionable order, for diagnosis. Free time keeps its own planning. |
| `plannerRetries` | `2` | Transport retries for one planner call (1–4). A plan the planner rejects is not sent again. |
| `plannerTimeoutSec` | `20` | How long a planner call may take (5–120). |

## Routines

Ask for a timetable in chat, for example “every morning feed the cows and mine ten logs”. The companion records one routine per owner: a name, a game-time phase (`morning`, `day`, `evening`, `night`) or an interval in game minutes, and one to eight steps made of existing tools. `list_routines` shows the next due time and the last result; `cancel_routine` removes it.

- Due work enters the ordinary queue with your authority. It never goes ahead of an owner order, and a running free-time shift is stopped at its safe boundary — except a safety reflex (survival, shelter, hunger), which is left alone.
- A window missed while the server was off is skipped, never replayed, so a long absence cannot build a backlog. A `/time` jump only moves the next due tick forward.
- The routine and its last outcome are saved under `<world>/friendai/routines/`. A crash between queueing the plan and saving the routine is reported as `interrupted` and is not repeated.
- Disabling autonomy stops new dispatches; work already queued is allowed to finish. `list_routines` and the menu's **Routine** row show the next due time and the last result.

## Portal travel

`travel_through_portal` walks the companion through a lit nether portal. If no lit portal is within 16 blocks and the destination is the Overworld or the Nether, it first builds and lights a 4×5 obsidian frame from carried materials, then enters it. The trip never teleports the companion: entering the portal block lets vanilla apply its own dimension rules and landing search. Before departure the source portal is saved as the `return portal` place (kind `portal`); a place you named yourself is never overwritten, a removed or renamed anchor is not recreated silently, and the trip falls back to the nearest portal. The End is not buildable through a nether portal and is a typed refusal.

## Trading

The owner can ask the companion to find loaded villagers, wandering traders and adult piglins, inspect exact vanilla offers, query remembered prices and preview explicit spending limits. Market notes are saved per owner under `<world>/friendai/trading/`; quotes and locations expire independently, and every purchase rechecks the live merchant offer. Orders use the companion's own reputation and inventory and never refresh stock or change professions by force.

Merchant orders require per-item caps and reserves. Queue retries share the original owner turn's scope and cannot increase its limits; pending purchases are not replayed after a restart. `obtain_item` considers a merchant only when that same owner turn already supplied an explicit trade budget and payment fingerprint. It only plans component-neutral outputs; exact component trades use `trade_item`.

The `barter` tool uses a real adult piglin and vanilla gold interaction. Its outputs are random, so the companion reports only drops attributed to that attempt and stops rather than paying again if an accepted ingot has no observed result. It defaults to at most eight gold ingots per order. Trading controls are owner-private in the Traders tab. Server defaults live under `tasks.trading` in `config/friendai-server.json`; `enabled` can turn all trading tools and UI actions off.

## Memory retrieval

Memory retrieval is an opt-in, default-off setting under `memory.retrieval` in `config/friendai-server.json`. `lexical` selects the facts that share words with your message, filtered by caller visibility and redacted before scoring, within `retrievalTopK` and `retrievalMaxChars`; it is a word match, not a semantic model, and it runs entirely on the server with no provider or download. A missing or stale derived index is rebuilt from the owner's memory file; forgetting or replacing a fact makes it immediately unretrievable, and the ordinary bounded memory block remains the fallback.

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
