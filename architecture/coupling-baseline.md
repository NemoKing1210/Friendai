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

## Wave 1 outcome (measured after the kernel extraction)

The kernel extraction moved the shared vocabulary out of `features.navigation` and the other features that owned it,
and the feature graph is now acyclic with no baseline file. Re-measured on the same toolchain:

| Metric | `1ce4fef6` (baseline) | after Wave 1 |
|---|---:|---:|
| Feature-to-feature class edges | 786 | 294 |
| Strongly connected components | 2 | none |
| Bidirectional feature pairs | 11 | none |
| `foundation/` and `world/` kernel packages | 0 | 2 |

The shared code now lives where neither side owns it: pure pathing and geometry in `engine/.../foundation/spatial/`,
movement policy in `foundation/movement/`, the place values, protection rule and portal policy in
`foundation/places/`; the Minecraft kernel is `src/main/java/dev/friendai/world/{movement,block,food,equipment,
targeting,placement}/`. `architecture/known-feature-cycles.txt` is deleted; `FeatureGraph` fails on any cycle.

`ArchitectureRulesTest.compiledProjectEdgesMatchOnlyDocumentedClassLevelExceptions` is still red on a fixed inherited
set of class edges that Wave 1 did not create and did not fix: `brewing -> enchanting.MenuBlockTask` (2),
`social -> memory` (3), `coordination -> server` (5), `appearance -> presentation.ClientSessionId`,
`cartography -> server.ServerRuntime` and `presentation -> cartography.TerrainMapView`. They are package-level cycles
and adapter leaks outside a move-only scope; Wave 4 fixes or explicitly re-scopes them. The list may only shrink and no
new line may be added. Everything else in the architecture group passes.

The owner-store benchmark above still describes the code Wave 3 changes; its numbers were not re-taken in Wave 1.

Two GameTest reds are inherited from base and were measured on both trees: `pathing_tests_mines_whole_log_column`
("mined 7 of 8") and `structure_tests_*`, where `structure_tests_tool_build_special_builds_snow_golem` ("a snow golem
should exist") and `structure_tests_portal_trip_builds_lights_and_records_the_return_anchor` ("the portal did not take
me to minecraft:the_nether") fail identically at `1ce4fef6` and on the Wave 1 branch (25 tests, 2 failed, run
2026-11-04). They are environment/outcome-sensitive and are left to CI and the nightly run.

## Wave 3 measurement: owner-store and queue cost (measured, 2026-11-07)

Two opt-in harnesses, both run with `FRIENDAI_BENCHMARK=1`, write their full reports to the git-ignored
`engine/build/*.md`:

- `OwnerStoreBenchmark.runScenarios` adds a matrix over population (8, 64, 1,000, 10,000 owners), record size
  (small: 8 facts; large: 200 facts, about 6 KiB encoded) and dirty population (idle: nothing changed; few: 1 %;
  all: every owner), on the **production** `OwnerStoreWriter` (capacity 256) with one warm pass that is settled
  before sampling, then 2–5 samples per row. It reports the caller-thread snapshot+submit time, the drain to disk
  (a barrier runnable submitted after the flush), the `statuses()` health-view pass with this thread's allocated
  bytes, the retained heap, files on disk and `QUEUE_FULL` count. The older synthetic `run(...)` table above is
  unchanged and remains a direct-executor baseline, not a tick latency.
- `QueueCostBenchmark` builds a server-sized queue (13 plans, 37 jobs: flat plans, one structured plan stopped in
  its frame, one plan waiting for a decision, finished steps), warms 2,000 iterations and reports p50/p95 and
  bytes/op for `plans()`, `jobs()`, `QueuePolicy.next`, `QueuePolicy.statuses`, a `planStatus` pass over every plan
  and `PlanMaterials.needs` over the pending steps.

Measured on JDK 25, Windows 11, one run each (a second run reproduced the same shape):

| population | record | dirty | snapshot p95 (ms) | drain p95 (ms) | status p95 (ms) | queue-full |
|---:|---|---|---:|---:|---:|---:|
| 64 | small | idle | 0.015 | 0.063 | 0.130 | 0 |
| 64 | small | few | 0.298 | 2.309 | 0.215 | 0 |
| 64 | small | all | 1.202 | 110.167 | 0.243 | 0 |
| 64 | large | all | 2.121 | 101.700 | 0.209 | 0 |
| 1,000 | small | idle | 0.273 | 0.326 | 0.356 | 0 |
| 1,000 | small | few | 0.259 | 10.402 | 0.289 | 0 |
| 1,000 | small | all | 7.944 | 315.327 | 0.264 | 743 |
| 1,000 | large | all | 9.840 | 307.967 | 0.288 | 743 |
| 10,000 | small | idle | 0.145 | 0.189 | 0.468 | 0 |
| 10,000 | small | few | 0.416 | 124.964 | 0.561 | 0 |
| 10,000 | small | all | 66.017 | 385.267 | 0.827 | 9,743 |
| 10,000 | large | all | 98.413 | 452.659 | 1.245 | 9,743 |

| queue operation | p50 (µs) | p95 (µs) | bytes/op |
|---|---:|---:|---:|
| `plans()` | 0.121 | 0.273 | 169 |
| `jobs()` | 0.163 | 0.823 | 361 |
| `QueuePolicy.next` | 3.321 | 5.660 | 4,961 |
| `QueuePolicy.statuses` | 1.980 | 2.528 | 3,721 |
| `planStatus` pass over every plan | 5.109 | 21.902 | 5,817 |
| `PlanMaterials.needs` | 3.859 | 7.850 | 5,273 |

**Verdict: no production change.** One idle tick's queue work (selection + every plan's status + the material
reserve) is 35 µs at p95, 0.07 % of a 50 ms tick; at the queue sizes an owner actually holds the whole path is
dominated by fixed per-call allocation, not by an algorithmic surprise. The health view costs 0.13–0.36 ms for the
whole population (one `statuses()` pass over 64 to 1,000 owners, so a few microseconds per store) and runs once per
five seconds, so caching it would save a fraction of a millisecond.
The only large figures are a fully dirty 10,000-owner flush (60–98 ms of caller-thread encoding, then 9,743
`QUEUE_FULL` rejections because the writer queue is bounded on purpose and retries on the next pass) and the
drain, which is disk-bound at about 1.2–1.6 ms per atomic replacement here and barely differs between an 8-fact
and a 200-fact record. Revision-aware dirty scanning would not touch either: the cost is encoding and per-file
replacement, not the scan that already skips unchanged owners. Both contracts the plan protects are unchanged:
queue-full work stays dirty and retryable, and read-only/backup/atomic-replace/shutdown bounds are untouched.
