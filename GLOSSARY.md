# Glossary

A quick reference for the words used in `AGENTS.md` and `ARCHITECTURE.md`. `ARCHITECTURE.md` is canonical for class names, flows and behavior, and a definition here never overrides it.

- **Companion** — the NPC entity (`CompanionEntity`), one per owner, carrying the inventory and hunger.
- **Owner / trusted / guest** — the player who summoned the companion, a player the owner trusts, and anyone else who talks to it.
- **Brain** — `CompanionBrain`, the per-owner agent loop that turns messages into LLM requests and tool calls.
- **Turn** — one user message plus the tool calls and final reply that answer it.
- **Goal** — one objective on the owner's `GoalBoard`, with criteria, queue-plan IDs, lessons and a replan budget; goals live in an owner file, so they outlive the entity.
- **Shift (free time)** — one autonomous block of the companion's free time, planned by `ShiftPolicy`, budgeted per hour and closed with an outcome and a lesson.
- **Skill** — a player-facing ability exposed to the LLM as a `Tool`, usually implemented by a `Task`.
- **Instruction skill** — bounded plain-text procedural guidance stored in `skill/`, selected per turn; it advises but never executes or changes tool permissions.
- **Description** — the companion summary/details plus the owner's explicit language/address profile in memory format 4.
- **Tool** — the LLM-facing definition (schema, access level, executor) registered in `FriendaiTools`.
- **Task** — long-running world work driven tick by tick by `ExecutionCoordinator`.
- **Reflex** — work ticked before the task runner every server tick (combat, fire, self-care, recovery); it may hold the runner for a few ticks.
- **Hold** — a scoped suspension of the task runner (`HoldReason`) that keeps the active task and resumes it; the owner's pause is its own hold.
- **Checkpoint / restore** — a bounded, versioned `TaskState` saved with the execution document and rebuilt by `TaskRestoreRegistry` on load; unsupported or unauthorized work is rejected, not run.
- **Plan** — an ordered list of tasks (`run_plan` / `SequenceTask`).
- **Blueprint** — a saved or generated block layout that `BuildTask` places.
- **Station** — a crafting/cooking block the companion uses; a `StationLease` remembers one it placed.
- **Perception** — the surroundings and owner focus rendered into prompt text.
- **Snapshot** — the menu state built by `SnapshotBuilder` and sent to the client.
- **Owner store** — one versioned JSON file per owner under `<world>/friendai/…`, written by `OwnerFileStore` with debounced, atomic replacement and a `.bak` on corruption.
- **Provider / preset / route / protocol** — where an LLM request goes and how it is encoded (engine `agent/llm/provider` and `agent/llm/protocol`; root `llm/http/HttpTransport` implements the engine `JsonHttpTransport` port).
