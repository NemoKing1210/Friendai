# Contributing to Friendai

## Ground rules

- **Everything in the repository is English**: code, identifiers, comments, logs, Markdown, commit messages, config defaults, the system prompt, tool descriptions and tool results.
- **Everything the player sees goes through translation keys.** Add the key to `src/main/resources/assets/friendai/lang/en_us.json` and the same key to every other language file. `LangFilesTest` fails if the key sets differ, a value is empty, the `%s` placeholders do not match, or code refers to a key that does not exist.
- Not translated: replies from the LLM, text addressed to the LLM (system prompt, tool descriptions/results, task summaries), logs and command literals.
- `dev.friendai.llm`, `config`, `text`, `tool` (except `tool.builtin`), `voice` and `brain` must not import `net.minecraft.*` or `net.fabricmc.*`. They are plain Java so they can be unit-tested without the game. Pure layers return `UserMessage(key, args)`; the Minecraft layer converts it with `Components.of(...)`.
- The world is touched only on the server thread. Server-side HTTP (the brain) runs on its own thread and hops back through `server.execute` (`ServerBrainScheduler`); client recognition and synthesis run on worker threads and hop back through `Minecraft.getInstance().execute`, and only the server-side transcript handler uses `server.execute`.
- API keys must never appear in logs, chat, exception messages or `toString()`.

## Adding a language

1. Copy `en_us.json` to `xx_xx.json` (for example `de_de.json`) and translate the values.
2. Keep every key and every `%s` placeholder.
3. Run `./gradlew test`.

## Adding a skill

A skill is a `Tool` (what the LLM sees) plus, usually, a `Task` (what the companion does over time).

1. Write the task in `dev.friendai.task.builtin` (implement `Task`; add `saveData()` if it should survive a world reload and teach `TaskFactory.restore` about it).
2. Register a tool for it in `FriendaiTools.create`: name, short English description, a schema built with `SchemaBuilder`, and the code that creates the task. Arguments are validated against the schema before your code runs.
3. Add a Fabric GameTest under `src/gametest` that runs the task on a test platform.

## Adding a provider preset

Presets are data. Add one to `ProviderRegistry.withBuiltins`: id, display name, default base URL, auth style, routing rules (model glob → protocol) and the protocol used to list models. Add a routing test to `ProviderRegistryTest`. If a service needs a wire format that no existing protocol speaks, add an `LlmProtocol` implementation with a `toWire`/`fromWire` pair and fixture tests.

## Tests

```bash
./gradlew test         # unit tests
./gradlew runGameTest  # in-game tests
./gradlew build        # everything
```

Write the failing test first. Items that cannot be automated (real providers, voice on each OS, LAN) are tracked in [MANUAL_CHECKLIST.md](MANUAL_CHECKLIST.md).

## Commits and CI

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<optional scope>): <lowercase subject>`, no trailing period, header up to 100 characters. Types: `feat fix docs style refactor perf test build ci chore revert`. Rules live in `commitlint.config.mjs`.

Enable the local check once per clone (plain shell, no Node needed):

```bash
git config core.hooksPath .githooks
```

On every pull request, GitHub Actions runs:

- **CI** (`.github/workflows/ci.yml`): unit tests, Fabric GameTests and the jar build. The `CI gate` job is the single required check; the mod jar is attached to the run as the `friendai-jar` artifact.
- **Commit lint** (`.github/workflows/commitlint.yml`): every commit message and the PR title. The `Commit lint gate` job is the required check.

A PR cannot be merged while either gate is red. Enable that once with `sh scripts/setup-branch-protection.sh` (needs admin rights; run it after the workflows have run at least once).

Releases: bump `mod_version` in `gradle.properties`, then push a tag `vX.Y.Z` that matches it. `.github/workflows/release.yml` runs the full build and publishes a GitHub release with the jar.
