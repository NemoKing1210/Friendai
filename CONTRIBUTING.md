# Contributing to Friendai

Friendai is a Fabric mod for Minecraft 26.3. The server owns the companion entity, agent, tasks and saved state; the
client owns rendering, menus, voice input/output and client settings. Both sides need the mod, including the integrated
server in singleplayer. Read [AGENTS.md](AGENTS.md) and [ARCHITECTURE.md](ARCHITECTURE.md) before changing an area.

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
./gradlew :engine:test --tests 'dev.friendai.<package>.*'  # selected pure engine tests
./gradlew test --tests 'dev.friendai.<package>.*'          # selected root tests; root test also includes engine
./gradlew test --tests '*SomeTest'                         # one unit-test class
./gradlew compileGametestJava                              # compile GameTests; does not boot Minecraft
./gradlew runGameTest -Dfabric-api.gametest.filter='friendai-gametest:<class>_tests_*'
./gradlew assemble verifyModArtifact                       # package and inspect the mod jar; exports the test jar
```

While working, run only the tests covering the change. A filtered `runGameTest` boots a Minecraft server; use the
class glob for the affected area. Do not run `build`, `check` or the unfiltered GameTest suite as a local safety check.
CI owns the full gate; a maintainer may explicitly request a local full run. Report the actual test group and result.

Unit results are in `build/test-results/test/*.xml` and `engine/build/test-results/test/*.xml` (one file per test
class). GameTest results are printed by the server, including `========= N GAME TESTS COMPLETE` and the required-test
summary. Compilation is not a substitute for running a GameTest when a world behavior changed.

Run the project formatter on only your Java files before committing:

```bash
./gradlew spotlessApply -PspotlessFiles=src/main/java/dev/friendai/path/File.java,engine/src/main/java/dev/friendai/path/File.java
```

Use root-relative paths and prefix engine paths with `engine/`. Do not run a bare `spotlessApply` in a shared checkout;
it can rewrite another session's files. CI runs `spotlessCheck`.

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

Add the default and validation to `engine/.../infrastructure/config/ServerConfig` or `ClientConfig`. Bump the persisted
schema version when its shape changes; older and future versions are unsupported and remain untouched. This project
does not migrate config files. Server startup uses in-memory defaults for unsupported server config and does not write
to the old file. Add editable UI in `src/client/.../config/` when appropriate, keeping YACL and Mod Menu optional. Add
matching language keys and focused validation and unsupported-version tests.

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

### Add player-facing text

Add the key to `src/main/resources/assets/friendai/lang/en_us.json` and the matching key and placeholders to
`ru_ru.json`. Reference it with `Component.translatable` or `Components.of(new UserMessage(...))`; run
`./gradlew test --tests '*LangFilesTest'`.

## Commits and CI

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<optional scope>):
<lowercase subject>`, no trailing period, header up to 100 characters. Types are configured in
`commitlint.config.mjs`. Enable the local commit check once per clone:

```bash
git config core.hooksPath .githooks
```

On every pull request, GitHub Actions runs the full unit tests, Fabric GameTests, formatting, artifact packaging and
registration checks. The `CI gate` job is required; the `Commit lint gate` checks commits and the PR title. Do not mark
manual checks complete unless they were performed; record remaining platform/provider/microphone checks in
`MANUAL_CHECKLIST.md`.

Set `mod_version` in `gradle.properties` according to the sizing rules in `AGENTS.md`. Release tags are the
maintainer's call; do not create tags or publish a release unless asked.
