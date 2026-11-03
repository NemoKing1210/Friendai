# Changelog

All notable, player-visible changes to Friendai. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/); the version in each heading is the `mod_version` in `gradle.properties`.

The file starts at 0.87.0. Earlier versions predate it, so their history stays in `git log`. Every shipped change adds a line here; see `AGENTS.md`, "Changelog", for the categories and the wording.

## [Unreleased]

## [0.195.0] - 2026-11-03

### Changed

- A remembered resource site that a visit proves empty is forgotten, so the companion stops walking back to places it has already cleared instead of touring them site by site.

## [0.194.1] - 2026-11-03

### Fixed

- The game no longer crashes when a menu snapshot cannot be encoded: the companion menu closes with a warning in the log and the server keeps running.
- The settings screen opens and searches in every mode; a search that keeps only a category's buttons no longer fails to rebuild the screen.
- The companion says it is stuck and needs a decision when a goal is unfinished and its queue is idle, instead of standing still in silence.
- A crafting step that fails for a reason other than missing materials names the step, instead of reporting ingredients the companion already carries.
- A cancelled turn is written to the log once, not twice.

## [0.194.0] - 2026-11-03

### Changed

- A request for a crafted item is now planned from the finished item, so the acquisition planner works out the logs, planks, sticks and stone itself instead of the companion hand-counting an ingredient chain.
- A failed queue step now points at retrying it or replacing that step while keeping the rest of the plan, instead of cancelling the whole plan and starting over.
- A turn that recovers from a failed plan now receives the planning and repair guidance, and the standing working method tells the companion to prefer nearby wood and never to name a species the player did not ask for.

### Fixed

- The companion no longer starts a social line before it has heard the owner speak: it waits for the first owner message, learns the language from it, and delivers anything it noticed in the meantime only after that.
- A happening noticed while the owner is quiet, away or in danger is no longer marked as told before its line is actually spoken, so it is delivered once when the companion can speak instead of being replaced by a neutral question.
- The companion no longer fails a crafting step because it counted the ingredients for the finished tools by hand; the acquisition planner computes the chain from the live inventory after every step.

## [0.193.1] - 2026-11-03

### Fixed

- The game no longer crashes when the companion menu is open while the companion's log holds provider request or reply entries: the menu snapshot now carries those entries instead of failing to encode.

## [0.193.0] - 2026-11-03

### Added

- Structured logging: the server writes JSONL records with levels, categories, sequence ids and cause links under `logs/friendai/`, beside a readable Markdown digest, and the companion can search them with `logs_search` (owner only).
- A **Logs** settings category controls the detail level, categories, file sizes, retention and digests, and opens the folder from the screen; the Log tab has open-folder, copy-path and pause buttons.
- `/friendai logs path|level|prune|export` prints the folder, changes the level live, prunes old files and writes a diagnostic bundle.

### Changed

- `normal` writes metadata only; `detailed` adds prompt, reply and tool-result bodies after redaction, and `off` writes no files.

## [0.192.0] - 2026-11-03

### Changed

- The companion highlight now traces the body instead of drawing a box around it, and it starts switched off: turn it on with the new **Outline** switch in the companion menu's Settings tab, the **Display** settings, or the unbound **Companion outline** key.

## [0.191.0] - 2026-11-02

### Fixed

- Free-time planning keeps recent failure evidence, respects the need cooldown, and gives concise translated reports for newly encountered blockers without repeating the same recent failure or plan-blocker question within five minutes.
- Independent targetless actions are no longer treated as duplicate work across companions.
- Learning-only skill outcome turns can still use their permitted tools without narrating routine results; task cancellations remain in diagnostics without an unnecessary model turn.
- Language metadata is no longer offered as an ambient small-talk topic, and away summaries preserve translation keys and arguments until the owner returns.
- Planner guidance now shows the queue parser's condition shape and valid remembered-place semantics, reducing retries that discard intended guards.
- Nighttime autonomy now starts a shelter reflex when no local home is known; it builds when stocked, otherwise it lights the area and waits safely. Planner schemas no longer advertise a duplicate `local` search scope, and crafting docs clarify that `station` means a keep-or-take policy.

## [0.190.0] - 2026-11-02

### Added

- Streaming replies can speak their first guard-accepted sentence before the provider finishes; the complete reply remains authoritative and tool calls still wait for the whole response.
- A Voice setting can turn early streamed speech off and wait for the full reply. The first sentence is also withheld when safety cannot be decided.
- Early streamed speech now uses the full reply guard: coordinates, internal names, code fences, line breaks and unfinished numbers are held or reported incomplete instead of being spoken; a delivered prefix that the full reply later rejects shows one cut-off notice.

## [0.189.0] - 2026-11-02

### Added

- `travel_through_portal` walks into an existing lit nether portal and lets vanilla move the companion; a missing portal is a typed failure, never a direct dimension teleport.

## [0.188.0] - 2026-11-02

### Fixed

- An unknown local speech model id no longer silently loads Whisper `base`; recognition reports not-ready until a supported model is selected.

## [0.187.0] - 2026-11-02

### Changed

- Approaching a distant entity now uses the resource-journey legs and chunk leases, recreating the journey when the target moves, before the final local approach.

## [0.186.0] - 2026-11-02

### Changed

- Following an owner more than 64 blocks away now uses the resource-journey legs and chunk leases, recreating the journey when the owner moves beyond the leg corridor.

## [0.185.0] - 2026-11-02

### Changed

- A `go_to` target farther than 64 blocks uses the existing resource-journey legs and chunk leases instead of one local search, so a long walk crosses the loaded edge in bounded steps.

## [0.184.0] - 2026-11-02

### Changed

- A route is now trimmed by projecting the companion onto nearby legs, not only by an exact node match, so a walked leg is dropped without stepping back when the body is between grid nodes.

## [0.183.0] - 2026-11-02

### Added

- A replan for a structured plan tells the planner the pending action path, so a branch-aware proposal is prepared against the node it must repair.

## [0.182.0] - 2026-11-02

### Added

- `plan_patch` accepts a pending action path (`step 1 → then #1`) and converts it to a bounded plan route, so a branch or repeat body can be patched without hand-computing indices.

## [0.181.0] - 2026-11-02

### Added

- A plan blocked on a `tool_at_least` block now gets the smallest vanilla pickaxe from an explicit block-to-tool table, inserted once as an `obtain_item` prerequisite.

## [0.180.0] - 2026-11-02

### Added

- A replan proposal for an unstarted structured plan replaces its tree atomically through the plan patch path, and a proposal applied after the caller lost access is refused.

## [0.179.0] - 2026-11-02

### Added

- `plan_patch` applies a fingerprinted node-range patch to an unstarted structured plan, keeping its id and cursor; a started plan or a mismatched fingerprint is refused.

## [0.178.0] - 2026-11-02

### Added

- Plan fingerprints now cover the plan tree and cursor, and an unstarted structured plan can receive a bounded node-range patch behind its fingerprint; a started structured plan is refused.

## [0.177.0] - 2026-11-02

### Added

- `replan_plan` asks the planner to repair one stalled queued plan; the planner sees that plan, keeps its completed work, and patches its first pending flat step behind the fingerprint check.

## [0.176.0] - 2026-11-02

### Added

- A stalled plan can ask the planner for a repair through the new `Replan` event: the planner sees that plan instead of the primary goal, and the patch replaces its first pending step with the fingerprint check. Structured active frames remain refused.

## [0.175.0] - 2026-11-02

### Fixed

- `queue_edit replace` refuses to rewrite a step inside an active structured plan frame, so the interpreter cursor, branch choice and repeat counters cannot drift from the edited tree.

## [0.174.0] - 2026-11-02

### Added

- The companion menu has plan controls: pause, resume or defer the first queued plan; the server checks the target and the owner, and the global pause stays separate.

## [0.173.0] - 2026-11-02

### Changed

- The model is told that a missing guarded item is repaired once by the queue itself, so it states the requirement with `has_item` and does not duplicate the gathering work; `plan_help` explains the repair and its consent.

## [0.172.0] - 2026-11-02

### Added

- A planner proposal now carries the plan fingerprint it was prepared against; if that plan changed before the proposal is applied, the proposal is refused instead of replacing newer work.

## [0.171.0] - 2026-11-02

### Added

- A plan blocked on a missing guarded item gets one bounded `obtain_item` prerequisite inserted before the blocked step; the plan resumes when it finishes, and a repeated poll never enqueues the same remedy again.

## [0.170.0] - 2026-11-02

### Added

- `queue_show plan <id>` prints a short plan fingerprint and `queue_edit` accepts it as `expected`: an edit computed against older work is refused with a request to re-read the plan.

## [0.169.0] - 2026-11-02

### Added

- `plan_validate` now reports the declared prerequisites of the actions it would run (`smelt_items`, `brew_potion`, `place_block`), the evidence for each, and bounded proposed remedies, without changing the queue.

## [0.168.0] - 2026-11-02

### Added

- A stronghold search with an eye of ender spends one eye into a bounded compass hint; the eye guides the search but never reports the stronghold position as a find.

## [0.167.0] - 2026-11-02

### Added

- Two owners can agree on a bounded item handoff at a meeting point: both companions must be there, the source inventory is re-read, and the exact quantity moves once; a declined, interrupted or replayed offer moves nothing again.

## [0.166.0] - 2026-11-02

### Added

- A structure found by its marker blocks is remembered once as a landmark place, so a later structure search can travel to the verified instance instead of searching again.

## [0.165.0] - 2026-11-02

### Added

- `find_structure` and `search_structure` search for a village, pillager outpost, stronghold, nether fortress, bastion, ancient city or trial chamber through visible marker evidence; an unseen structure is never reported as found.

## [0.164.0] - 2026-11-02

### Added

- The companion menu has a **Map** tab: explored cells of the current dimension, remembered sites and walked roads, drawn from a bounded view rather than the full terrain record.

## [0.163.0] - 2026-11-02

### Added

- Owners can ask another owner's companion for one bounded, expiring piece of help, accept or decline it; an acceptance authorizes only the named help.

## [0.162.0] - 2026-11-02

### Added

- A streaming provider can deliver a reply as ordered sentences: the client plays them in order, never repeats one, and reports a cut-off answer once. **Stream replies** can be turned off in Voice settings to buffer the whole reply before speaking.

## [0.161.0] - 2026-11-02

### Added

- The companion can brew potions: `brew_potion` walks to a brewing stand, uses your water bottles, the recipe ingredient and blaze powder, keeps a half-finished stand resumable and reports the potion count and what it spent. `plan_brew` shows the same steps, operations and fuel without doing anything, and a missing bottle, ingredient, fuel or stand is one clear refusal.

## [0.160.0] - 2026-11-02

### Changed

- A multi-item request is now clearly one action: the companion's own tool descriptions say that mining, smelting, farming, storing, milking, picking up drops and clearing an area each cover the whole batch in one call, and a small floor points at the blueprint builder instead of one block at a time.

## [0.159.1] - 2026-11-02

### Fixed

- The companion no longer invents shared memories when it starts a conversation: its small talk is now a neutral question about your plans, preferences or work, and a fact you told it is repeated with the source named instead of being presented as something you did together.

## [0.159.0] - 2026-11-02

### Fixed

- Text the companion screens drew too close to its own background is readable again: the inventory's XP level, the `Y` in a task's coordinates and the grey "off" dot on a button now clear the WCAG contrast minimum, and a new test measures every colour pair the menu draws, including the surfaces a wash or a vanilla sprite puts behind it.

## [0.158.0] - 2026-11-02

### Added

- A goal can carry a deadline: set it in minutes with `set_goal` or `update_goal`, and the companion sees "due in N minutes" or "overdue by N minutes" before it plans. Old saved goals load unchanged with no deadline.

 - 2026-11-02

### Changed

- The subjects the companion has already raised, and what it learned about what you like talking about, now survive a server restart per owner instead of starting over.

 - 2026-11-02

### Added

- A remembered place or note now says how it was established — verified by a task, told by you, or inferred — and marks itself stale after a month, so the companion stops treating old guesses as current facts.
- A goal now carries a risk class worked out from the tools its work needs: the companion sees `risk: careful`, and `risk: destructive; ask owner first` before it plans anything that fights, clears an area or runs commands.

 - 2026-11-02

### Fixed

- A free-time shift the owner asked for no longer says the same thing twice when its plan fails: they get the specific reason once, not the reason plus a generic end report.

 - 2026-11-02

### Added

- A goal can now ask for outcomes the world can actually check beyond "have it in the bag": that the owner **received** the items, or that a named block stands at a position. Both are refused honestly when the world says no.
- Remembered places and notes now say how old they are in plain words (today, 3 days ago, over a month ago), so a stale memory reads as history instead of a promise.

 - 2026-11-02

### Changed

- Free time survives a restart: a shift keeps its purpose, budget and remaining time, and says one line when it ends — what it was for, what it got done and why it stopped. A shift whose plan did not come back ends as a work conflict instead of vanishing.
- After a failed attempt the companion no longer re-offers the remedy it already tried: the repeated-remedy check now matches the remedy wording it would actually use, so it moves to a different approach or names the blocker.

 - 2026-11-02

### Added

- The menu's activity line now shows what the running task is doing in one word (working, waiting).
- When a goal ends, the companion remembers one line about it that says whether the world confirmed it and the shortest fact a task actually verified — so an unproven "done" is remembered as unproven.

 - 2026-11-02

### Added

- The companion can now mention its shared history on its own — a recent achievement, the project you are on, a place it knows, the last thing a task verified, a remembered preference — at most three short items, only from facts it actually has, and only when they fit.

 - 2026-11-02

### Changed

- Free time now states what a shift is for, what it may spend and why it ended (owner back, owner spoke, budget spent, work conflict, done), and it refuses to start work that duplicates a queued job or an owner order.

 - 2026-11-02

### Added

- An **Interaction style** setting (Concise, Balanced, Expressive) that changes how much the companion says and narrates — never its permissions, facts, tools or safety rules. Default is Balanced.

 - 2026-11-02

### Added

- The companion menu now shows the active goal with its criteria and lets you **edit its wording** in place, so a goal set by chat can be corrected without starting over. Editing is owner-only and does not need operator rights.

 - 2026-11-02

### Fixed

- The companion can no longer report a goal as done when a criterion the world can check does not hold: such a claim is refused, the goal stays open, and the refusal names exactly what is still missing.

 - 2026-11-01

### Fixed

- The menu's **Retry** button no longer paints over **Pause** in the companion control tab; each button has its own slot again.
- The settings tab in the menu scrolls instead of drawing its lower switches over the Done button, settings switches announce which setting they belong to, and tab surfaces no longer cover their own widgets.

 - 2026-11-01

### Changed

- The settings screen now says exactly why voice will not start — a cloud voice without an API key, a local model that is not downloaded — using the same message the runtime prints instead of a generic "not configured", and it points at the model row when the chosen model cannot call tools.

## [0.146.0] - 2026-11-01

### Changed

- 46 more settings now explain themselves: each has a plain-language tooltip with its default, range and unit, whether it costs money, whether it touches the world or protection, and whether it applies when you press Save.

## [0.145.0] - 2026-11-01

### Security

- Text the companion remembers about what happened to you (a death, an advancement, a return) now goes through the same secret masking as the rest of the prompt, so a key that slipped into a name cannot reach the model through the journal.

## [0.144.0] - 2026-11-01

### Added

- A **Reset everything** button at the top of the first settings tab, next to the per-category buttons: it returns every setting of the screen to its defaults in one click, still without saving until you press Save. A single row keeps YACL's own per-option reset.

## [0.143.0] - 2026-11-01

### Changed

- The companion's face now answers the work itself: a finished job shows a short pleased expression and a failed one a displeased one, and both fade back on their own. Stopping a task on purpose changes nothing — danger and combat still win over both.

### Fixed

- CI's GameTest shard check listed eight registered test classes with no shard, so the gate failed before any test ran; every registered class is back in exactly one shard.

## [0.142.0] - 2026-11-01

### Added

- A **Reset this category** button at the top of every settings category: it puts that one category back to its defaults and leaves every other change alone. Nothing is saved until you press Save.

## [0.141.0] - 2026-11-01

### Added

- A **Retry** button in the companion menu: after a task failed or was stopped, one click queues the same job again, unchanged. It does nothing while the job is still running or queued.

## [0.140.0] - 2026-11-01

### Changed

- When the companion runs out of replans it now names the exact blocker — for example "target not reachable (can't reach 1 2 3)" — instead of reporting a generic failure, and it records no further lessons for that goal until you unblock it.

## [0.139.0] - 2026-11-01

### Changed

- When it plans new work, the companion is now told what its earlier tasks already proved (what it gathered, what changed), so a second plan stops re-doing steps it has already completed.

## [0.138.0] - 2026-11-01

### Changed

- After a task the companion now also reports how its health and hunger changed, so it notices when work cost it something and can say so or eat before the next job.

## [0.137.0] - 2026-11-01

### Added

- The companion can now drop a manner you dislike — teasing, humour, warmth, curiosity or energy — or reset the whole manner to neutral when you say how it talks is wrong. A dropped manner never drifts back on its own.

## [0.136.1] - 2026-11-01

### Fixed

- Memory compaction no longer fails when the companion is not present in the world; the merge still applies and the manner only drifts while the companion exists.

## [0.136.0] - 2026-11-01

### Changed

- Every settings category now says what it affects — `Client`, `Server` — so it is clear before you open it whether a change touches the world for everyone or only your screen.


<!-- Add entries here while you work; the version bump moves them into that version's section. -->

## [0.135.0] - 2026-11-01

### Changed

- The companion's own lines are calmer: about one every two minutes when you are free, never closer than a minute and a half, and longer pauses while it works, rests or you are away.

## [0.134.0] - 2026-11-01

### Changed

- The subjects the companion raises now follow what you answer: a subject you reply to twice comes up more often, one that drew silence less often and never twice in a row.
- Subjects about grief, money, health or contact details are never raised, in every interface language.

## [0.133.0] - 2026-11-01

### Added

- The companion's manner can drift a little with shared history: an applied memory compaction moves one of its five traits one small step, and the drift shows in how it says things, never in what it knows, what it may do or a safety rule. It is kept in the memory book and survives death and restarts.

## [0.132.0] - 2026-11-01

### Added

- An announced advancement and a return after half an hour now join the journal the companion may speak from; a quick reconnect is not treated as an absence. The advancement rides a one-line mixin because Fabric has no advancement event.

## [0.131.0] - 2026-11-01

### Added

- When a config file was damaged and the mod is running from the last good backup, the settings screen says so instead of leaving the situation invisible.

## [0.130.0] - 2026-11-01

### Added

- A **Pause / Resume** button in the companion menu's Control tab, the same switch as `/friendai pause`: the label follows the real state, so a held companion reads "Resume" even after a rejoin.

### Changed

- The Control tab lays its five buttons out by the width available instead of assuming four, so they still fit on the smallest menu.

## [0.129.0] - 2026-11-01

### Added

- `/friendai pause` holds the companion's work and `/friendai resume` lets it continue the same job from where it stopped. The hold survives a rejoin and a respawn until you release it, and a paused companion never reports work as finished.

## [0.128.0] - 2026-11-01

### Changed

- A finished task now tells the companion which plan and which goal it belonged to, so its next reply connects the result to the work it was meant to prove instead of reading as an unrelated event.

## [0.127.0] - 2026-11-01

### Added

- When nothing has happened worth a line, the companion may now open a topic of its own in the language you write in. It never repeats a topic it already raised, and a real happening always comes first.

## [0.126.0] - 2026-11-01

### Added

- The companion remembers what happened to you: a death, a respawn, a change of dimension and a return are kept in a small per-owner journal and the newest one may be said once, when you are near and free. An older memory file loads with an empty journal instead of being refused.

## [0.125.0] - 2026-11-01

### Added

- A **Feeling** tab in the companion menu shows the mood, the bond the owner has earned and its last reasons, plus the current goal with each criterion marked met or not proven. Only the owner sees it; guests and other players never receive it.

## [0.124.1] - 2026-11-01

### Changed

- The pace of the companion's own lines now follows the shipped conversation cadence: a per-situation gap with a little variation, a quiet window right after you speak, and a backoff when a line drew no reaction.

## [0.124.0] - 2026-11-01

### Added

- The settings screen has a **search** field that filters categories and options by name or synonym, and a **Basic / Advanced** switch so keys, budgets, radii and blacklists stay out of the way until you ask for them.
- Profiles can be created, duplicated, renamed and deleted from the screen, with the last profile and the one in use protected.
- Settings can be **exported** to a file with keys masked and **imported** back, refusing a file this build does not understand and never letting a masked key overwrite a real one.

## [0.123.0] - 2026-11-01

### Changed

- A finished job is now checked against the goal it was started for: criteria the world cannot show are reported back as **not proven yet**, so the companion stops calling unfinished work done.

## [0.122.0] - 2026-11-01

### Added

- The companion can speak first: when you are near, not fighting and not busy, it may say one line about something that happened — today, the death it remembers. That line is words only: no tools, no work started, no coordinates, and it is never repeated.
- **Chat → Let the companion speak first** switches it off (it then answers only), and `/friendai speak status` reports the state.

## [0.121.0] - 2026-11-01

### Added

- Gifts and rescues now count toward the bond: handing something over pays once per kind while the amount keeps growing, and killing the mob that just hurt you is remembered as a rescue. The daily cap still applies.

## [0.120.0] - 2026-11-01

### Added

- **Show my companion through walls**: a box appears around your own companion when it is more than 12 blocks away or hidden, with an unbound **Companion outline** key and a switch in a new **Display** tab. Another player's companion is never outlined.
- The companion now shows a mood that lasts: it turns worried only after a moment of real danger, stays worried a little longer, and reacts to a hit, to finished work and to what you explicitly say.
- A **bond built from deeds** — shared finished work, gifts, rescues, and the odd hit — kept in the memory book, surviving death and restarts, with `/friendai social status` to see the tier, the mood and the last reasons. The bond only changes tone; it never changes a permission.

## [0.119.0] - 2026-11-01

### Added

- The companion now drops everything it carries where it dies — bag, armour and both hands, once each — and goes back for it: an idle or following companion fetches drops within 24 blocks on its own, and with free time allowed it walks farther. A hazard (lava, fire, the void), a spot it cannot reach or a full bag is reported instead of retried forever.
- Two new owner tools: `recover_drops` sends it back for the last death's things, `dropped_items` reports what is still there. Saying "leave it" is remembered until the next death.

### Changed

- After a death the new body starts with an empty bag; hunger, experience, settings and queued work still carry over.

## [0.118.0] - 2026-11-01

### Added

- Consequential switches now explain themselves: digging and bridging on the way, hearing unaddressed chat, letting guests talk, building in free time and protecting valuable drops each carry a tooltip, as do the memory and skill limits.

### Changed

- `architecture/settings-inventory.md` records every server setting, its default, clamp, screen key and tooltip, so a new option has a checklist to fit into.

## [0.117.0] - 2026-11-01

### Added

- The settings screen now reaches the remaining server options: tree sight, blocked-queue notices and the free-time watchdog under **Tasks**; mine and place merging, prompt limits and planner limits under **World**; the path-search node budget; and the instruction-skill caps under **Companion**. Every one carries a tooltip with its default, range and unit.

## [0.116.0] - 2026-11-01

### Added

- The **Memory** tab controls how conversation lines are kept: how many, how long each may be, how many days they survive and when the companion is warned memory is nearly full, plus a switch for model-driven compaction.
- The **Free time** tab now covers working while you are offline, the offline shift rate and how quickly a paused shift resumes when you return.

## [0.115.0] - 2026-11-01

### Added

- The **Chat** tab now exposes the longest reply and the guest cooldown in seconds, and explains the hearing radius in a tooltip.
- The **Planning** tab now exposes the per-turn tool budget, how much chat history is sent, the model request timeout and the pause between replies.

## [0.114.0] - 2026-11-01

### Added

- **Trading** and **Resources** settings tabs expose villager trading radii, price and location freshness, wait limits and barter amounts, plus resource search and expedition radii and the escort distances used when the owner walks away — each with a plain-language tooltip.
- The **Companion** tab now shows chunk loading and its radius.

## [0.113.0] - 2026-11-01

### Added

- An **Autonomy** settings tab controls the companion's free time: whether it acts while you are idle or away, shift length, how many shifts it may take per hour, the cooldown after a failed shift, and whether it may touch saved places or build.

## [0.112.0] - 2026-11-01

### Added

- A **World** settings tab edits path-finding permissions and budgets, saved places and the nearby-mob guard for structures, with plain-language tooltips.

## [0.111.0] - 2026-11-01

### Added

- A **Tasks** settings tab edits follow-by-default, mining and tree search radii, task timeout, queue caps and drop collection, with plain-language tooltips.

## [0.110.0] - 2026-11-01

### Added

- A **Planning** settings tab edits tool routing, autonomy, replan limit, goal nudge interval, system-prompt budget and planner timeout, with plain-language tooltips.

## [0.109.1] - 2026-11-01

### Fixed

- Long translated labels in the companion menu no longer clip at the smallest GUI scale; the menu is now checked in all eight languages.

## [0.109.0] - 2026-11-01

### Changed

- The provider screen loads the model list automatically when it opens with a usable key, instead of waiting for **Refresh models**.

## [0.108.0] - 2026-11-01

### Changed

- The provider screen hides the custom base URL behind a collapsed **Advanced** group, so the common setup stays short.

## [0.107.1] - 2026-11-01

### Changed

- The provider model list now survives a restart, so a provider outage no longer empties the model picker after relaunching the game.

## [0.107.0] - 2026-11-01

### Added

- The provider screen now shows whether the selected service is ready, still needs an API key, still needs a model, or is a local endpoint.

## [0.106.0] - 2026-11-01

### Added

- Deepgram as a paid speech-recognition provider, with a key, a model and a language.

## [0.105.0] - 2026-11-01

### Added

- Google Cloud Speech-to-Text as a paid recognition provider, with a key and a language.

## [0.104.0] - 2026-11-01

### Added

- Google Cloud Text-to-Speech as a paid speech-synthesis provider, with a key, voice, language, speed and LINEAR16 playback.

## [0.103.1] - 2026-11-01

### Fixed

- The German and Spanish **Reset** buttons no longer clip at the smallest GUI scale.

## [0.103.0] - 2026-11-01

### Added

- `/friendai settings` prints the effective provider, model, autonomy, chunk loading, hearing radius, memory and instruction-skill settings for a dedicated server without opening the client screen.

## [0.102.0] - 2026-11-01

### Added

- The provider screen now has a **Get an API key** button that opens the selected service's key page.

## [0.101.0] - 2026-11-01

### Added

- German, French, Spanish, Brazilian Portuguese, Simplified Chinese and Ukrainian interface translations, with placeholder parity enforced for every language.
- Portuguese is now recognised by the language detector, so an owner writing Portuguese gets Portuguese replies.
- The README is now a landing page with a feature table, contents, badges and language switcher, and is translated into Russian, Ukrainian, German, French, Spanish, Brazilian Portuguese and Simplified Chinese.

## [0.100.0] - 2026-11-01

### Changed

- Provider model lists are cached for a day and keep the last successful list when a provider is offline, so opening the provider screen no longer discards models on a failed refresh.

## [0.99.0] - 2026-11-01

### Added

- The companion's body language now shows above its head: focus while it works, worry when hurt or in danger, pleasure after a finished job, displeasure after a failed one, and alarm in combat.

## [0.98.0] - 2026-11-01

### Added

- Named LLM presets for OpenRouter, DeepSeek, Groq, Mistral, xAI, Together AI, Fireworks AI, Cerebras and GitHub Models, all using the existing OpenAI-compatible protocol; `openai_compat` remains the escape hatch.

## [0.97.0] - 2026-11-01

### Added

- The operator Log tab now has request and reply entries for each model step: the system/message previews and offered tools on the way out, and the reply text, tool calls, finish reason and usage on the way back.

## [0.96.4] - 2026-11-01

### Fixed

- The planner is now told that a standing tree is one `fell_tree` step, not a `mine_blocks` count, so “collect 32 jungle logs” no longer starts a per-log mining plan.

## [0.96.3] - 2026-11-01

### Fixed

- A companion can now climb a water column taller than eight blocks: a rising stroke no longer counts as a submerged lateral swim and exhausts the breath budget.

## [0.96.2] - 2026-11-01

### Fixed

- A companion no longer breaks a block that has lava directly below it: the break is refused with the cell and a remedy, so neither the drop nor the companion falls into the lava.

## [0.96.1] - 2026-11-01

### Fixed

- A task that is alive but stops making progress now stops with “no progress for N s: …” instead of burning the whole five-minute timeout, and a short soak checks that a long runner session finishes or cancels without a duplicate terminal event.

## [0.96.0] - 2026-11-01

### Added

- The companion now understands the exact orders “follow me”, “stay”, “come here”, “stop”, “status” and “inventory” in English and Russian even when the language model is unavailable, and runs them through the same tools and access checks as before; `/friendai status` says when only these local orders work.

## [0.95.12] - 2026-11-01

### Fixed

- A guarding companion no longer starts fights with neutral mobs or the warden: endermen, piglins and zombified piglins are attacked only when provoked, a piglin group is avoided unless ordered, and the warden and dragon are treated as dangers rather than targets.

## [0.95.11] - 2026-11-01

### Added

- `/friendai diagnose` writes a bounded, redacted diagnostic bundle for the owner (or an operator for a named owner), with an optional `chat` history and never an API key.

## [0.95.10] - 2026-11-01

### Fixed

- A house asked for with a planks, slab or full-block roof now gets the matching stairs instead of failing with an unknown block state, and a roof with no stairs variant is refused before the first block with a message that names the field and the fix.

## [0.95.9] - 2026-11-01

### Fixed

- A companion that broke the block it was standing on no longer calls the thing it was sent for unreachable. Standing on the rim of the hole it has just dug, with nothing under its own feet, it now steps back onto solid ground and carries on instead of refusing every step it could take.

## [0.95.8] - 2026-11-01

### Fixed

- A companion that drops into the hole it has just dug keeps working instead of giving up: while it is falling there is nothing under its feet to stand and reach from, and it used to call the block it was sent for unreachable. It now waits until it lands and mines on from there.

## [0.95.7] - 2026-11-01

### Fixed

- A companion on a busy server looks at what is around it right now instead of standing blind: the view sweep no longer queues a fresh pass behind the one still running, so the sights it acts on stay current and a block its own digging exposes is noticed at once instead of being lost behind minutes-old checks.

## [0.95.6] - 2026-11-01

### Fixed

- A companion digging for a buried resource on a busy server now sees the faces its own digging opens. With several companions around, a dig's sighting could be dropped behind a full queue of view checks, so the search gave up with "no minecraft:stone observed or remembered nearby" while standing in the stone it had just mined.
- A companion asked for leather no longer answers that cows have none. It learns what creatures drop from sixteen samples instead of five, so one unlucky run of empty loot rolls can no longer hide a real source.

## [0.95.5] - 2026-10-31

### Fixed

- A companion that mined a few blocks down now places its crafting table or furnace on the rim of the hole it dug instead of giving up with "no free spot nearby", so it can craft what it went down for.

## [0.95.4] - 2026-10-31

### Fixed

- The companion notices the stone its own digging uncovers: restarting the view sweep from a new spot no longer throws away the block changes the dig had just queued, so a buried resource it exposes becomes known instead of being searched for again.

## [0.95.3] - 2026-10-31

### Fixed

- The companion reaches a mob or a player it was sent to instead of standing a step short and giving up: the last stretch of the walk is finished by hand when the route ends just outside the reach.
- The companion notices a block its own digging exposes: a sighting queued before it moved is re-aimed from where it stands instead of being dropped.

## [0.95.2] - 2026-10-31

### Fixed

- The companion finishes a tall build instead of giving up partway: when it has scaffolded itself above a block it still has to place, it takes its own column back down and places the block instead of failing the whole build.

## [0.95.1] - 2026-10-31

### Fixed

- A tall tree is felled completely and every log is picked up: the companion finishes its climb standing on the scaffold, not in mid-jump, so the top logs are no longer left behind, and it walks the last step onto a drop its route stopped just short of.
- A campfire that has gone out no longer counts as a cooking station: the companion checks the live block instead of the memory of it.

## [0.95.0] - 2026-10-30

### Changed

- The Tasks tab shows each task's arguments as readable chips instead of raw JSON: the block or item with its icon and name, the coordinates as X Y Z, and the count. Item ids in task results and history are shown by their game names, and tool names read as plain words.
- The Overview tab's Activity card is laid out as labelled rows (free time, next step, budget, last result, planner model) with item icons and game names instead of one block of text.
- The Toggles tab is now Settings and sits at the bottom of the menu list, below Debug. Each switch is a card with a title, a short description, the reason when it cannot be changed, and a clear On/Off switch.
- Opening the companion menu no longer pauses the companion: it keeps working while you look at its tabs.

### Fixed

- The movement overlay no longer switches itself on when the settings page opens: it is off at the start of every connection and is no longer stored in the client config.

## [0.94.0] - 2026-10-30

### Added

- The companion can now look back at itself: `read_log` shows its own recent activity (owner only), `recent_chat` returns the last messages of a conversation including its own replies, `memory_overview` lists every fact with its id and how full each memory book is, and `compress_memory` merges duplicate or superseded facts, as a dry run first and applied only when the owner agrees. Promises are never merged and no fact is dropped without a replacement.
- Conversations are kept per owner in a transcript that survives restarts and death. A trusted player can read only their own conversation and a guest none; secrets are masked when it is written and when it is read.
- The Memory tab has a "Recent chat" entry that shows the last lines and a "Clear chat" button with a confirming second click.
- `memory.transcriptMaxEntries`, `memory.transcriptMaxTextChars`, `memory.transcriptRetentionDays`, `memory.compactAtPercent` and `memory.compactionEnabled` in the server config (schema 19; an older file is upgraded with a backup).

### Changed

- `recall` can be narrowed by a text fragment and a category, and says what it searched when nothing matches instead of claiming nothing is remembered.
- The memory block of the prompt shows how full each facts book is, and a short "your own records" note tells the model when to read its log, conversations and memory.

### Fixed

- The Memory tab refreshes only when what it shows has changed, including for a player with a custom description.

## [0.93.1] - 2026-10-30

### Fixed

- A companion no longer spends its last building block on a single rise it cannot climb: when a stair or bridge needs more blocks than it carries, it gathers them first or gives the job up instead of leaving a stump in the way.
- A companion boxed in with no way out now reports that the target is unreachable instead of crashing the walk while it looks for building material.

## [0.93.0] - 2026-10-30

### Added

- Mining a valuable block whose drop would fall into lava, fire, a hole or the void now builds a catch block under it first, takes the drop, and then removes the block again. Ore next to a lava pool no longer burns and ore over a ravine no longer strands.
- `mining.protectDrops`, `mining.valuableBlocks` and `mining.catchBlocks` in the server config and a Mining category in the settings screen.

### Changed

- An ore whose drop has nowhere safe to land and no catch block can be built there is left in place with an answer that names where the drop would go and what is missing, instead of being mined and lost.
- The debug overlay marks the catch cells before the block is broken, and the mining summary says how many catch blocks were placed and removed.

### Fixed

- Stopping a mining job no longer leaves its catch blocks or route pillars standing in the world; they are taken down like a cancelled build's scaffold.

## [0.92.0] - 2026-10-30

### Added

- The companion can read its own instruction catalogue: `list_skills` shows the id, title, source, version, on/off state and a one-line description of every skill it knows, one page at a time.
- `skill_help` returns the skill-authoring checklist with the real limits, so the rules are available even on a turn where the Writing skills procedure was not selected.
- A new built-in skill, Writing skills, teaches the companion to turn a finished goal or a failed shift into one reusable procedure instead of losing the lesson.
- Nine more built-in skills now advise on planning after a failure, routes and terrain, mining and the drops, self-care, exploration, combat, storage and crafting, trust and privacy, and repairing mistakes. Trust and privacy is public, so a guest also receives it.
- Built-in skill titles follow one naming rule, so adding a catalogue entry is a data file plus two titles.

## [0.91.1] - 2026-10-30

### Fixed

- A running companion no longer skids past the planned route: it slows down before a corner and before the end of the route, so it stays on the path instead of swinging wide and doubling back.
- A companion that arrives at the edge of a gap off the line of the jump keeps lining up first, so it no longer takes off sideways and falls into the pit.

## [0.91.0] - 2026-10-30

### Added

- Pointing at an ore or a log now mines the whole connected vein (up to 32 blocks) instead of stopping at the one block. Every block is checked for tools and protection as before, and the answer says how many were mined.

### Fixed

- A pointed mine reports what it actually picked up. When the drop is still on the ground the answer gives its place and suggests picking it up, instead of claiming the block dropped nothing.
- A drop that fell into the hole the mine just dug is picked up from the rim of the hole instead of being left behind as unreachable.
- A pointed mine with a full inventory refuses before breaking the block, so the drop is not left lying on the ground.
- Answers like "no coal nearby" now say that nothing was seen or remembered within the radius and name the tools that look around, instead of reading like a fact about the whole world.

## [0.90.1] - 2026-10-30

### Fixed

- A tree, block or place search starts around the companion itself. Asking for wood no longer sends it to the tree nearest to you, and the job still starts while you are far away or offline.

## [0.90.0] - 2026-10-30

### Changed

- The companion jumps like a player: it leaps up onto a ledge one block higher across a gap of up to two blocks, and down onto lower ground across a gap of up to four blocks, a longer crossing the further it falls.
- A companion too hungry to sprint now hops a one-block gap with a walking jump instead of walking around it; wider gaps still need a running start.

### Fixed

- The companion holds its speed through a leap, so it no longer drops short of a wide gap or the edge of a ledge.

## [0.89.2] - 2026-10-30

### Fixed

- A companion carried past the middle of a block by its own momentum on a climb or a diagonal step now counts the step as done instead of turning back to the middle, so it no longer steps back a block before going on.

## [0.89.1] - 2026-10-30

### Changed

- Route planning is lighter: the path search and the terrain cache it reads no longer box every block position, so long routes cost less per tick, and the companion sorts out what you ask of it a little faster.

## [0.89.0] - 2026-10-30

### Added

- The companion spends its own survival items: it pours a water bucket to break a fall and takes the source back, drinks milk to clear a poison or a slow that costs the job, eats a golden apple or drinks a healing potion when it is critical outside a fight, drinks fire resistance in lava and water breathing under water, and throws a splash potion at a wounded owner standing next to it.

### Fixed

- Clearing an area no longer stands still on leaves the companion cannot reach: it works from the nearest cells outward, which opens up the inside of a thick canopy, and it stops with a report after three unreachable cells in a row instead of searching for a route to each of them.
- A tree felled from a log high on the trunk now loses every log down to its foot: the companion follows the trunk from what it saw instead of stopping at the part it could see.
- A log cut above the leaves is no longer lost: the companion breaks the leaf the drop rests on so it falls where it can be picked up.

## [0.88.0] - 2026-10-30

### Added

- The companion leaps a whole ravine: a running jump now clears a gap of one, two or three empty blocks instead of a single missing block.

### Fixed

- The companion runs up to a gap and leaps it instead of walking off the edge and falling in; without the food to sprint it walks around the gap or bridges it.

## [0.87.0] - 2026-10-30

### Added

- Survival building places blocks at a player's pace: `tasks.placeIntervalTicks` (default 4 ticks, range 1–40, server config schema 17) instead of one per server tick; creative mode keeps one block per tick.
- The companion holds the block it is placing and swings once per placement instead of working with an empty hand.
