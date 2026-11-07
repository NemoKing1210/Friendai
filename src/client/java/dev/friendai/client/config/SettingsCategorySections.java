package dev.friendai.client.config;

import static dev.friendai.client.config.SettingsWidgets.booleanOption;
import static dev.friendai.client.config.SettingsWidgets.scoped;
import static dev.friendai.client.config.SettingsWidgets.stringOption;
import static dev.friendai.client.config.SettingsWidgets.tr;

import dev.friendai.features.mining.MiningRuntimeSettings;
import dev.friendai.infrastructure.config.ClientConfig;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * The server-owned settings tabs and the client-side social tab: one builder per category, each a pure
 * composition of options over the config object the screen passes in.
 */
final class SettingsCategorySections {
    private SettingsCategorySections() {}

    static ConfigCategory companionCategory(ServerConfig server) {
        var c = server.companion;
        List<String> plannerProfiles = new ArrayList<>();
        plannerProfiles.add("");
        plannerProfiles.addAll(server.profiles.keySet());
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.companion"), "friendai.config.scope.server"))
                .option(stringOption(
                        "friendai.config.companion.name",
                        "friendai.config.companion.name.tooltip",
                        () -> c.name,
                        "Friendai",
                        v -> c.name = v.isBlank() ? "Friendai" : v.strip()))
                .option(stringOption(
                        "friendai.config.companion.description",
                        "friendai.config.companion.description.tooltip",
                        () -> c.description,
                        new ServerConfig.CompanionSection().description,
                        v -> c.description = v))
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.companion.language"))
                        .description(OptionDescription.of(tr("friendai.config.companion.language.tooltip")))
                        .binding("auto", () -> c.language, v -> c.language = v.isBlank() ? "auto" : v.strip())
                        .controller(StringControllerBuilder::create)
                        .build())
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.companion.interaction_style"))
                        .description(OptionDescription.of(tr("friendai.config.companion.interaction_style.tooltip")))
                        .binding(
                                dev.friendai.features.social.InteractionStyle.BALANCED.configValue(),
                                () -> c.interactionStyle,
                                v -> c.interactionStyle = dev.friendai.features.social.InteractionStyle.parse(v)
                                        .configValue())
                        .controller(StringControllerBuilder::create)
                        .build())
                .option(booleanOption(
                        "friendai.config.skills.enabled",
                        "friendai.config.skills.enabled.tooltip",
                        true,
                        () -> server.skills.enabled,
                        v -> server.skills.enabled = v))
                .option(booleanOption(
                        "friendai.config.skills.approval",
                        "friendai.config.skills.approval.tooltip",
                        true,
                        () -> server.skills.requireApproval,
                        v -> server.skills.requireApproval = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.budget"))
                        .description(OptionDescription.of(tr("friendai.config.skills.budget.tooltip")))
                        .binding(4096, () -> server.skills.promptBudget, v -> server.skills.promptBudget = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(200, 6000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.hourly"))
                        .description(OptionDescription.of(tr("friendai.config.skills.hourly.tooltip")))
                        .binding(6, () -> server.skills.modelWritesPerHour, v -> server.skills.modelWritesPerHour = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(0, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.per_outcome"))
                        .description(OptionDescription.of(tr("friendai.config.skills.per_outcome.tooltip")))
                        .binding(2, () -> server.skills.writesPerOutcome, v -> server.skills.writesPerOutcome = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(0, 2))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.owner_count"))
                        .description(OptionDescription.of(tr("friendai.config.skills.owner_count.tooltip")))
                        .binding(64, () -> server.skills.ownerCount, v -> server.skills.ownerCount = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(1, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.server_count"))
                        .description(OptionDescription.of(tr("friendai.config.skills.server_count.tooltip")))
                        .binding(32, () -> server.skills.serverCount, v -> server.skills.serverCount = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(1, 32))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.skills.total_chars"))
                        .description(OptionDescription.of(tr("friendai.config.skills.total_chars.tooltip")))
                        .binding(96_000, () -> server.skills.totalChars, v -> server.skills.totalChars = v)
                        .controller(
                                opt -> IntegerFieldControllerBuilder.create(opt).range(4_000, 96_000))
                        .build())
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.agent.planner"))
                        .description(OptionDescription.of(tr("friendai.config.agent.planner.tooltip")))
                        .binding(
                                "",
                                () -> server.agent.planner == null ? "" : server.agent.planner,
                                value -> server.agent.planner = value == null || value.isBlank() ? null : value.strip())
                        .controller(option -> DropdownStringControllerBuilder.create(option)
                                .values(plannerProfiles)
                                .allowAnyValue(false)
                                .allowEmptyValue(true))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.planner_retries"))
                        .description(OptionDescription.of(tr("friendai.config.agent.planner_retries.tooltip")))
                        .binding(2, () -> server.agent.plannerRetries, value -> server.agent.plannerRetries = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 4))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.place_interval"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.place_interval.tooltip")))
                        .binding(
                                4,
                                () -> server.tasks.placeIntervalTicks,
                                value -> server.tasks.placeIntervalTicks = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 40))
                        .build())
                .option(booleanOption(
                        "friendai.config.companion.chunk_loading",
                        "friendai.config.companion.chunk_loading.tooltip",
                        true,
                        () -> c.chunkLoading.enabled,
                        value -> c.chunkLoading.enabled = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.companion.chunk_radius"))
                        .description(OptionDescription.of(tr("friendai.config.companion.chunk_radius.tooltip")))
                        .binding(1, () -> c.chunkLoading.radius, value -> c.chunkLoading.radius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 4))
                        .build())
                .build();
    }

    static ConfigCategory chatCategory(ServerConfig server) {
        var c = server.chat;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.chat"), "friendai.config.scope.server"))
                .option(stringOption(
                        "friendai.config.chat.prefix",
                        "friendai.config.chat.prefix.tooltip",
                        () -> c.prefix,
                        "@",
                        v -> c.prefix = v))
                .option(booleanOption(
                        "friendai.config.chat.respond_to_name",
                        "friendai.config.chat.respond_to_name.tooltip",
                        true,
                        () -> c.respondToName,
                        v -> c.respondToName = v))
                .option(booleanOption(
                        "friendai.config.chat.listen_all",
                        "friendai.config.chat.listen_all.tooltip",
                        false,
                        () -> c.listenAll,
                        v -> c.listenAll = v))
                .option(booleanOption(
                        "friendai.config.chat.guests_enabled",
                        "friendai.config.chat.guests_enabled.tooltip",
                        true,
                        () -> c.guestsEnabled,
                        v -> c.guestsEnabled = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.hearing_radius"))
                        .description(OptionDescription.of(tr("friendai.config.chat.hearing_radius.tooltip")))
                        .binding(64, () -> c.hearingRadius, v -> c.hearingRadius = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(4, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.max_reply_chars"))
                        .description(OptionDescription.of(tr("friendai.config.chat.max_reply_chars.tooltip")))
                        .binding(256, () -> c.maxReplyChars, v -> c.maxReplyChars = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(32, 256))
                        .build())
                .option(booleanOption(
                        "friendai.config.chat.ambient_speech",
                        "friendai.config.chat.ambient_speech.tooltip",
                        true,
                        () -> c.ambientSpeech,
                        v -> c.ambientSpeech = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.chat.guest_cooldown_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.chat.guest_cooldown_seconds.tooltip")))
                        .binding(5, () -> c.guestCooldownMs / 1_000, v -> c.guestCooldownMs = v * 1_000)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(0, 60))
                        .build())
                .build();
    }

    static ConfigCategory planningCategory(ServerConfig server) {
        var agent = server.agent;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.planning"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.agent.tool_routing",
                        "friendai.config.agent.tool_routing.tooltip",
                        true,
                        () -> agent.toolRouting,
                        value -> agent.toolRouting = value))
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.autonomy"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.tooltip")))
                        .binding("assistive", () -> agent.autonomy, value -> agent.autonomy = value)
                        .controller(option -> DropdownStringControllerBuilder.create(option)
                                .values(List.of("passive", "assistive", "autonomous"))
                                .allowAnyValue(false))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.max_replans"))
                        .description(OptionDescription.of(tr("friendai.config.agent.max_replans.tooltip")))
                        .binding(3, () -> agent.maxReplans, value -> agent.maxReplans = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 10))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.goal_tick_sec"))
                        .description(OptionDescription.of(tr("friendai.config.agent.goal_tick_sec.tooltip")))
                        .binding(30, () -> agent.goalTickSec, value -> agent.goalTickSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.prompt_budget"))
                        .description(OptionDescription.of(tr("friendai.config.agent.prompt_budget.tooltip")))
                        .binding(24_000, () -> agent.promptBudget, value -> agent.promptBudget = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 100_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.agent.planner_timeout_sec"))
                        .description(OptionDescription.of(tr("friendai.config.agent.planner_timeout_sec.tooltip")))
                        .binding(20, () -> agent.plannerTimeoutSec, value -> agent.plannerTimeoutSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(5, 120))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.max_tool_steps"))
                        .description(OptionDescription.of(tr("friendai.config.brain.max_tool_steps.tooltip")))
                        .binding(
                                8,
                                () -> server.brain.maxToolStepsPerTurn,
                                value -> server.brain.maxToolStepsPerTurn = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 32))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.history_messages"))
                        .description(OptionDescription.of(tr("friendai.config.brain.history_messages.tooltip")))
                        .binding(30, () -> server.brain.historyMessages, value -> server.brain.historyMessages = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(6, 200))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.request_timeout_sec"))
                        .description(OptionDescription.of(tr("friendai.config.brain.request_timeout_sec.tooltip")))
                        .binding(
                                60,
                                () -> server.brain.requestTimeoutSec,
                                value -> server.brain.requestTimeoutSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(5, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.brain.cooldown_ms"))
                        .description(OptionDescription.of(tr("friendai.config.brain.cooldown_ms.tooltip")))
                        .binding(500, () -> server.brain.cooldownMs, value -> server.brain.cooldownMs = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 60_000))
                        .build())
                .build();
    }

    static ConfigCategory tasksCategory(ServerConfig server) {
        var tasks = server.tasks;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.tasks"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.tasks.default_follow",
                        "friendai.config.tasks.default_follow.tooltip",
                        true,
                        () -> tasks.defaultFollow,
                        value -> tasks.defaultFollow = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.mine_search_radius"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.mine_search_radius.tooltip")))
                        .binding(32, () -> tasks.mineSearchRadius, value -> tasks.mineSearchRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.tree_search_radius"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.tree_search_radius.tooltip")))
                        .binding(32, () -> tasks.treeSearchRadius, value -> tasks.treeSearchRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.timeout_sec"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.timeout_sec.tooltip")))
                        .binding(300, () -> tasks.taskTimeoutSec, value -> tasks.taskTimeoutSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 3600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_max_jobs"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_max_jobs.tooltip")))
                        .binding(64, () -> tasks.queueMaxJobs, value -> tasks.queueMaxJobs = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 256))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_max_plans"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_max_plans.tooltip")))
                        .binding(8, () -> tasks.queueMaxPlans, value -> tasks.queueMaxPlans = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 32))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_max_steps"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_max_steps.tooltip")))
                        .binding(32, () -> tasks.queueMaxStepsPerAdd, value -> tasks.queueMaxStepsPerAdd = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(booleanOption(
                        "friendai.config.tasks.fell_collect",
                        "friendai.config.tasks.fell_collect.tooltip",
                        true,
                        () -> tasks.fellCollectDrops,
                        value -> tasks.fellCollectDrops = value))
                .option(booleanOption(
                        "friendai.config.tasks.mine_collect",
                        "friendai.config.tasks.mine_collect.tooltip",
                        true,
                        () -> tasks.mineCollectDrops,
                        value -> tasks.mineCollectDrops = value))
                .option(booleanOption(
                        "friendai.config.tasks.tree_perception",
                        "friendai.config.tasks.tree_perception.tooltip",
                        true,
                        () -> tasks.treePerception,
                        value -> tasks.treePerception = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.tree_perception_radius"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.tree_perception_radius.tooltip")))
                        .binding(12, () -> tasks.treePerceptionRadius, value -> tasks.treePerceptionRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(4, 24))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.queue_blocked_notice_sec"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.queue_blocked_notice_sec.tooltip")))
                        .binding(30, () -> tasks.queueBlockedNoticeSec, value -> tasks.queueBlockedNoticeSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(5, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.tasks.free_time_watchdog_sec"))
                        .description(OptionDescription.of(tr("friendai.config.tasks.free_time_watchdog_sec.tooltip")))
                        .binding(120, () -> tasks.freeTimeWatchdogSec, value -> tasks.freeTimeWatchdogSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .build();
    }

    static ConfigCategory logsCategory(ServerConfig server) {
        var logs = server.logging;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.logs"), "friendai.config.scope.server"))
                .option(ButtonOption.createBuilder()
                        .name(tr("friendai.config.logs.open_folder"))
                        .action((screen, button) ->
                                dev.friendai.client.ui.LogFolder.open(dev.friendai.client.ui.LogFolder.currentRoot()))
                        .build())
                .option(ButtonOption.createBuilder()
                        .name(tr("friendai.config.logs.copy_path"))
                        .action((screen, button) ->
                                dev.friendai.client.ui.LogFolder.copy(dev.friendai.client.ui.LogFolder.currentRoot()))
                        .build())
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.logs.level"))
                        .description(OptionDescription.of(tr("friendai.config.logs.level.tooltip")))
                        .binding("normal", () -> logs.level, value -> logs.level = value)
                        .controller(option -> DropdownStringControllerBuilder.create(option)
                                .values(List.of("off", "normal", "detailed", "trace"))
                                .allowAnyValue(false))
                        .build())
                .option(booleanOption(
                        "friendai.config.logs.category.brain",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.brain,
                        value -> logs.brain = value))
                .option(booleanOption(
                        "friendai.config.logs.category.tool",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.tool,
                        value -> logs.tool = value))
                .option(booleanOption(
                        "friendai.config.logs.category.task",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.task,
                        value -> logs.task = value))
                .option(booleanOption(
                        "friendai.config.logs.category.queue",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.queue,
                        value -> logs.queue = value))
                .option(booleanOption(
                        "friendai.config.logs.category.net",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.net,
                        value -> logs.net = value))
                .option(booleanOption(
                        "friendai.config.logs.category.speech",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.speech,
                        value -> logs.speech = value))
                .option(booleanOption(
                        "friendai.config.logs.category.world",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.world,
                        value -> logs.world = value))
                .option(booleanOption(
                        "friendai.config.logs.category.config",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.config,
                        value -> logs.config = value))
                .option(booleanOption(
                        "friendai.config.logs.category.client",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.client,
                        value -> logs.client = value))
                .option(booleanOption(
                        "friendai.config.logs.category.server",
                        "friendai.config.logs.category.tooltip",
                        true,
                        () -> logs.server,
                        value -> logs.server = value))
                .option(Option.<Long>createBuilder()
                        .name(tr("friendai.config.logs.max_file_bytes"))
                        .description(OptionDescription.of(tr("friendai.config.logs.limits.tooltip")))
                        .binding(8_388_608L, () -> logs.maxFileBytes, value -> logs.maxFileBytes = value)
                        .controller(
                                option -> dev.isxander.yacl3.api.controller.LongFieldControllerBuilder.create(option)
                                        .range(1_048_576L, 268_435_456L))
                        .build())
                .option(Option.<Long>createBuilder()
                        .name(tr("friendai.config.logs.max_total_bytes"))
                        .description(OptionDescription.of(tr("friendai.config.logs.limits.tooltip")))
                        .binding(52_428_800L, () -> logs.maxTotalBytes, value -> logs.maxTotalBytes = value)
                        .controller(
                                option -> dev.isxander.yacl3.api.controller.LongFieldControllerBuilder.create(option)
                                        .range(1_048_576L, 2_147_483_648L))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.logs.retention_days"))
                        .description(OptionDescription.of(tr("friendai.config.logs.limits.tooltip")))
                        .binding(7, () -> logs.retentionDays, value -> logs.retentionDays = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 90))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.logs.max_sessions"))
                        .description(OptionDescription.of(tr("friendai.config.logs.limits.tooltip")))
                        .binding(20, () -> logs.maxSessionsPerOwner, value -> logs.maxSessionsPerOwner = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 200))
                        .build())
                .option(booleanOption(
                        "friendai.config.logs.digest",
                        "friendai.config.logs.digest.tooltip",
                        true,
                        () -> logs.digest,
                        value -> logs.digest = value))
                .build();
    }

    static ConfigCategory worldCategory(ServerConfig server) {
        var pathing = server.pathing;
        var places = server.places;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.world"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.pathing.allow_break",
                        "friendai.config.pathing.allow_break.tooltip",
                        true,
                        () -> pathing.allowBreak,
                        value -> pathing.allowBreak = value))
                .option(booleanOption(
                        "friendai.config.pathing.allow_place",
                        "friendai.config.pathing.allow_place.tooltip",
                        true,
                        () -> pathing.allowPlace,
                        value -> pathing.allowPlace = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.pathing.search_budget_ms"))
                        .description(OptionDescription.of(tr("friendai.config.pathing.search_budget_ms.tooltip")))
                        .binding(2, () -> pathing.searchBudgetMs, value -> pathing.searchBudgetMs = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 50))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.pathing.max_gather"))
                        .description(OptionDescription.of(tr("friendai.config.pathing.max_gather.tooltip")))
                        .binding(64, () -> pathing.maxGatherBlocks, value -> pathing.maxGatherBlocks = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 256))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.pathing.max_nodes"))
                        .description(OptionDescription.of(tr("friendai.config.pathing.max_nodes.tooltip")))
                        .binding(20_000, () -> pathing.maxNodes, value -> pathing.maxNodes = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1_000, 100_000))
                        .build())
                .option(booleanOption(
                        "friendai.config.places.enabled",
                        "friendai.config.places.enabled.tooltip",
                        true,
                        () -> places.enabled,
                        value -> places.enabled = value))
                .option(booleanOption(
                        "friendai.config.places.auto_remember",
                        "friendai.config.places.auto_remember.tooltip",
                        true,
                        () -> places.autoRemember,
                        value -> places.autoRemember = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.max"))
                        .description(OptionDescription.of(tr("friendai.config.places.max.tooltip")))
                        .binding(128, () -> places.maxPlaces, value -> places.maxPlaces = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.auto_mine_min_blocks"))
                        .description(OptionDescription.of(tr("friendai.config.places.auto_mine_min_blocks.tooltip")))
                        .binding(16, () -> places.autoMineMinBlocks, value -> places.autoMineMinBlocks = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 256))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.merge_radius"))
                        .description(OptionDescription.of(tr("friendai.config.places.merge_radius.tooltip")))
                        .binding(16, () -> places.mergeRadius, value -> places.mergeRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.prompt_limit"))
                        .description(OptionDescription.of(tr("friendai.config.places.prompt_limit.tooltip")))
                        .binding(6, () -> places.promptLimit, value -> places.promptLimit = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 12))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.prompt_budget"))
                        .description(OptionDescription.of(tr("friendai.config.places.prompt_budget.tooltip")))
                        .binding(1_200, () -> places.promptBudget, value -> places.promptBudget = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(200, 4_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.places.planner_limit"))
                        .description(OptionDescription.of(tr("friendai.config.places.planner_limit.tooltip")))
                        .binding(40, () -> places.plannerLimit, value -> places.plannerLimit = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.structures.max_nearby"))
                        .description(OptionDescription.of(tr("friendai.config.structures.max_nearby.tooltip")))
                        .binding(
                                4,
                                () -> server.structures.maxNearbySpawned,
                                value -> server.structures.maxNearbySpawned = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 32))
                        .build())
                .build();
    }

    static ConfigCategory tradingCategory(ServerConfig server) {
        var trading = server.tasks.trading;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.trading"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.trading.enabled",
                        "friendai.config.trading.enabled.tooltip",
                        true,
                        () -> trading.enabled,
                        value -> trading.enabled = value))
                .option(booleanOption(
                        "friendai.config.trading.idle_refresh",
                        "friendai.config.trading.idle_refresh.tooltip",
                        true,
                        () -> trading.idleRefresh,
                        value -> trading.idleRefresh = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.radius"))
                        .description(OptionDescription.of(tr("friendai.config.trading.radius.tooltip")))
                        .binding(64, () -> trading.observationRadius, value -> trading.observationRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(8, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.quote_fresh_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.quote_fresh_seconds.tooltip")))
                        .binding(
                                60,
                                () -> trading.quoteFreshTicks / 20,
                                value -> trading.quoteFreshTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 3600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.location_fresh_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.trading.location_fresh_minutes.tooltip")))
                        .binding(
                                10,
                                () -> trading.locationFreshTicks / 1_200,
                                value -> trading.locationFreshTicks = Math.max(1_200, value * 1_200))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.idle_refresh_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.idle_refresh_seconds.tooltip")))
                        .binding(
                                10,
                                () -> trading.idleRefreshIntervalTicks / 20,
                                value -> trading.idleRefreshIntervalTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.wait_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.wait_seconds.tooltip")))
                        .binding(
                                120,
                                () -> trading.waitTicks / 20,
                                value -> trading.waitTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.max_wait_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.trading.max_wait_seconds.tooltip")))
                        .binding(
                                600,
                                () -> trading.maxWaitTicks / 20,
                                value -> trading.maxWaitTicks = Math.max(20, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.max_ingots"))
                        .description(OptionDescription.of(tr("friendai.config.trading.max_ingots.tooltip")))
                        .binding(64, () -> trading.maxBarterIngots, value -> trading.maxBarterIngots = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 64))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.trading.default_ingots"))
                        .description(OptionDescription.of(tr("friendai.config.trading.default_ingots.tooltip")))
                        .binding(8, () -> trading.barterDefaultIngots, value -> trading.barterDefaultIngots = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 64))
                        .build())
                .build();
    }

    static ConfigCategory resourcesCategory(ServerConfig server) {
        var resources = server.tasks.resources;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.resources"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.resources.expeditions",
                        "friendai.config.resources.expeditions.tooltip",
                        true,
                        () -> resources.allowExpeditions,
                        value -> resources.allowExpeditions = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.local_radius"))
                        .description(OptionDescription.of(tr("friendai.config.resources.local_radius.tooltip")))
                        .binding(64, () -> resources.localRadius, value -> resources.localRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(16, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.local_seconds"))
                        .description(OptionDescription.of(tr("friendai.config.resources.local_seconds.tooltip")))
                        .binding(
                                120,
                                () -> resources.localTicks / 20,
                                value -> resources.localTicks = Math.max(200, value * 20))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 600))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.expedition_radius"))
                        .description(OptionDescription.of(tr("friendai.config.resources.expedition_radius.tooltip")))
                        .binding(512, () -> resources.expeditionRadius, value -> resources.expeditionRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(16, 2048))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.expedition_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.resources.expedition_minutes.tooltip")))
                        .binding(
                                10,
                                () -> resources.expeditionTicks / 1_200,
                                value -> resources.expeditionTicks = Math.max(1_200, value * 1_200))
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 40))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.observation_radius"))
                        .description(OptionDescription.of(tr("friendai.config.resources.observation_radius.tooltip")))
                        .binding(64, () -> resources.observationRadius, value -> resources.observationRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(4, 64))
                        .build())
                .option(booleanOption(
                        "friendai.config.resources.wait_for_owner",
                        "friendai.config.resources.wait_for_owner.tooltip",
                        true,
                        () -> resources.waitForOwner,
                        value -> resources.waitForOwner = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.owner_sight"))
                        .description(OptionDescription.of(tr("friendai.config.resources.owner_sight.tooltip")))
                        .binding(32, () -> resources.ownerSightRadius, value -> resources.ownerSightRadius = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(8, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.owner_hold"))
                        .description(OptionDescription.of(tr("friendai.config.resources.owner_hold.tooltip")))
                        .binding(48, () -> resources.ownerHoldDistance, value -> resources.ownerHoldDistance = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(9, 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.resources.owner_resume"))
                        .description(OptionDescription.of(tr("friendai.config.resources.owner_resume.tooltip")))
                        .binding(
                                24, () -> resources.ownerResumeDistance, value -> resources.ownerResumeDistance = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 511))
                        .build())
                .build();
    }

    static ConfigCategory autonomyCategory(ServerConfig server) {
        var autonomy = server.autonomy;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.autonomy"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.autonomy.enabled",
                        "friendai.config.autonomy.enabled.tooltip",
                        true,
                        () -> autonomy.enabled,
                        value -> autonomy.enabled = value))
                .option(booleanOption(
                        "friendai.config.autonomy.idle_start",
                        "friendai.config.autonomy.idle_start.tooltip",
                        true,
                        () -> autonomy.idleStart,
                        value -> autonomy.idleStart = value))
                .option(booleanOption(
                        "friendai.config.autonomy.while_offline",
                        "friendai.config.autonomy.while_offline.tooltip",
                        true,
                        () -> autonomy.whileOffline,
                        value -> autonomy.whileOffline = value))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.resume_after"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.resume_after.tooltip")))
                        .binding(60, () -> autonomy.resumeAfterSec, value -> autonomy.resumeAfterSec = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(10, 900))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.idle_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.idle_minutes.tooltip")))
                        .binding(5, () -> autonomy.idleMinutes, value -> autonomy.idleMinutes = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 240))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.shift_minutes"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.shift_minutes.tooltip")))
                        .binding(20, () -> autonomy.shiftMinutes, value -> autonomy.shiftMinutes = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(1, 240))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.max_shifts"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.max_shifts.tooltip")))
                        .binding(6, () -> autonomy.maxShiftsPerHour, value -> autonomy.maxShiftsPerHour = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.offline_shifts"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.offline_shifts.tooltip")))
                        .binding(2, () -> autonomy.offlineShiftsPerHour, value -> autonomy.offlineShiftsPerHour = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 60))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.autonomy.fail_cooldown"))
                        .description(OptionDescription.of(tr("friendai.config.autonomy.fail_cooldown.tooltip")))
                        .binding(10, () -> autonomy.failCooldownMin, value -> autonomy.failCooldownMin = value)
                        .controller(option ->
                                IntegerFieldControllerBuilder.create(option).range(0, 240))
                        .build())
                .option(booleanOption(
                        "friendai.config.autonomy.protect_places",
                        "friendai.config.autonomy.protect_places.tooltip",
                        true,
                        () -> autonomy.protectPlaces,
                        value -> autonomy.protectPlaces = value))
                .option(booleanOption(
                        "friendai.config.autonomy.allow_building",
                        "friendai.config.autonomy.allow_building.tooltip",
                        true,
                        () -> autonomy.allowBuilding,
                        value -> autonomy.allowBuilding = value))
                .build();
    }

    static ConfigCategory memoryCategory(ServerConfig server) {
        var m = server.memory;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.memory"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.memory.enabled",
                        "friendai.config.memory.enabled.tooltip",
                        true,
                        () -> m.enabled,
                        v -> m.enabled = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_fact_chars"))
                        .description(OptionDescription.of(tr("friendai.config.memory.max_fact_chars.tooltip")))
                        .binding(512, () -> m.maxFactChars, v -> m.maxFactChars = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(40, 512))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_facts_per_player"))
                        .description(OptionDescription.of(tr("friendai.config.memory.max_facts_per_player.tooltip")))
                        .binding(128, () -> m.maxFactsPerPlayer, v -> m.maxFactsPerPlayer = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 128))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.max_world_facts"))
                        .description(OptionDescription.of(tr("friendai.config.memory.max_world_facts.tooltip")))
                        .binding(128, () -> m.maxWorldFacts, v -> m.maxWorldFacts = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 200))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.transcript_entries"))
                        .description(OptionDescription.of(tr("friendai.config.memory.transcript_entries.tooltip")))
                        .binding(200, () -> m.transcriptMaxEntries, v -> m.transcriptMaxEntries = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(20, 2_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.transcript_chars"))
                        .description(OptionDescription.of(tr("friendai.config.memory.transcript_chars.tooltip")))
                        .binding(400, () -> m.transcriptMaxTextChars, v -> m.transcriptMaxTextChars = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(100, 2_000))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.transcript_retention_days"))
                        .description(
                                OptionDescription.of(tr("friendai.config.memory.transcript_retention_days.tooltip")))
                        .binding(7, () -> m.transcriptRetentionDays, v -> m.transcriptRetentionDays = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(1, 90))
                        .build())
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.memory.compact_at_percent"))
                        .description(OptionDescription.of(tr("friendai.config.memory.compact_at_percent.tooltip")))
                        .binding(90, () -> m.compactAtPercent, v -> m.compactAtPercent = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(50, 100))
                        .build())
                .option(booleanOption(
                        "friendai.config.memory.compaction_enabled",
                        "friendai.config.memory.compaction_enabled.tooltip",
                        true,
                        () -> m.compactionEnabled,
                        v -> m.compactionEnabled = v))
                .build();
    }

    /** A valuable drop's protection: whether it is on, what counts as valuable and how many blocks it may cost. */
    static ConfigCategory miningCategory(ServerConfig server) {
        ServerConfig.MiningSection m = server.mining;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.mining"), "friendai.config.scope.server"))
                .option(booleanOption(
                        "friendai.config.mining.protect_drops",
                        "friendai.config.mining.protect_drops.tooltip",
                        true,
                        () -> m.protectDrops,
                        v -> m.protectDrops = v))
                .option(Option.<Integer>createBuilder()
                        .name(tr("friendai.config.mining.catch_blocks"))
                        .description(OptionDescription.of(tr("friendai.config.mining.catch_blocks.tooltip")))
                        .binding(9, () -> m.catchBlocks, v -> m.catchBlocks = v)
                        .controller(o -> IntegerFieldControllerBuilder.create(o).range(0, 27))
                        .build())
                .option(Option.<String>createBuilder()
                        .name(tr("friendai.config.mining.valuable_blocks"))
                        .description(OptionDescription.of(tr("friendai.config.mining.valuable_blocks.desc")))
                        .binding(
                                String.join(",", MiningRuntimeSettings.DEFAULT_VALUABLE),
                                () -> m.valuableBlocks == null ? "" : String.join(",", m.valuableBlocks),
                                v -> m.valuableBlocks = new java.util.ArrayList<>(java.util.Arrays.stream(v.split(","))
                                        .map(String::strip)
                                        .filter(entry -> !entry.isEmpty())
                                        .toList()))
                        .controller(StringControllerBuilder::create)
                        .build())
                .build();
    }

    static ConfigCategory socialCategory(ClientConfig client) {
        var social = client.social;
        return ConfigCategory.createBuilder()
                .name(scoped(tr("friendai.config.category.display"), "friendai.config.scope.client"))
                .option(booleanOption(
                        "friendai.config.social.mood_mark",
                        "friendai.config.social.mood_mark.tooltip",
                        true,
                        () -> social.moodMark,
                        value -> social.moodMark = value))
                .build();
    }
}
