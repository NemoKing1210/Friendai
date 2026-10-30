---
name: executing-todo-items
description: "Use when asked to pick or implement a work item from TODO.md in the Friendai checkout (\"do task 2\", \"take the next task\", \"implement TODO 24\") or when resuming an item already marked [~]."
---

# Executing a TODO item

## Overview

An item in `TODO.md` is a contract with an evidence trail: its anchors (`File.java:line`) drift, and the work may already be half-shipped. Prove the item is still open and that the change cannot silently undo something else **before** the first code line — and put the item back to the maintainer instead of building it when the evidence says it would regress, outgrow its scope, or rest on a stale premise. `AGENTS.md` states the rules; this skill is the order of operations that satisfies them for one numbered item.

## One item per run — then stop

One invocation of this skill executes **exactly one numbered item**, its sub-items included, and then the run ends.

- When the item is ticked, archived and committed — or restored to `[ ]` with a stated reason — report and **stop**. Do not start the next item in the same run, even when the request named several: the maintainer invokes the skill again for that one.
- A request that lists several items is a queue. Take the first, say in the report which ones are still untouched, and let the maintainer choose the next run.
- Never claim two items, never plan or execute two in parallel, never dispatch a subagent per item, and never fold in "a quick one" because it sits in the same file.
- A bug or idea you notice outside the item goes into the report (or `TODO.md`, when asked), never into this run's code.
- If the item depends on another open item that is claimed or unshipped, stop and name the dependency instead of doing both.

## Gate 0 — claim and observe (the first edit)

1. `grep -nE '^\s*- \[~\]' TODO.md`, `git status`, `git log --oneline -5`. A foreign `[~]` means stop: the item is taken. Dirty files may belong to a live session.
2. Flip `- [ ]` to `- [~]` on the one item this run takes (the parent for a whole item; a single line only when the maintainer named that sub-item). No session name, timestamp or `**In progress**` line, and never a separate claim commit.
3. Write down which dirty or untracked files are not yours. They are never staged, formatted or reverted.

## Gate 1 — prove it is not already implemented

Produce a table in the plan: **anchor → what the code says today → verdict (missing / partial / done)**.

- Read every `path:line` the item names, but find the symbol, not the line number (`find`, `xd://lsp` hover/references). Items are often stale by tens of lines.
- Search history and prior attempts: `git log --oneline -S'<symbol>' --all`, `git log --oneline -- <paths>`, `TODO_ARCHIVE.md`, `docs/superpowers/{specs,plans}/`.
- Test the actual behavior, not the wording: the default value, the missing tool, the absent field, the refusal path. Reading the file that should contain the behavior is the minimum; running the smallest scenario that shows it is better.
- **Done already**: stop, report the evidence (`path:line`, commit), and get the maintainer's word before ticking and archiving.
- **Partly done**: narrow the item in the plan and state what remains, or stop if the remainder no longer matches the item.
- A user report quoted in the item is ground truth **for the bug at report time**, not for the current code.

## Gate 2 — regression watch (before code)

List in the plan:

- every caller of the symbols you will change (`xd://lsp` references for exported symbols, `grep` for the rest), and what each expects;
- the tests that already pin the behavior you touch, and that must stay green;
- the contracts at risk: server-thread rule (rule 1), pure-layer imports (rule 8), persistence and wire formats, translation keys in both files, access levels, optional integrations;
- the behaviors that must not change, each paired with the test or GameTest that proves it;
- the blast radius on other open items — items 10, 22-24, 28-31 all touch the same hands/path/drop seam; state which seam you extend instead of duplicating.

No answer to "what could this break?" means the plan is not finished.

## Gate 3 — raise it for discussion instead of building it

A Gate 1 or Gate 2 finding is not a licence to proceed with a caveat in the report. When the work you can see ahead would regress something, contradict the item, or turn out to be a different job than the item claims, **stop before the first code edit and put it to the maintainer**, with the evidence and a recommendation.

Ask when any of these is true:

- the item's premise is wrong or stale: the code already does what the item asks, the bug has another cause, or the cited anchor shows a different design than the item assumes;
- the only way to satisfy the item breaks a behavior, contract or test that Gate 2 lists — server-thread rule, pure-layer imports, persistence or wire format, an access level, a translation key, another open item's seam;
- the change is materially larger or more invasive than the item states (a save/config schema break, a new sub-item, a rewritten interface, a dependency or Minecraft version move);
- two items collide on the same seam and doing this one half-way would block the other;
- the item depends on an unshipped or claimed item;
- the change needs a player-visible behavior decision (default value, refusal path, wording of a rule) that the item does not settle;
- the risk or cost is out of proportion to the value, and a narrower item would deliver the point.

How to ask — the question is part of the record, not a delay:

- Never ask about anything the repository, the docs or a running scenario can answer: read it first, and only the genuine trade-off goes to the maintainer. A question is not permission to stop investigating.
- Ask once, batched, with options that differ materially: proceed as specified / narrow to what is safe / do the alternative you propose / abandon the item. Say which one you recommend and why, in one or two sentences of facts.
- Carry the proof: `path:line` of the conflict, the test that would break, the schema version, the cost. "This may regress" without a name is not a question.
- Leave the `[~]` marker in place while the answer is out: the item is still yours and still claimed. Restore `[ ]` only when the item is abandoned.
- Write the ruling into the plan or spec next to the slice it changes, then execute the chosen option. Do not re-argue it after the answer, and do not "fix" the other option on the side.
- No answer yet, and other reachable work exists inside the same item? Do that work while waiting; do not touch the disputed slice.

## Plan before code

Follow `AGENTS.md`, "Working from TODO.md": spec to `docs/superpowers/specs/`, plan to `docs/superpowers/plans/`, both git-ignored, both naming the item. Skip them only when the whole change fits two sentences and the direction is settled.

The plan is a list of slices, and every slice carries: the files it touches, its test (the failing assertion first), its verification command, and the behavior from Gate 2 it must not move. Order slices so each one is runnable alone. Name the tests you will extend, and how you know a green run is not a vacuous one.

## Execute

- TDD per slice, batched: write the slice and its tests, format, then run **once**. Read every failure before editing again.
- Test scope while working: the covering unit group and at most one filtered GameTest class (`AGENTS.md`, "Test scope"). Never `build`, `check` or a bare `runGameTest` "to be safe".
- A check that passed on its first run proves nothing until you have seen it fail for the right reason. Break the guarded line once inside the batch.
- Every world change is exercised once before the commit: a filtered GameTest class or a real client/server session, and the report says which.

## Verify before saying done

- `assemble verifyModArtifact` compiles and packages (JDK 25; `sh ./gradlew …` on this Windows checkout).
- `spotlessApply -PspotlessFiles=<your files>` (never a bare run in a shared checkout).
- Quote real numbers: unit-test count, the `All N required tests passed` line or the filtered class result. State plainly what was left to CI and what could not be verified (real provider, microphone, GUI).
- Re-read your own diff against Gate 2 before committing.

## Close the loop

In the commit that carries the change:

- tick `[x]` and move the item (number intact, sub-items with it) to `TODO_ARCHIVE.md`; never renumber anything;
- bump `mod_version` by the change size, or say why no bump is needed;
- `ARCHITECTURE.md` / `AGENTS.md` when a package, tool, config or recipe changed; both language files for player-facing text (`LangFilesTest`); `site/app.js` plus `site/i18n.ru.js` when the capability map shows the thing; `MANUAL_CHECKLIST.md` for what automation cannot cover;
- stage only your paths (`git add <path>…`), never `-A`, and name foreign dirty files in the final message instead of committing them.

Final message: item and sub-items with what each became, the Gate 1 verdict, any Gate 3 question and the ruling it received (or that it is still waiting), the regression checks that ran, the tests you actually ran with results, the version and jar path, what you could not verify, and the foreign changes you noticed.

Then stop. The run is over with this one item; the next item needs a new invocation.

## Red flags — STOP

| Thought | Reality |
|---|---|
| "The item says the code is broken, so it is" | The item may predate a fix. Gate 1 first. |
| "Tests are green, so there is no regression" | Green means the checked paths are fine; the unfound caller is the risk. Gate 2. |
| "I'll run the full suite to be safe" | 9+ minutes, shared lock, other sessions. Run the covering group. |
| "The line number matches, so the anchor is valid" | Numbers drift. Read the symbol. |
| "The spec is obvious" | Medium item or an interface/save/config change: write it, one paragraph is enough. |
| "The marker/version/docs can land later" | They land in the same commit, or the item is not closed. |
| "That dirty file is probably mine" | Unknown dirt is someone else's; leave it and say so. |
| "The old test is in the way, I'll re-pin it" | A test that guards untouched behavior is a Gate 2 finding, not an obstacle. |
| "I'll build it and mention the risk in the final message" | Too late: the maintainer decided nothing and the regression is committed. Gate 3 asks before code. |
| "It probably regresses, but I can't name what" | Unnamed risk = unfinished Gate 2. Find the caller or the contract, then ask with the proof. |
| "Asking is stalling; I'll just pick the safer option" | A behavior, schema or scope choice is the maintainer's. Decide only the documented default, and say so. |
| "The item says X, so X is what ships" | A stale premise is a Gate 1/3 finding. Report it and ask, do not implement the stale version. |
| "The request lists three tasks, so I do three" | One item per run. Do the first, report the rest untouched, stop. |
| "I'm warm now, I'll just continue with the next one" | The run ends at the close-out. The next item needs a new invocation. |
| "The second one is a one-line fix in the same file" | Still a separate item, a separate claim and a separate run. |
| "I can run both items' tests in parallel" | Two claims break the `[~]` lock and the record. One Gradle run at a time. |
