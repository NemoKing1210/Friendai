---
name: clearing-todo-list
description: Use when the maintainer asks to clear, prune or tidy TODO.md of finished work in the Friendai checkout ("очисти список задач", "clear the todo list", "убери выполненные пункты").
---

# Clearing the TODO list

## Overview

This is the only procedure that removes lines from `TODO.md`. Ordinary work never does: an item is ticked `[x]` and stays where it is until an explicit clearing. A run of this skill deletes finished work, keeps every open or claimed item byte-for-byte, and does nothing else — no code, no guide edits, no version bump, no other file.

`AGENTS.md`, "Working from TODO.md" states the rules; this skill is the order of operations for the one job the maintainer has to ask for by name.

## Preconditions

Check all three before the first edit:

1. `git status --porcelain TODO.md` prints nothing. A dirty file may hold another session's uncommitted edit or claim; stop and report it.
2. `grep -cE '^\s*- \[x\]' TODO.md` is at least 1. With none, report "nothing to clear" and stop.
3. **A claimed `[~]` item does not block the run.** It is live work and is preserved exactly. Clearing a file that carries a committed claim can collide with that session's later `TODO.md` commit; say so in the report and leave the conflict to git. Never take over, tick, move or delete a claim.

## Snapshot first

Record, so every deletion can be checked afterwards:

- the exact `[ ]` and `[~]` lines (`grep -nE '^\s*- \[[ ~]\]' TODO.md`);
- the existing `**Retired numbers.**` line, if any;
- the highest top-level number in the file;
- the number of top-level `[x]` items and of their `[x]` sub-items;
- every `##` heading and every four-space-indented `**…**` body paragraph.

## Delete finished blocks

A finished item owns its **whole block**: the top-level `- [x]` line through the last line before the next column-0 `- [` line or `##` heading. That block includes its indented sub-items, its `**Trace and baseline.**` / `**Regression checks.**` paragraphs and any table that belongs to it. Trailing blank lines at the end of a removed block go with it; the blank line separating it from the next surviving item stays.

- Under an **open (`[ ]`) or claimed (`[~]`) parent**, delete only the finished sub-item blocks. The parent and its own paragraphs stay.
- Never delete or rewrite a `[ ]` or `[~]` line, the file header, the `## Items` heading, or a `##` section that still describes open or claimed work.
- Never renumber, reword or reorder a surviving item, and never touch a `**Skipped:**` note.

## Batch documentation

The dated paragraph under `## Items` and the trailing `## Audit coverage` section document the batch that was just cleared.

- Remove them **only when no open or claimed item remains** after the deletion.
- Otherwise keep both and name in the report every reference that now points at a cleared number.

## Retired numbers

Numbers are never reused. Update the header line to exactly:

`**Retired numbers.** Numbers up to and including N are reserved and never reused; a cleared item's number resolves through git history, and a surviving open or claimed item keeps its number.`

N is the **larger** of the N already on that line and the highest top-level number in the file. Read the existing line first: after a deletion the file no longer records the highest number ever used, so recomputing N from the file alone would let a cleared number be issued again. Never remove or shorten the line. The next new item gets N+1.

If the line does not exist yet, add it after the "**Numbers are stable.**" paragraph.

## Verify

Run after the edit and compare with the snapshot:

- `grep -cE '^\s*- \[x\]' TODO.md` prints `0`.
- Every `[ ]`/`[~]` line from the snapshot is still present, byte-identical: `diff` the sorted greps and expect no output.
- `grep -n '^## ' TODO.md` still lists `## Items`, plus `## Audit coverage` when it was kept.
- `grep -n 'Retired numbers' TODO.md` shows the new N.
- Every surviving four-space `**…**` paragraph sits under a surviving item; none is orphaned under a deleted one.
- No number in the file or its sub-items was reused.

A failed check means the tree is not committed and the file is restored from the snapshot notes — never with `git checkout`, `restore`, `reset` or `stash`.

## Commit and report

- Stage only `TODO.md` (plus the guide edits of the rule-change commit when that has not landed yet). Never `git add -A`.
- Commit as `docs(todo): clear <a> items, retire numbers up to N`.
- `TODO.md` is not in `scripts/check-docs.sh`'s default file list, so no docs check is needed for it.
- Report: top-level and sub-item counts removed, the preserved open and claimed items by number, the retired range, the commit, and any foreign dirty files noticed.

## Failure paths

- Dirty `TODO.md`, no `## Items` heading, or a failed post-edit check: stop with the tree unchanged and say what was found.
- A block boundary that is not obvious (an unusual heading, a list inside an item): stop and ask rather than guess, because a wrong boundary either strands a paragraph or eats the next item.
- Never "fix" the file with a destructive git command; another session may hold work in it.

## Red flags — STOP

| Thought | Reality |
|---|---|
| "The claim looks stale, I can delete it" | A claim is never yours to remove; preserve it and report it. |
| "Only the ticked line is the item" | The block includes its sub-items, paragraphs and tables. A stranded paragraph is a broken list. |
| "N is whatever the file's highest number is now" | After deletion the file's highest may be lower; take the maximum with the recorded N or a number gets reused. |
| "TODO.md is dirty but I only touch my lines" | A dirty file may be another session's uncommitted work; stop. |
| "`## Audit coverage` describes cleared items, remove it" | Only when no open or claimed item remains; otherwise it is still the batch's record and its references are reported. |
| "I'll clear and tick an item while I'm here" | This skill removes only; every other action belongs to its own procedure. |
| "I'll archive the deleted items somewhere" | No archive: git history is the record. |
