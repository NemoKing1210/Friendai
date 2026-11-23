---
name: delegating-work
description: Use when a Friendai task splits into independent parts that can run in parallel, or when deciding whether to spawn subagents at all.
---

# Delegating work

`AGENTS.md` "Working with subagents" states the rules. This skill is the split, the brief and the integration.

## When to delegate

Delegate when the task splits into two or more independent parts: separate modules or files, parallel searches, comparing options, review passes. Launch them in parallel in one step, not one after another.

Do not delegate dependent steps, tiny tasks, or design decisions that need one continuous context. If the brief would be longer than the work, do it yourself.

## Split by ownership

- Each child owns a set of files, a module or one review dimension. Two children never write the same file.
- If two pieces must touch one file, run them one after the other or keep that file in the main session.
- Fix the contract first: write the interface, shared types and file layout before launching children. Children fill in only their own files.
- Read-only work (searches, call-path tracing, log reading, independent reviews) changes nothing and may overlap with writers.
- Keep design in the main session.

## Brief template

A child does not see the conversation. Make each brief self-contained:

- **Goal** and why it matters, in one or two sentences.
- **Paths** to read and, for writers, the exact files it may change.
- **Constraints**: the relevant `AGENTS.md` rules (layer boundaries, threading, English only, no commits unless asked) and what is out of scope.
- **Result format**: for example, "reply with a summary under 200 words plus a `path:line` list".
- **Done when**: a checkable criterion (a test passes, a symbol is found, a list is complete).

## Isolation

- Writers own disjoint files. Prefer a separate branch or worktree when the tool offers it.
- Only one agent runs `./gradlew` in a checkout at a time: parallel builds fight over the Gradle lock and the shared `run/` directory. Give builders their own worktree, or run the build yourself after integrating.
- Never hand a child an expensive model. A child does bounded mechanical work; if a piece seems to need an expensive model, split it smaller or do it yourself.

## Integrate

- Subagent reports are claims, not proof. Read the diff yourself.
- Resolve conflicting edits yourself; do not ask a subagent to settle a conflict between subagents.
- Run `./gradlew assemble verifyModArtifact` plus the covering test group in the main session, and never pass a child's report on as your own verification.

## Adapter notes

- **Claude Code**: tool `Agent` (formerly `Task`). Custom agents in `.claude/agents/*.md` with YAML frontmatter (`name`, `description`, `tools`, `model`; `isolation: worktree` for an isolated checkout).
- **Codex CLI**: spawn agents explicitly; custom agents are TOML in `.codex/agents/` (`name`, `description`, `developer_instructions`). Prefer parallelism for read-heavy work and be careful with concurrent writes.
- **opencode**: `Task` tool and `@name` mentions; custom agents in `.opencode/agents/*.md` with `mode: subagent`.
- **pi / oh-my-pi (`omp`)**: `task` and `wait` tools; custom agents in `.omp/agents/*.md` or `pi` agent extensions.

When the environment has no subagents, do not invent them: split the work into independent steps and read only what you need.
