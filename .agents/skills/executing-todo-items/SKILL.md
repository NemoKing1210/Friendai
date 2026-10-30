---
name: executing-todo-items
description: "Use when asked to pick or implement a work item from TODO.md in the Friendai checkout (\"do task 2\", \"take the next task\", \"implement TODO 24\") or when resuming an item already marked [~]."
---

# Executing a TODO item

## Overview

An item in `TODO.md` is a contract with an evidence trail: its anchors (`File.java:line`) drift, and the work may already be half-shipped. Prove the item is still open and that the change cannot silently undo something else **before** the first code line. `AGENTS.md` states the rules; this skill is the order of operations that satisfies them for one numbered item.

## Gate 0 — claim and observe (the first edit)

1. `grep -nE '^\s*- \[~\]' TODO.md`, `git status`, `git log --oneline -5`. A foreign `[~]` means stop: the item is taken. Dirty files may belong to a live session.
2. Flip `- [ ]` to `- [~]` on the item you take (the parent for a whole item; a single line only when the maintainer named that sub-item). No session name, timestamp or `**In progress**` line, and never a separate claim commit.
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

Final message: item and sub-items with what each became, the Gate 1 verdict, the regression checks that ran, the tests you actually ran with results, the version and jar path, what you could not verify, and the foreign changes you noticed.

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
