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
foundation-to-platform, optional-integration leaks, presentation-to-adapter, protocol-to-adapter and
presentation-to-feature-internal edges fail with the exact source and target classes. Feature view dependencies and
YACL from client config pass. These package names describe the target architecture; the rules apply as those packages
are introduced.

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

CI's unit-test job runs the root test task, which includes `ArchitectureRulesTest`; the same task also retains the
existing language-key and typed-failure scans.
