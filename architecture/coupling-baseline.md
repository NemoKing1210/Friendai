# Coupling and owner-store baseline

Measured on 2026-11-04 from `main` at commit `1ce4fef6`, mod version `0.209.0`, on Temurin
`25.0.4.1+1-LTS` (Windows 11). This file is the committed comparison point for Wave 1 and Wave 3 of the
architecture consolidation. The generated detail lives in `build/architecture-report.md` and
`engine/build/owner-store-benchmark.{json,md}` and is not committed.

## How the numbers were taken

- The coupling inventory is built from compiled project class edges collected by `jdeps` in
  `ArchitectureRulesTest` (`ArchitectureFixtures.collectCompiledProjectEdges`), not from source text.
- Source-import counts below scan `engine/src/main/java`, `src/main/java` and `src/client/java` and count
  `import dev.friendai.features.*` lines across feature boundaries.
- The owner-store benchmark uses `OwnerStoreBenchmark` in `engine/src/test`, a direct executor, a temporary
  directory per population and a deterministic `UUID(0, i)` per owner. It writes one row per population and
  deletes its work tree. Heap is `totalMemory - freeMemory` after two `System.gc()` calls: an approximate
  retained-heap signal, not a JOL measurement.
- Above 200 owners the per-owner latency pass samples owners because `OwnerFileStore.queueDirty()` scans every
  loaded value on each flush; the batch total and file count remain exact.

## Coupling at 0.209.0 compared with the design baseline at 0.204.0

| Metric | 0.204.0 (`9eb9e0fd`, design §2.2) | 0.209.0 (`1ce4fef6`, measured) |
|---|---:|---:|
| Java files / packages | 1,301 / 209 | 1,322 / 212 |
| Unit test files / GameTest classes | 565 / 112 | 587 / 111 |
| Feature-to-feature import statements / ordered pairs | 419 / 70 | 429 / 84 |
| Bidirectional feature pairs (class edges) | 9 | 11 |
| Imports into `features.navigation.pathing` from other features | 127 | 127 |
| Imports of `navigation.pathing.Pos` from other features | 73 | 73 |
| Compiled project class edges / feature-to-feature class edges | not recorded | 42,772 / 786 |

The dominant pattern is unchanged: shared scaffolding still lives inside a feature. `Pos` alone is imported by
73 classes outside navigation, and the navigation pathing package is still the spatial vocabulary of mining,
construction, fire, knowledge, places, farming, survival and acquisition.

## Strongly connected components (measured)

`FeatureGraph.cycles` over the compiled class edges returns two components:

1. `acquisition, combat, construction, cooking, crafting, farming, fire, inventory, knowledge, mining, navigation, placements, places, survival, trading`
2. `memory, social`

The first component is much larger than the nine direct pairs in the design: one-way edges into and out of the
cycle connect cooking, crafting, fire, inventory, placements and trading to it. Removing only the nine listed
pairs is not guaranteed to break this component; Wave 1 re-measures after each move.

## Bidirectional feature pairs (class edges, measured)

- `acquisition <-> knowledge`
- `acquisition <-> navigation`
- `combat <-> survival`
- `construction <-> mining`
- `construction <-> navigation`
- `farming <-> survival`
- `knowledge <-> navigation`
- `memory <-> social`
- `mining <-> navigation`
- `mining <-> placements`
- `navigation <-> places`

Compared with the design's nine, `acquisition <-> navigation` and `mining <-> placements` are additional.
The design's `places <-> navigation` pair is the same edge as `navigation <-> places` here.

## Top class fan-out (compiled project classes, measured)

| Class | Distinct outgoing project classes |
|---|---:|
| `server.agent.CompanionSessions` | 232 |
| `entity.CompanionEntity` | 198 |
| `client.config.FriendaiConfigScreen` | 194 |
| `agent.turn.CompanionBrain` | 145 |
| `features.construction.minecraft.task.BuildTask` | 145 |
| `features.combat.minecraft.CombatController` | 143 |
| `server.runtime.ServerServiceGraph` | 143 |
| `server.agent.FreeTimeDriver` | 142 |
| `server.execution.ExecutionCoordinator` | 129 |
| `server.net.CompanionRequestHandler` | 125 |

The top six are the Wave 2 hub cards, in the same order as the plan.

## Owner-store benchmark (measured)

| Owners | Load (ms) | Flush total (ms) | p50 flush (ms) | p95 flush (ms) | Files | Approx used heap (MiB) |
|---:|---:|---:|---:|---:|---:|---:|
| 8 | 2.77 | 11.70 | 1.46 | 1.74 | 8 | 5.3 |
| 64 | 14.79 | 71.22 | 1.21 | 1.49 | 64 | 5.4 |
| 1,000 | 140.51 | 1,200.44 | 1.26 | 1.66 | 1,000 | 5.6 |
| 10,000 | 1,173.96 | 10,209.47 | 1.41 | 2.06 | 10,000 | 7.4 |

The batch flush grows superlinearly (about 12 ms at 8 owners, 1.2 s at 1,000, 10.2 s at 10,000) while a
single-owner flush stays near 1.2–2.1 ms. That is the signature of `queueDirty()` scanning every loaded owner,
and it is the measurement Wave 3 must improve without changing data safety. Heap growth is modest at these
record sizes; the 10,000 row is a trend signal, not a production population.

## Inherited architecture-test state

At `1ce4fef6`, `ArchitectureRulesTest.compiledProjectEdgesMatchOnlyDocumentedClassLevelExceptions` is already
red with about forty inherited allowlist/direction violations (for example `navigation -> places`,
`navigation -> acquisition`, `social -> memory`, `coordination -> server`, `cartography -> server`,
`appearance -> presentation`, `brewing -> enchanting`). They predate this program and are outside Wave 1's
move-only scope. The new `featureDependencyGraphHasOnlyKnownCycles` test is the Wave 1 cycle signal and passes
against the recorded SCC baseline. Wave 4 must either fix or explicitly re-scope those inherited edges before
the architecture group can be fully green.
