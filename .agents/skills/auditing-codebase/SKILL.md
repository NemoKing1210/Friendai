---
name: auditing-codebase
description: Use when the maintainer asks for a code audit or review sweep of the Friendai checkout, or for an audit of completed work ("аудит кода", "аудит выполненных задач", "найди ошибки и оптимизации").
---

# Auditing the codebase

## Overview

A code audit reads, reasons and reports; it changes nothing. Find defects, wrong logic, per-tick cost and wasted work across the whole codebase, prove each finding in the code, and hand the maintainer a ranked report with proposals. Code, `TODO.md`, the guides and configuration are edited only after the maintainer approves a proposal — and that edit belongs to its own task, not to this run.

`AGENTS.md` supplies the review lens: rules 1–12 ("Rules that keep it working") plus the failure modes it lists. `ARCHITECTURE.md` names what owns each part of a flow, `GLOSSARY.md` the terms.

## Read-only contract

- No code, guide, `TODO.md`, configuration or data edits.
- No Gradle build, server or client by default: an audit is reading. A finding that needs execution to confirm states the exact command and waits for approval.
- Reports and proposed items are the only output. Never commit anything.
- Findings may quote a few lines of code; they never quote a whole file.

## Mode and baseline

A **previous report** is a file under `docs/superpowers/audits/` named `YYYY-MM-DD-codebase.md` whose header has `mode:`, `head:` and `base:` lines. The two older audits in that folder (`2026-11-03-system-audit.md`, `2026-11-07-critical-systems-audit.md`) predate this contract; they are context, not a baseline.

- **No previous report → full pass.** Header `mode: full`, `base: none (full pass)`, `head: <sha>`.
- **Previous report → offer a changed-since pass** over `git diff <previous head>..HEAD` plus every uncommitted path, and say plainly that unchanged code is not re-read. Diff from the previous `head`, never its `base`: the previous pass already covered its own window. Header `mode: changed-since <date>`, `base: <previous head>`, `head: <sha>`.
- The maintainer may still ask for a full pass; that is a full pass regardless of history.

## Dirty tree

The tree is often dirty because several sessions share the checkout. Never refuse one, never clean it, never stash.

- Record every `git status --porcelain` path on the header's `dirty:` line (`dirty: none` when clean).
- Tag a finding in an uncommitted file `uncommitted` so it can be reproduced against the working tree.
- Uncommitted paths belong to every changed-since pass, whatever the diff says.

## Area split

A full pass covers every row. Adjust the rows by what the changed-since diff touches, and say which rows were skipped.

| Area | Where |
|---|---|
| foundation and infrastructure | `engine/src/main/java/dev/friendai/foundation`, `engine/src/main/java/dev/friendai/infrastructure` |
| execution, tasks and presentation core | `engine/src/main/java/dev/friendai/execution`, `engine/src/main/java/dev/friendai/task`, `engine/src/main/java/dev/friendai/presentation` |
| agent, planning, protocol and providers | `engine/src/main/java/dev/friendai/agent`, `engine/src/main/java/dev/friendai/protocol` |
| engine features | `engine/src/main/java/dev/friendai/features` (all 25 packages) |
| world, entity, pathing, movement and perception | `src/main/java/dev/friendai/` — `entity`, `pathing`, `movement`, `world`, `perception`, `combat`, `place`, `placed`, `structure`, `build`, `mining`, `tree`, `farming`, `fire`, `survival`, `skin` |
| tasks, tools and queue adapters | `src/main/java/dev/friendai/` — `task`, `tool`, `queue`, `crafting`, `creative`, `trading`, `integration`, `features` |
| server agent, brain, chat and sessions | `src/main/java/dev/friendai/` — `server`, `agent`, `brain`, `chat` |
| llm, voice and speech boundaries | `src/main/java/dev/friendai/{llm,voice}`, `engine/src/main/java/dev/friendai/features/{conversation,speech,instructions}` |
| commands, menu, transport and stores | `src/main/java/dev/friendai/` — `command`, `menu`, `net`, `transport`, `store`, `config`, `debug`, `skill`, `memory`, `knowledge`, `common` |
| client | `src/client/java/dev/friendai/client` |
| build, CI and scripts | `build.gradle`, `engine/build.gradle`, `gradle.properties`, `.github/`, `scripts/`, `.githooks/` |
| resources, mixins and entrypoints | `src/main/resources/`, `src/main/java/dev/friendai/mixin`, `src/gametest/resources/`, both `fabric.mod.json` |
| tests, fixtures and their honesty | `src/test`, `engine/src/test`, `engine/src/testFixtures`, `src/gametest` |
| guides against the code | `AGENTS.md`, `ARCHITECTURE.md`, `GLOSSARY.md`, `CONTRIBUTING.md`, `README.md`, `MANUAL_CHECKLIST.md` — a statement the code contradicts is a `docs` finding |

## Brief and dispatch

One read-only subagent per area, all independent areas dispatched in one batch on a cheap model. Each brief is self-contained:

- the paths it owns, and "read-only: change nothing";
- rules 1–12 of `AGENTS.md` as the lens, plus the failure modes list and the area's `ARCHITECTURE.md` sections;
- the finding schema below, and "return findings only, one block per finding";
- an instruction to mark uncertainty instead of guessing: "I could not confirm" is an acceptable result.

When subagents are unavailable, walk the areas in this session one at a time. Say in the report which way ran.

## Verify every finding

A subagent report is a claim, not evidence. For every returned finding:

1. Open the file at the quoted `path:line` and read enough around it to judge the claim.
2. Keep it only when the code shows what the finding says **and** the impact is concrete.
3. Check the callers of anything it calls wrong (rule 3–6 territory), so a guard elsewhere is not missed.
4. Otherwise drop it. The report's "Dropped" note says how many were dropped and why one representative was dropped.
5. Dedupe across areas: one owner, one location, one finding.
6. Record in `verification` what you actually did: "read `path:line`, caller `path:line` checked".

## Closed items as context

Closed `TODO.md` items explain why the code looks as it does and what it promised.

- An item whose **Done when** the current code no longer satisfies is a `bug` finding whose `proposal` names the item number; it is not a separate audit.
- Items come from the live `TODO.md` while they are there. After a clearing, read them with `git log -p -- TODO.md` over the retired range, or from the spec or plan named after the number.
- With no closed items in the list, say a full pass is cheapest before the first clearing rather than reading the whole history by default.

## Finding schema

Every finding, in the report and in the chat summary, is one row of these fields:

| Field | Content |
|---|---|
| `area` | the row above it belongs to |
| `severity` | `blocker` (crash, data loss, a broken rule 1–12), `major` (wrong behavior, false reporting, unbounded per-tick cost), `minor` (real but contained), `nit` (low value) |
| `category` | `bug`, `wrong-logic`, `perf`, `improvement`, `test-gap`, `docs` |
| `location` | `path:line` (plus `uncommitted` when it is in the working tree) |
| `evidence` | what the code shows, quoted minimally |
| `impact` | the concrete wrong outcome, never "may be a problem" |
| `proposal` | the smallest fix, or the `TODO.md` item text to raise |
| `verification` | how the orchestrator confirmed it |

Rank: blockers and data safety first, then wrong logic and false reporting, then per-tick cost and timeouts, then improvements, tests, docs.

## Report

Write `docs/superpowers/audits/YYYY-MM-DD-codebase.md`, in English:

```
mode: full | changed-since <date>
head: <sha>
base: none (full pass) | <previous head>
dirty: none | <paths>
```

Then: the ranked top findings (the table rows), the per-area detail, `## Not read` (areas skipped and why, any truncated brief), `## Dropped`, and `## Proposed TODO items` as ready-to-paste item text — proposed, never written into `TODO.md`.

Finish with a short Russian chat summary: what was covered, counts by severity, the top findings, the report path. `docs/` is git-ignored; never commit the report.

## Red flags — STOP

| Thought | Reality |
|---|---|
| "The subagent sounded confident" | It saw one area and no callers. Read the code yourself. |
| "This area is clean, the others are probably fine" | Skipped areas go in `Not read`; silence is not a pass. |
| "A dirty tree should be stashed first" | Never. Record it in `dirty:` and read the working tree. |
| "I'll fix this small one while I'm here" | Report only; the fix needs approval and its own task. |
| "The reviewer flagged it, so it goes in" | Unverified findings cost real fix cycles. Read the location. |
| "The spec did not mention this input, so it is a nit" | Grade what a player gets if it ships, not what the spec names. |
| "I can run the tests to confirm" | One Gradle run at a time per checkout, and an audit does not build. State the check and ask. |
| "The report can live in the branch" | `docs/` is git-ignored; the report stays local. |
