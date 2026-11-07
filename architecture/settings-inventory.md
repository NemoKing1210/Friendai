# Settings inventory

One row per `ServerConfig` field: the section it belongs to, the config path used by `validate()`, the type,
the shipped default, the clamp applied on load, the settings-screen key that edits it (`—` when it is file-only)
and whether the control carries a tooltip.

The screen is the only place a player edits these; a `—` row is reachable through `config/friendai-server.json`
and applied by `/friendai reload`. See `FriendaiConfigScreen` for the category each key belongs to.

| Section | Config path | Type | Default | Range | Screen key | Tooltip |
|---|---|---|---|---|---|---|
| Companion | companion.name | String | "Friendai" |  | companion.name | no |
| Companion | companion.description | String | "A friendly and resourceful Minecraft companion. Keeps answers short and helpful." |  | companion.description | no |
| Companion | companion.language | String | "auto" |  | companion.language | yes |
| Chat | chat.prefix | String | "@" |  | chat.prefix | no |
| Chat | chat.respondToName | boolean | true |  | chat.respond_to_name | no |
| Chat | chat.listenAll | boolean | false |  | chat.listen_all | yes |
| Chat | chat.maxReplyChars | int | 256 | 32..256 | chat.max_reply_chars | yes |
| Chat | chat.guestsEnabled | boolean | true |  | chat.guests_enabled | yes |
| Chat | chat.hearingRadius | int | 64 | 4..64 | chat.hearing_radius | yes |
| Chat | chat.guestCooldownMs | int | 5000 | 0..60000 | chat.guest_cooldown_seconds | yes |
| Chat | chat.ambientSpeech | boolean | true | — | chat.ambient_speech | yes |
| Brain | brain.maxToolStepsPerTurn | int | 8 | 1..32 | brain.max_tool_steps | yes |
| Brain | brain.historyMessages | int | 30 | 6..200 | brain.history_messages | yes |
| Brain | brain.requestTimeoutSec | int | 60 | 5..600 | brain.request_timeout_sec | yes |
| Brain | brain.cooldownMs | int | 500 | 0..60000 | brain.cooldown_ms | yes |
| Tasks | tasks.defaultFollow | boolean | true |  | tasks.default_follow | no |
| Tasks | tasks.mineSearchRadius | int | 32 | 4..64 | tasks.mine_search_radius | yes |
| Tasks | tasks.taskTimeoutSec | int | 300 | 10..3600 | tasks.timeout_sec | yes |
| Tasks | tasks.placeIntervalTicks | int | 4 | 1..40 | tasks.place_interval | yes |
| Tasks | tasks.treeSearchRadius | int | 32 | 4..64 | tasks.tree_search_radius | yes |
| Tasks | tasks.fellCollectDrops | boolean | true |  | tasks.fell_collect | no |
| Tasks | tasks.mineCollectDrops | boolean | true |  | tasks.mine_collect | no |
| Tasks | tasks.treePerception | boolean | true |  | tasks.tree_perception | no |
| Tasks | tasks.treePerceptionRadius | int | 12 | 4..24 | tasks.tree_perception_radius | yes |
| Tasks | tasks.queueMaxJobs | int | 64 | 1..128 | tasks.queue_max_jobs | yes |
| Tasks | tasks.queueMaxPlans | int | 8 | 1..16 | tasks.queue_max_plans | yes |
| Tasks | tasks.queueBlockedNoticeSec | int | 30 | 5..600 | tasks.queue_blocked_notice_sec | yes |
| Tasks | tasks.queueMaxStepsPerAdd | int | 32 | 1..64 | tasks.queue_max_steps | yes |
| Tasks | tasks.freeTimeWatchdogSec | int | 120 | 1..600 | tasks.free_time_watchdog_sec | yes |
| Trading | trading.enabled | boolean | true |  | trading.enabled | no |
| Trading | trading.idleRefresh | boolean | true |  | trading.idle_refresh | no |
| Trading | trading.observationRadius | int | 64 |  | trading.radius | yes |
| Trading | trading.quoteFreshTicks | int | 1_200 |  | trading.quote_fresh_seconds | yes |
| Trading | trading.locationFreshTicks | int | 12_000 |  | trading.location_fresh_minutes | yes |
| Trading | trading.idleRefreshIntervalTicks | int | 200 |  | trading.idle_refresh_seconds | yes |
| Trading | trading.waitTicks | int | 2_400 |  | trading.wait_seconds | yes |
| Trading | trading.maxWaitTicks | int | 12_000 |  | trading.max_wait_seconds | yes |
| Trading | trading.maxBarterIngots | int | 64 |  | trading.max_ingots | yes |
| Trading | trading.barterDefaultIngots | int | 8 |  | trading.default_ingots | yes |
| Resource | resource.allowExpeditions | boolean | true |  | resources.expeditions | no |
| Resource | resource.localRadius | int | 64 |  | resources.local_radius | yes |
| Resource | resource.localTicks | int | 2_400 |  | resources.local_seconds | yes |
| Resource | resource.expeditionRadius | int | 512 |  | resources.expedition_radius | yes |
| Resource | resource.expeditionTicks | int | 12_000 |  | resources.expedition_minutes | yes |
| Resource | resource.observationRadius | int | 64 |  | resources.observation_radius | yes |
| Resource | resource.waitForOwner | boolean | true |  | resources.wait_for_owner | no |
| Resource | resource.ownerSightRadius | int | 32 |  | resources.owner_sight | yes |
| Resource | resource.ownerHoldDistance | int | 48 |  | resources.owner_hold | yes |
| Resource | resource.ownerResumeDistance | int | 24 |  | resources.owner_resume | yes |
| Memory | memory.enabled | boolean | true |  | memory.enabled | no |
| Memory | memory.maxFactChars | int | 512 | 40..512 | memory.max_fact_chars | yes |
| Memory | memory.maxFactsPerPlayer | int | 128 | 1..128 | memory.max_facts_per_player | yes |
| Memory | memory.maxWorldFacts | int | 128 | 1..200 | memory.max_world_facts | yes |
| Memory | memory.transcriptMaxEntries | int | 200 | 20..2000 | memory.transcript_entries | yes |
| Memory | memory.transcriptMaxTextChars | int | 400 | 100..2000 | memory.transcript_chars | yes |
| Memory | memory.transcriptRetentionDays | int | 7 | 1..90 | memory.transcript_retention_days | yes |
| Memory | memory.compactAtPercent | int | 90 | 50..100 | memory.compact_at_percent | yes |
| Memory | memory.compactionEnabled | boolean | true |  | memory.compaction_enabled | no |
| Mining | mining.protectDrops | boolean | true |  | mining.protect_drops | yes |
| Mining | mining.catchBlocks | int | MiningRuntimeSettings.DEFAULT_CATCH_BLOCKS | 0..27 | mining.catch_blocks | yes |
| Pathing | pathing.allowBreak | boolean | true |  | pathing.allow_break | yes |
| Pathing | pathing.allowPlace | boolean | true |  | pathing.allow_place | yes |
| Pathing | pathing.searchBudgetMs | int | 2 | 1..10 | pathing.search_budget_ms | yes |
| Pathing | pathing.maxNodes | int | 20_000 | 1000..100000 | pathing.max_nodes | yes |
| Pathing | pathing.maxGatherBlocks | int | 64 | 0..256 | pathing.max_gather | yes |
| Places | places.enabled | boolean | true |  | places.enabled | no |
| Places | places.autoRemember | boolean | true |  | places.auto_remember | no |
| Places | places.autoMineMinBlocks | int | 16 | 1..256 | places.auto_mine_min_blocks | yes |
| Places | places.maxPlaces | int | 128 | 8..1024 | places.max | yes |
| Places | places.mergeRadius | int | 16 | 1..64 | places.merge_radius | yes |
| Places | places.promptLimit | int | 6 | 1..12 | places.prompt_limit | yes |
| Places | places.promptBudget | int | 1_200 | 200..4000 | places.prompt_budget | yes |
| Places | places.plannerLimit | int | 40 | 1..128 | places.planner_limit | yes |
| Structures | structures.maxNearbySpawned | int | 4 | 1..16 | structures.max_nearby | yes |
| Agent | agent.toolRouting | boolean | true |  | agent.tool_routing | no |
| Agent | agent.autonomy | String | "assistive" |  | autonomy | yes |
| Agent | agent.maxReplans | int | 3 | 0..10 | agent.max_replans | yes |
| Agent | agent.goalTickSec | int | 30 | 10..600 | agent.goal_tick_sec | yes |
| Agent | agent.promptBudget | int | 24_000 | 2000..60000 | agent.prompt_budget | yes |
| Agent | agent.planner | String | null |  | agent.planner | yes |
| Agent | agent.plannerRetries | int | 2 | 1..4 | agent.planner_retries | yes |
| Agent | agent.plannerTimeoutSec | int | 20 | 5..120 | agent.planner_timeout_sec | yes |
| Autonomy | autonomy.enabled | boolean | true |  | autonomy.enabled | no |
| Autonomy | autonomy.idleStart | boolean | true |  | autonomy.idle_start | no |
| Autonomy | autonomy.idleMinutes | int | 5 | 1..120 | autonomy.idle_minutes | yes |
| Autonomy | autonomy.resumeAfterSec | int | 60 | 10..900 | autonomy.resume_after | yes |
| Autonomy | autonomy.whileOffline | boolean | true |  | autonomy.while_offline | no |
| Autonomy | autonomy.shiftMinutes | int | 20 | 3..120 | autonomy.shift_minutes | yes |
| Autonomy | autonomy.maxShiftsPerHour | int | 6 | 1..60 | autonomy.max_shifts | yes |
| Autonomy | autonomy.offlineShiftsPerHour | int | 2 | 0..60 | autonomy.offline_shifts | yes |
| Autonomy | autonomy.failCooldownMin | int | 10 | 1..240 | autonomy.fail_cooldown | yes |
| Autonomy | autonomy.protectPlaces | boolean | true |  | autonomy.protect_places | no |
| Autonomy | autonomy.allowBuilding | boolean | true |  | autonomy.allow_building | yes |
| Skills | skills.enabled | boolean | true |  | skills.enabled | no |
| Skills | skills.promptBudget | int | 4096 | 200..6000 | skills.budget | yes |
| Skills | skills.requireApproval | boolean | true |  | skills.approval | no |
| Skills | skills.modelWritesPerHour | int | 6 | 0..60 | skills.hourly | yes |
| Skills | skills.writesPerOutcome | int | 2 | 0..2 | skills.per_outcome | yes |
| Skills | skills.ownerCount | int | 64 | 1..64 | skills.owner_count | yes |
| Skills | skills.serverCount | int | 32 | 1..32 | skills.server_count | yes |
| Skills | skills.totalChars | int | 96_000 | 4000..96000 | skills.total_chars | yes |
| Companion | companion.chunkLoading.enabled | boolean | true |  | companion.chunk_loading | no |
| Companion | companion.chunkLoading.radius | int | 1 | 0..4 | companion.chunk_radius | yes |

## Default review (TODO 67.2, 2026-11-04)

The table above is the server field inventory. This section records the owner, category and keep/change decision for
the shipped defaults, including the client, provider-profile and mirrored runtime defaults that the older table did
not cover. Evidence is the deterministic baseline in `engine/build/eval-baseline.md` unless a row names a test or a
compatibility invariant. "Keep" means no evidence justified a change; it is not an unexamined default.

| Owner | Key(s) | Current | Category | Decision | Evidence / invariant |
|---|---|---|---|---|---|
| `ServerConfig` | `agent.promptBudget` | 24 000 chars | token-sensitive | keep | Baseline system prompts are 8.6k–34.2k chars; an eight-step objective reaches 97.8k across eight requests. The prompt budget is a ceiling, not a target; no fixture was truncated, and lowering it risks dropping tool schemas. |
| `ServerConfig` | `brain.historyMessages` | 30 | token-sensitive | keep | Baseline history is 1–64 messages; the longest fixture is the deliberate eight-step objective. Trimming is already message- and turn-aware (rule 4); a lower cap would shorten legitimate multi-call work. |
| `ServerConfig` | `brain.maxToolStepsPerTurn` | 8 | token/loop | keep | The eight-step fixture completes in 8 requests; this is a turn loop bound, not a prompt cost. |
| `ServerConfig` | `chat.maxReplyChars` | 256 | presentation | keep | At the clamp ceiling; lowering truncates player-facing replies and needs a product decision, not a spend target. |
| `ServerConfig` | `places.promptBudget` | 1 200 | token-sensitive | keep | Places block is bounded separately and the baseline does not show it crowding the system prompt. |
| `ServerConfig` | `skills.promptBudget` | 4 096 | token-sensitive | keep | The owner's runtime file uses 2 400; the code default is the safer upper bound for owner/model skills. A change needs a skill-selection starvation fixture. |
| `ServerConfig` | `memory.maxFactChars` / `maxFactsPerPlayer` / `maxWorldFacts` | 512 / 128 / 128 | learning/retention | keep | At or near their clamps; lowering loses learned facts. |
| `ServerConfig` | `memory.compactAtPercent` / transcript caps | 90 / 200 / 400 / 7 days | learning/retention | keep | Compaction and retention are privacy/learning decisions, not token tuning. |
| `ServerConfig` | `autonomy.*` | 20 min / 6 per hour / 2 offline | spend/autonomy | keep | Baseline `hour_passive` makes 0 requests; `hour_assistive` and `hour_autonomous` each make one scripted request in a simulated hour. Lowering caps without provider usage would suppress useful work. |
| `ServerConfig` | `memory.retrieval` / `topK` / `maxChars` | off / 5 / 600 | token-sensitive, opt-in | keep (off) | New opt-in feature; default off preserves the existing prompt and spend. The lexical backend is measured separately. |
| `ServerConfig` | `profile.provider/model/apiKey/baseUrl/temperature/maxTokens` | anthropic / claude-sonnet-5-5 / env / null / 0.7 / 8192 | provider-dependent | keep | No offline tokenizer or same-model usage; lowering output or context can truncate tool JSON and multilingual replies. Provider/key changes are not defaults tuning. |
| `ClientConfig` | `voice.stt.local.model` | `base` | client/UX | keep | Whisper base is the documented balance; the catalog lets the owner choose a larger model without a default migration. |
| `ClientConfig` | `voice.stt.maxRecordSec` / `voice.tts.maxChars` / `volume` / `spatial` | 30 / 300 / 1.0 / on | presentation | keep | Bounded by existing clamps; no token effect. |
| `ClientConfig` | `voice.streamReplies` | true | presentation | keep | Streaming reduces first-sentence latency; the guard still buffers unsafe prefixes. |
| `ClientConfig` | `voice.wake.enabled` / phrase / preRollSec | false / bundle default / 2 | privacy | keep | Wake word is opt-in by design and never opens the microphone unasked. |
| `ClientConfig` | `voice.stt.provider/language/inputDevice`, `voice.tts.*` | local/auto/system/… | client/UX | keep | Environment-dependent; no safe universal default change. |
| `BrainSettings` | `maxToolStepsPerTurn`, `historyMessages`, timeouts, cooldown | mirrors of `brain.*` | mirror | keep | `AgentSettings.DEFAULTS` and `BrainSettings` are tested against the validated config defaults; changing one without the other is a bug, not tuning. |
| `TaskRuntimeSettings` / `QueueLimits` / `MemoryLimits` / `TranscriptLimits` | bounded defaults | see owning classes | safety/persistence | keep | These caps enforce bounded work, queue and file size; they are not token knobs. |
| `ProviderRegistry` / profiles | provider presets, endpoints, models | data | provider-dependent | keep | Routing and key handling are outside default tuning; a preset cannot rewrite provider/model/key. |

### Mirror consistency

Existing consistency checks cover the server autonomy section. Add/keep tests for `AgentSettings.DEFAULTS`,
`TaskRuntimeSettings.DEFAULTS`, `QueueLimits.DEFAULTS`, `MemoryLimits.DEFAULTS`, `TranscriptLimits.DEFAULTS` and the
client wake/recognition defaults so a mirrored field cannot drift from its validated owner.

### Decision

The baseline shows the current defaults already keep capability and cost within the measured fixtures; none of the
reviewed keys justified a change. No "preset" therefore ships: a preset that applies the same values would be a no-op
and would suggest a tuning that the evidence does not support. The opt-in preset path remains an explicit future
decision if a measured fixture shows a shortfall (67.4/67.5). Existing explicit owner settings always win.
