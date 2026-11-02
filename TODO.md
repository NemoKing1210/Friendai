# TODO

Work items to implement later, newest at the bottom. Pick one by its number ("do task 2").

**Numbers are stable.** Never renumber an existing item, not even when one above it is finished: a number said out loud in a session must keep pointing at the same work. A finished item is ticked `[x]` and moved to [TODO_ARCHIVE.md](TODO_ARCHIVE.md) with its number intact; sub-items keep theirs (`2.3`). The archive is read-only history: never renumber or reuse an archived number.

**Choose whole items, not slices.** Asked to pick work, take a top-level item with its own number (`17`), never a sub-item (`17.1`): a sub-item is one slice of its parent and is done as part of it. An explicitly named sub-item ("do 17.1") is an order and is the one exception. The full rule is in `AGENTS.md`, "Working from TODO.md".

**An item is claimed before it is worked on.** `- [~]` instead of `- [ ]` means a session has taken it. That marker alone is the claim: do not add a session name, a timestamp or an `**In progress**` line under the item. The marker stays uncommitted while the task runs and is committed only when the whole task is done, together with its files — never as a separate claim commit, so in a shared checkout the uncommitted `[~]` is what the other session sees. Do not start a claimed item. Finish: `[x]`, move it to [TODO_ARCHIVE.md](TODO_ARCHIVE.md). Stop: restore `[ ]` and say why in the final message. A `[~]` older than 24 hours with no active session may be taken over. The full rule is in `AGENTS.md`, "Working from TODO.md".

## Open

## Done

Completed items are kept in [TODO_ARCHIVE.md](TODO_ARCHIVE.md); their numbers stay stable across both files.
