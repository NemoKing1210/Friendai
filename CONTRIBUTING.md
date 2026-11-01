# Contributing to Friendai

Friendai is a Fabric mod for Minecraft 26.3. The server owns the companion entity, agent, tasks and saved state; the
client owns rendering, menus, voice input/output and client settings. Both sides need the mod, including the integrated
server in singleplayer. Read [AGENTS.md](AGENTS.md) and [ARCHITECTURE.md](ARCHITECTURE.md) before changing an area.

`AGENTS.md` is the canonical rulebook and `ARCHITECTURE.md` the reference; this file is a short human-facing
summary and must not add rules — when they disagree, `AGENTS.md` wins.

## Ground rules

- Repository content is English. Player-facing text uses translation keys from `en_us.json`, mirrored in `ru_ru.json`.
- `engine/` has no Minecraft, Fabric, optional UI or native audio dependencies. Keep game adapters in root source sets.
- Only the server thread touches the world. Async server work hops back with `server.execute`; client work hops back
  with `Minecraft.getInstance().execute`.
- API keys never appear in logs, chat, exception messages or `toString()`; send them in headers, never URLs.
- Follow the dependency and ownership boundaries in `ARCHITECTURE.md`. Update its package map, flows or persistence
  table in the same commit when a change affects them.

## Build and test commands

Java 25 is required. If needed, set `JAVA_HOME` to a JDK 25 before invoking Gradle.

```bash
./gradlew test --tests 'dev.friendai.<package>.*'                        # selected root tests (includes engine)
./gradlew runGameTest -Dfabric-api.gametest.filter='friendai-gametest:<class>_tests_*'
./gradlew assemble verifyModArtifact                                     # compile, package, refresh the test jar
```

The full command table, the test-scope rules, the formatter invocation and the prompt-golden workflow live in
`AGENTS.md` ("Commands", "Testing", "Formatting"). Run only the group covering the change; CI owns the full gate.
Compilation is not a substitute for running a GameTest when a world behavior changed.

## Common changes

### Add a skill

1. Put Minecraft task adapters in `src/main/java/dev/friendai/features/<area>/minecraft/task/`; put reusable pure
   policy and model code in the matching `engine/src/main/java/dev/friendai/features/<area>/` package.
2. Contribute the tool from the area's `*Tools` class via `FeatureToolContributor`, `FeatureToolRegistry` and
   `TaskToolDefinition`. Validate inputs with `SchemaBuilder`, choose access and queue behavior deliberately, and
   provide catalog metadata. `FriendaiTools.build` composes contributors; do not create another central factory.
3. If work must resume after restart, make the definition restorable and contribute its bounded checkpoint decoder
   with `.withCheckpoint(<stable task type>, <CheckpointRestorer>)`; `TaskRestoreRegistry` composes contributed
   decoders and rejects duplicate keys.
4. Add both language entries and a GameTest under `src/gametest`; register each test class in
   `src/gametest/resources/fabric.mod.json`.
5. Run the relevant unit group and one filtered GameTest class. Bump `mod_version` for shipped behavior changes;
   `assemble verifyModArtifact` refreshes `test-builds/friendai-<version>.jar` without starting GameTests.

### Add a provider or protocol

Provider presets and routing live in `engine/.../agent/llm/provider/`; wire formats live in
`engine/.../agent/llm/protocol/`. Register protocols/presets in `ProviderRegistry.withBuiltins` and preserve shared
message rules in `ProtocolSupport`. Test routing and serialization with the provider/protocol unit tests and
`MockHttpServer`; update the README provider table if the change is user-visible. Keys stay redacted.

### Add a config option

1. Add the field and default to `engine/.../infrastructure/config/ServerConfig` or `ClientConfig`; enforce valid ranges
   in that config's validation path.
2. When the persisted shape changes, bump its schema and add an explicit adjacent `ConfigMigration` step with tests for
   existing values and defaults for new fields. The migration chain is the source of truth for supported versions;
   validate before writing, preserve unknown fields, back up uniquely and replace atomically. These migrations apply to
   configuration, not owner or world records.
3. Classify the option in `engine/.../features/settings/SettingsModel`: add a catalogue entry with its tab id, `basic`
   or `advanced`, and the synonyms a player may type. Search, the Basic/Advanced split and the reset scope come from
   that model; an uncatalogued option stays visible and matches only on its own key words.
4. Build the widget in `src/client/.../config/` and give it a tooltip with
   `.description(OptionDescription.of(Component.translatable("<key>.tooltip")))` stating what it changes, the default,
   unit and range, any cost, world or protection side effects, and whether it applies live or after `/friendai reload`.
   Keep YACL and Mod Menu optional.
5. Add the label and tooltip keys to every locale (`en_us.json` first, then `ru_ru`, `de_de`, `fr_fr`, `es_es`,
   `pt_br`, `zh_cn`, `uk_ua`) with identical placeholders. Add focused validation and migration tests, cover the new
   search or mode classification in `SettingsModelTest`, and run `LangFilesTest` for the two keys.

### Add a network payload

Keep pure values, bounds and codecs in `engine/.../protocol/`; Fabric wrappers belong in
`src/main/java/dev/friendai/transport/payload/`. Register them through `FriendaiNetworking`. Gate client-to-server
requests through the negotiated connection in `ServerProtocolConnections`, check `canSend` before either side sends,
and handle world/client state on the correct thread. Authorization and sensitive-data filtering happen on the server.

### Change persistence

Use the existing per-owner repositories and `OwnerFileStore`/`StoreCodec` boundaries. Keep owner records separate,
bounded and versioned. Older and future formats are unsupported and remain untouched; this project does not migrate
saved records or retain old-format readers. Corrupt and oversized data must not be overwritten accidentally. Preserve
atomic replacement and backup recovery, add codec and repository round-trip/failure tests, and update the persistence
inventory in `ARCHITECTURE.md`.

### Add player-facing text or a language

English (`en_us.json`) is the source of truth. Add the key there, then the same key to every shipped language:
`ru_ru`, `de_de`, `fr_fr`, `es_es`, `pt_br`, `zh_cn` and `uk_ua`. Keep every `%s`, `%d` and `%%` placeholder, and their
order, identical to English; `LangFilesTest` compares the ordered placeholder sequence, the key set and empty values
across every file. Reference the key with `Component.translatable` or `Components.of(new UserMessage(...))`; run
`./gradlew test --tests '*LangFilesTest'`.

A new language is a full `en_us.json` translation plus long-string layout checks (buttons, tabs, tooltips, HUD, toasts
and the settings screen at the smallest GUI scale); a translated interface does not add a local voice.

## Commits and CI

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<optional scope>):
<lowercase subject>`, no trailing period, header up to 100 characters. Types are configured in
`commitlint.config.mjs`. Enable the local commit check once per clone:

```bash
git config core.hooksPath .githooks
```

On every pull request, GitHub Actions runs the full unit tests, Fabric GameTests, formatting, artifact packaging and
registration checks. The `CI gate` job is required; the `Commit lint gate` checks commits and the PR title. See
"CI checks and red runs" in `AGENTS.md` for the shard map, the nightly run and how to triage a red shard. Do not mark
manual checks complete unless they were performed; record remaining platform/provider/microphone checks in
`MANUAL_CHECKLIST.md`.

Set `mod_version` in `gradle.properties` according to the sizing rules in `AGENTS.md`. Release tags are the
maintainer's call; do not create tags or publish a release unless asked.

## Releases

A release is one tag, one jar and one checksum. The maintainer does the tagging; the workflow verifies and publishes.

1. `main` is green and the `CHANGELOG.md` section for the version is written.
2. `./gradlew assemble`, then `scripts/check-release.sh vX.Y.Z` — it checks the version format, the tag, the jar name,
   the `fabric.mod.json` version inside the jar and any sidecar checksum, and prints the SHA-256.
3. `git tag vX.Y.Z && git push origin vX.Y.Z` runs `.github/workflows/release.yml`: it refuses a tag that does not match
   `mod_version`, a commit that is not an ancestor of `origin/main`, or a commit without a green CI run, builds with
   `./gradlew clean build`, verifies the artifact with the same script, and publishes (or idempotently replaces) the
   release with `friendai-X.Y.Z.jar` and `friendai-X.Y.Z.jar.sha256`. Release notes are the changelog section, or the
   generated notes when there is none.
4. To exercise the pipeline without publishing, dispatch the workflow manually with a tag and `dry_run: true`: it builds,
   verifies and uploads the jar as an artifact only. The workflow never creates a tag and never edits
   `gradle.properties`; a version bump is a normal commit on `main`.
