---
name: analyzing-logs
description: Use when the maintainer asks to analyze Friendai game or companion logs for errors, wrong logic, stalls or slowdowns ("анализ логов", "разбери логи", "что не так в логах").
---

# Analyzing game logs

## Overview

Read the session's evidence, follow each suspicious record back to the turn or task that caused it, and decide — against the code — whether the log shows a defect, a misleading report, or expected behavior. The output is a report and proposals; nothing is edited.

The log surface is described in `README.md`, "Logs" (paths, settings, commands) and `ARCHITECTURE.md`, `foundation/diagnostics` plus the task-diagnostics paragraph (what each record kind carries). Read both before the first query.

## Read-only and privacy contract

- No code, guide, `TODO.md`, configuration or log edits. Do not change the logging level.
- Raising the level on a server that carries other players' chat needs their knowledge; never do it on your own.
- Quote the minimum: a `seq`, an `event`, a short `msg` or one `data` field. Never a whole prompt or reply body, a full stack, coordinates the finding does not need, or any secret.
- A bundle or a `detailed` file is not pasted anywhere outside the local report unread.

## Locating the files

Try in order and stop at the first that exists; record every root searched:

1. The path the maintainer pasted, or the one `/friendai logs path` printed.
2. The newest `<world>/friendai/logs/` tree under `run/saves/` in this checkout.
3. The client tree `run/logs/friendai/client/`, with its `index.jsonl` naming the world each session belonged to.
4. The standard launcher game directories: `%APPDATA%\.minecraft`, `%APPDATA%\ModrinthApp\profiles\<profile>`, `%APPDATA%\PrismLauncher\instances\<instance>`. Each save keeps `friendai/logs/`; the shared `logs/friendai/` holds the client records.
5. `logs/latest.log` and `crash-reports/` beside the game directory the tree came from.
6. `build/gametest-shards/<class>.log` and the build log when the question is about tests.

A guessed path is never reported as found. When no durable tree exists anywhere — a dev `run/` checkout routinely has `latest.log` and no JSONL — read what does exist, say plainly that the mod's own records are absent, and ask for the path or the world. That is a normal outcome, not a failure.

## Inventory

- List files newest-first with size and modification time; read a session's `.md` digest first, then the matching `.jsonl`.
- File names: `boot-<id>.jsonl` (server), `session-<time>-<id>.jsonl` (owner), `client-<id>.jsonl`; a rotation adds `-2`, `-3` before the extension. Each has an `.md` digest; each tree has a `README.md`. An older digest belongs to an older boot.
- Read the header record first: `head -1 "$f" | jq '{level, categories, world, mod, minecraft, started}'`. Its `level` is the configured detail level. At `normal`, prompt, reply and tool bodies are absent by design — a missing body is not evidence of a bug.

## Record shape

Records are JSONL. Field names from `LogJson`:

`seq`, `timeMillis`, `level` (lowercase), `category` (lowercase), `kind` (`header`, or `USER`, `ASSISTANT`, `TOOL_CALL`, `TOOL_RESULT`, `ERROR`, `TASK`, `MANUAL`, `PLANNER`, `REQUEST`, `RESPONSE`), `event`, `owner`, `companion`, `session`, `turn`, `plan`, `job`, `task`, `toolCall`, `cause`, `durationMs`, `data` (string values), `msg`, `error` (`type`, `msg`, `stack`).

The header line has `kind: "header"`; exclude it from every record query.

## Triage queries

Run over the selected window (one session, one boot, or the last N minutes by `timeMillis`). `jq` on Git Bash:

```bash
f=session-*.jsonl
# error and warn records
jq -c 'select(.kind != "header" and (.level == "warn" or .level == "error")) | {seq,timeMillis,level,category,event,msg,cause}' "$f"
# gaps, stalls and long episodes
jq -c 'select(.kind != "header" and (.event == "clock.gap" or (.event | test("^task\\.(progress|phase|hold)$")) or (.durationMs // 0) > 5000)) | {seq,event,cause,durationMs,data,msg}' "$f"
# refusals and blockers
jq -c 'select(.kind != "header" and (.event | test("^(task\\.reject|queue\\.block|plan\\.failed|plan\\.fallback)$"))) | {seq,event,plan,job,task,msg,cause}' "$f"
# claimed completion against verified outcome
jq -c 'select(.kind != "header" and (.event | test("^(outcome\\.check|plan\\.finish|task\\.finish|inventory\\.delta)$"))) | {seq,event,plan,job,task,data,msg,cause}' "$f"
# provider trouble, retries, dropped records
jq -c 'select(.kind != "header" and (.event | test("^(llm\\.(retry|error|discarded)|event\\.dropped|log\\.(dropped|suppressed|health))$"))) | {seq,event,msg,data,cause}' "$f"
# routing and planning decisions
jq -c 'select(.kind != "header" and (.event | test("^(route\\.decision|plan\\.(request|delivered|start))$"))) | {seq,event,plan,turn,data,msg}' "$f"
```

PowerShell equivalent for one query (the rest follow the same shape):

```powershell
Get-Content $f | ForEach-Object { $_ | ConvertFrom-Json } |
    Where-Object { $_.kind -ne 'header' -and $_.level -in 'warn','error' } |
    Select-Object seq, timeMillis, level, category, event, msg, cause
```

Then look for patterns, not only single lines: the same `event` repeated for one `plan`/`job`/`task`, the same blocker text twice (`AcquisitionAttempts.MAX_SAME_BLOCKER`), a `task.finish` claiming success with no matching `inventory.delta`, a quiet period with no `task.progress`, `log.dropped`/`log.suppressed` that explains a suspected gap.

## Cause tracing

- Every record carries `cause` = the `seq` that started it (0 when none). Walk it upward until 0 and write a `seq`-ordered timeline.
- `seq` is the file sequence; the live ring's pagination ID is a different identifier. Never mix them.
- Bounded walk, and stop at a cause that is not in the selected file (a rotation boundary) — say so.

## Code mapping

For each suspicious record, find the emitter and read it:

- `engine/src/main/java/dev/friendai/foundation/diagnostics/LogEvents.java` for the event constants;
- the task or service named in `task`/`toolCall`/`event` (grep the event string);
- the message key or typed reason in `msg`/`data`.

Decide and state which of the three it is: a **defect** (the code does something wrong), a **misleading report** (the behavior is right, the record says otherwise), or **expected** (the record documents a normal path). Quote `path:line`.

## Cross-checks

- `latest.log` and `crash-reports/`: a stack trace or a crash around the same time.
- A failed GameTest class from `build/gametest-shards/<class>.log` or the CI shard log.
- The client tree when the symptom is on the client (`client-*.jsonl`, `index.jsonl`).
- When the log names an item or a spec (`plan.md` named in `TODO.md`), read it for the intended behavior.

## Symptom mode

When the maintainer names one symptom, run the triage that applies to it, trace every matching record, and list the queries you skipped instead of pretending the full sweep ran.

## Report

Write `docs/superpowers/audits/YYYY-MM-DD-logs-<scope>.md`, in English:

```
mode: full | symptom: <text>
head: <sha>
dirty: none | <paths>
level: <off|normal|detailed|trace>
window: <date/time range, boot id or session file>
files: <paths read>
```

Then the findings in the shared schema (`area`, `severity`, `category`, `location`, `evidence`, `impact`, `proposal`, `verification`), with log evidence as file plus `seq`/`cause`; then `## Not read` (files skipped, queries skipped, rotations not opened); then `## Proposed TODO items`.

Finish with a short Russian chat summary: the window and files, counts by severity, the top findings, the report path. `docs/` is git-ignored; never commit the report.

## Red flags — STOP

| Thought | Reality |
|---|---|
| "The JSONL is missing, so there is no evidence" | `latest.log` and crash reports are evidence too. Say what was read and what was not. |
| "`normal` level hid the prompt — that is the bug" | At `normal`, bodies are absent by design. Say it is unverifiable, not missing. |
| "I found a folder, so it is the right world" | `index.jsonl`, the header's `world`, and `/friendai logs path` decide. Several worlds mean ask. |
| "The report can quote the whole reply" | Minimum fields only; no full bodies, stacks, coordinates or secrets. |
| "I'll turn on `trace` to see more" | Never on your own, especially on a server with other players' chat. |
| "The record says it failed, so the code is broken" | It may be a correct refusal or a misleading label. Read the emitter. |
| "One suspicious line is one finding" | A pattern in a `plan`/`task` history is the finding; a single line may be a normal path. |
| "I'll fix the wording while I'm here" | Report only; fixes need approval. |
