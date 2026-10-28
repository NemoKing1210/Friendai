# Architecture dependency rules

`ArchitectureRulesTest` executes these rules against project class dependencies emitted by the Java 25 `jdeps` tool,
and separately checks engine classes for forbidden targets. The `verifyEngineClasspath` task checks resolved
compile/runtime entries for Minecraft, Fabric, optional UI and native audio dependencies, including otherwise-unused
artifacts. The test also checks source/resource roots and Fabric entrypoint registrations. A failed `jdeps` process,
malformed class-edge output, or empty dependency graph fails the test.

## Enforced directions

| Rule | Allowed direction |
|---|---|
| `engine-forbidden-dependency` | Engine classes and classpaths exclude Minecraft, Fabric, YACL, Mod Menu, Whisper JNI and sherpa-onnx. |
| `foundation-direction` | Foundation has no platform, agent, feature, protocol, presentation, infrastructure, server, client or transport dependency. |
| `pure-java-no-platform` | `brain`, `config`, `llm`, `text`, `voice` and non-builtin `tool` classes do not depend on Minecraft or Fabric. |
| `feature-to-server` | Engine feature code does not depend on server, client, transport, infrastructure, presentation or Minecraft/Fabric adapters. |
| `agent-direction` | Engine agent/application code calls ports and does not depend on server, client, transport, infrastructure, presentation or Minecraft/Fabric adapters. |
| `server-to-client` | Server implementation does not depend on client implementation. |
| `optional-integration-isolation` | YACL and Mod Menu references are confined to `dev.friendai.client.config`. |
| `presentation-feature-view` | Presentation may consume a feature's `view` package; it may not depend on feature internals. |
| `presentation-to-adapter` | Presentation does not depend on agent orchestration, server/client adapters, transport, infrastructure or Minecraft/Fabric. |
| `protocol-direction` | Protocol contracts do not depend on presentation, infrastructure, server/client adapters, transport or Minecraft/Fabric. |
| `legacy-server-state-lookup` | Application and adapter classes obtain owned dependencies from their boundary instead of looking up `FriendaiServerState`. |

The test fixtures prove that engine-to-platform/UI/native, feature-to-server, agent-to-adapter, server-to-client,
foundation-to-platform, optional-integration leaks, presentation-to-adapter, protocol-to-adapter and
presentation-to-feature-internal edges fail with the exact source and target classes. Feature view dependencies and
YACL from client config pass. These package names describe the target architecture; the rules apply as those packages
are introduced.

## Current migration exceptions

The current checkout has 66 exact class edges to `dev.friendai.core.FriendaiServerState`. Each edge is recorded in
[`legacy-dependency-edges.tsv`](legacy-dependency-edges.tsv) with its owning removal task (4, 5, 8, 9, 11, 14, 16 or
17). The test compares the live violations with this complete list: an undocumented edge fails, and a row fails once
its edge disappears. Do not replace entries with package-wide globs or add exceptions without assigning them to a
specific migration task.

Tasks 4, 5, 8, 9, 11, 14, 16 and 17 remove the class edges they own. When a migration removes an edge, delete its row in
the same commit. No other legacy class edges are allowed.

## Source and registration scope

Language-key scans cover `engine/src/main/java`, `src/main/java` and `src/client/java`; translation catalogs are
collected from the engine and root production resource roots. The typed-placement-failure scan covers all engine and
root main Java sources. Resource lookup checks every production resource in the engine and root main resource roots.
Fabric and GameTest metadata entries must be unique and resolve to their current source classes. All GameTest classes
that declare `@GameTest` or `@GameTestGenerator` remain registered; the current metadata contains 89 entrypoints.

CI's unit-test job runs the root test task, which includes `ArchitectureRulesTest`; the same task also retains the
existing language-key and typed-failure scans.
