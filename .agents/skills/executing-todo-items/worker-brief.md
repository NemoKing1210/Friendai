# Worker brief for one TODO item

A dispatch brief for a worker agent that implements exactly one numbered item from `TODO.md`. Copy the block below, fill `<N>` and `<title>`, and send it. The worker reads the checkout's own rules; this brief adds only the dispatch context, the timebox and the build discipline.

```text
You are completing one item of the Friendai TODO backlog. Your worktree is a private copy of
the repository; change nothing outside it.

Item: TODO.md item <N> — "<title>". Do the whole top-level item, including every open sub-item
under it. The item text, including its **Done when** clause, is the contract.

Read CLAUDE.md, AGENTS.md and .agents/skills/executing-todo-items/SKILL.md before the first
edit, and follow them. One item per run: do not start or claim another item.

Timebox: about ten minutes of anchors, callers and existing tests, then the first edit. The
spec and plan can be a few lines each. If the design is still unclear after that, implement the
simplest correct version and let tests decide the rest.

Build discipline (mandatory; the build after every edit is what makes a one-hour item take a
day):
- One Gradle invocation per batch of edits: finish the batch, run one ./gradlew compileJava
  (compileGametestJava when GameTest sources changed). If it fails, read every error, fix them
  in one pass, rerun once.
- One filtered test invocation per batch: pass each affected package as another --tests flag
  instead of starting a second run.
- One filtered GameTest run, only after the fixture code is complete.
- One final ./gradlew assemble verifyModArtifact.
- Run the full test or check at most once, at the end. Format once with
  spotlessApply -PspotlessFiles=<your files> before the final test run.
- Use exact paths from the item text or one rg --files lookup; a guessed path costs a round trip.

Gradle needs JDK 25 (a Temurin install is usually under C:\Program Files\Eclipse Adoptium).
Commit on your branch, staging only your own paths, and tick [x] for the item in the same
commit. Do not push, open a pull request, merge, force-push or run deployTestJar.

Report in under twenty lines: item number and title, branch, commit hashes and subjects, files
changed, the exact test commands with pass/fail counts and the GameTest result line, version
old -> new with the reason, spec and plan paths, what you could not verify, and any problem
spotted outside the item's scope.
```

The prohibitions, the `[~]` claim, the skip note, the documentation and translation updates and the version bump are the skill's job; restating them here only invites drift when the rules change.
