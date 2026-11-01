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
| Agent | agent.plannerTimeoutSec | int | 60 | 10..300 | agent.planner_timeout_sec | yes |
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
