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
| `foundation-direction` | Foundation has no platform, agent, feature, protocol, presentation, infrastructure, server, client, transport or world dependency. |
| `kernel-direction` | The Minecraft kernel (`dev.friendai.world`) does not depend on features, server, client, transport, presentation, infrastructure or protocol. |
| `feature-graph-acyclic` | Feature packages form a directed acyclic graph; no cycle baseline is allowed. |
| `pure-java-no-platform` | `brain`, `infrastructure/config`, `agent/llm`, `foundation/text`, `features/speech` and non-builtin `tool` classes do not depend on Minecraft or Fabric. |
| `feature-to-server` | Engine feature code does not depend on server, client, transport, infrastructure, presentation or Minecraft/Fabric adapters. |
| `agent-direction` | Engine agent/application code calls ports and does not depend on server, client, transport, infrastructure, presentation or Minecraft/Fabric adapters. |
| `server-to-client` | Server implementation does not depend on client implementation. |
| `optional-integration-isolation` | YACL and Mod Menu references are confined to `dev.friendai.client.config`. |
| `presentation-feature-view` | Presentation may consume a feature's `view` package; it may not depend on feature internals. |
| `presentation-to-adapter` | Presentation does not depend on agent orchestration, server/client adapters, transport, infrastructure or Minecraft/Fabric. |
| `protocol-direction` | Protocol contracts do not depend on presentation, infrastructure, server/client adapters, transport or Minecraft/Fabric. |
| `runtime-service-graph-boundary` | Only classes in `dev.friendai.server.runtime` may depend on its package-private `ServerServiceGraph`. |

The test fixtures prove that engine-to-platform/UI/native, feature-to-server, agent-to-adapter, server-to-client,
foundation-to-platform, foundation-to-world, world-to-feature, optional-integration leaks, presentation-to-adapter,
protocol-to-adapter and presentation-to-feature-internal edges fail with the exact source and target classes. Feature
view dependencies and YACL from client config pass. These package names describe the target architecture; the rules
apply as those packages are introduced.

`foundation/` and `world/` are the shared kernel: a feature or an adapter may depend on them, never the other way
round. A pure policy both layers need moves into `foundation`; a Minecraft adapter both layers need moves into `world`.
A cycle between two feature packages is a design error, not a baseline entry: the shared part moves into the kernel, or
one feature becomes a port. `FeatureGraph` checks the cycle rule over class-level edges, and
`architecture/known-feature-cycles.txt` no longer exists.

## Inherited class-level exceptions

`compiledProjectEdgesMatchOnlyDocumentedClassLevelExceptions` is red on a small fixed set of class edges inherited from
before the kernel extraction (`features/brewing` → `features/enchanting.MenuBlockTask`, `features/social` →
`features/memory`, `features/coordination` → `server`, `features/appearance` → `presentation.ClientSessionId`,
`features/cartography` → `server.ServerRuntime`, `presentation` → `features/cartography.TerrainMapView`). They are
package-level cycles and adapter leaks that Wave 4 removes. The manifest stays empty on purpose, so this test fails
until they are fixed rather than hiding them in a list: no Wave 1 change may add an edge to it, and the set may only
shrink.

## Runtime service graph

`ServerServiceGraph` is package-private in `dev.friendai.server.runtime`; runtime construction, lifecycle and tick
orchestration are its only callers. The architecture test rejects edges from other packages. The legacy dependency
manifest is retained as an empty header-only file; no transitional class-edge exceptions are permitted.

## Source and registration scope

Language-key scans cover `engine/src/main/java`, `src/main/java` and `src/client/java`; translation catalogs are
collected from the engine and root production resource roots. The typed-placement-failure scan covers all engine and
root main Java sources. Resource lookup checks every production resource in the engine and root main resource roots.
Fabric and GameTest metadata entries must be unique and resolve to their current source classes. The architecture
test checks that every GameTest class declaring `@GameTest` or `@GameTestGenerator` is registered; do not maintain a
manual entrypoint count here.

The legacy `dev/friendai/core` package is removed: server-facing feature adapters live under `server/<feature>/`,
server outcome facts in `server/agent/`, and the shared Minecraft text adapter is `common/Components`. A feature's
Minecraft packages keep depending only on the platform and the task-registration API.

CI's unit-test job runs the root test task, which includes `ArchitectureRulesTest`; the same task also retains the
existing language-key and typed-failure scans.
