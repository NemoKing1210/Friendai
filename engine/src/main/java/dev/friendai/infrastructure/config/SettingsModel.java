package dev.friendai.infrastructure.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Navigation model of the settings screen: which option lives in which category, which options are
 * advanced, and how a search query matches them.
 *
 * <p>Pure data so it can be unit-tested without a game. The keys are the very translation keys the screen
 * already uses, which keeps the catalogue and the widgets from drifting apart: a key that is not in the
 * catalogue still shows, it is simply never hidden and matches only on its own words.
 *
 * <p>Categories are the screen's tabs and are reported in the order they appear; the screen never reorders
 * them, it only hides the ones a query leaves empty.
 */
public final class SettingsModel {
    /**
     * One editable option. {@code key} is the translation key the screen names the option with,
     * {@code labelKey} is the key whose text is the label (the same key today), {@code category} is the tab
     * id, {@code advanced} marks keys, URLs, budgets, radii and internals, and {@code searchTerms} holds
     * lower-case synonyms that are not part of the label.
     */
    public record Entry(String key, String category, String labelKey, boolean advanced, List<String> searchTerms) {
        public Entry {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
            if (category == null || category.isBlank())
                throw new IllegalArgumentException("category must not be blank");
            if (labelKey == null || labelKey.isBlank())
                throw new IllegalArgumentException("labelKey must not be blank");
            List<String> terms = new ArrayList<>();
            for (String term : searchTerms) {
                if (term == null || term.isBlank()) continue;
                String lower = term.strip().toLowerCase(Locale.ROOT);
                if (!terms.contains(lower)) terms.add(lower);
            }
            searchTerms = List.copyOf(terms);
        }
    }

    /** The catalogue, in the order the screen renders its categories and options. */
    private static final List<Entry> CATALOGUE = catalogueEntries();

    private SettingsModel() {}

    /** Every known option, in screen order. */
    public static List<Entry> catalogue() {
        return CATALOGUE;
    }

    /** The category ids present in the catalogue, in screen order. */
    public static List<String> categories() {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry entry : CATALOGUE) ids.add(entry.category());
        return List.copyOf(ids);
    }

    public static Optional<Entry> entry(String key) {
        if (key == null) return Optional.empty();
        for (Entry entry : CATALOGUE) {
            if (entry.key().equals(key)) return Optional.of(entry);
        }
        return Optional.empty();
    }

    /**
     * True when a query leaves the option visible. A blank query matches everything; every whitespace
     * separated word must match the label, a synonym or a word of the key, ignoring case. An unknown key
     * matches only on its own words, so buttons and labels stay with their category.
     */
    public static boolean matches(String query, String key) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        if (key == null || key.isBlank()) return false;
        List<String> terms = termsOf(key);
        for (String word : needle.split("\\s+")) {
            if (word.isEmpty()) continue;
            boolean found = false;
            for (String term : terms) {
                if (term.contains(word)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** The keys a query leaves visible, in catalogue order. A blank query returns every known key. */
    public static Set<String> search(String query) {
        Set<String> keys = new LinkedHashSet<>();
        for (Entry entry : CATALOGUE) {
            if (matches(query, entry.key())) keys.add(entry.key());
        }
        return keys;
    }

    /**
     * True when the option belongs in the current Basic/Advanced mode. Advanced entries are hidden while the
     * mode is Basic; an unknown key is never hidden, so a new option stays reachable until it is catalogued.
     */
    public static boolean show(String key, boolean advancedOn) {
        return entry(key).map(entry -> advancedOn || !entry.advanced()).orElse(true);
    }

    private static List<String> termsOf(String key) {
        List<String> terms = new ArrayList<>();
        entry(key).ifPresent(entry -> terms.addAll(entry.searchTerms()));
        terms.add(key.toLowerCase(Locale.ROOT));
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                word.append(Character.toLowerCase(c));
            } else if (!word.isEmpty()) {
                if (!terms.contains(word.toString())) terms.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty() && !terms.contains(word.toString())) terms.add(word.toString());
        return terms;
    }

    private static List<Entry> catalogueEntries() {
        List<Entry> entries = new ArrayList<>();
        // Provider: what the model talks to. Keys and URLs are advanced so a fresh install shows three fields.
        entries.add(basic("friendai.config.profile", "provider", "profile", "account", "preset"));
        entries.add(basic("friendai.config.provider", "provider", "provider", "service", "api"));
        entries.add(basic("friendai.config.model", "provider", "model", "llm", "ai", "engine"));
        entries.add(basic("friendai.config.temperature", "provider", "temperature", "creativity", "randomness"));
        entries.add(advanced("friendai.config.max_tokens", "provider", "tokens", "length", "reply"));
        entries.add(
                advanced("friendai.config.api_key", "provider", "key", "token", "secret", "password", "credential"));
        entries.add(advanced("friendai.config.base_url", "provider", "url", "endpoint", "address", "proxy"));

        // Companion: who the companion is and what it may learn.
        entries.add(basic("friendai.config.companion.name", "companion", "name", "nickname"));
        entries.add(basic("friendai.config.companion.description", "companion", "description", "personality"));
        entries.add(basic("friendai.config.companion.language", "companion", "language", "locale"));
        entries.add(basic("friendai.config.companion.chunk_loading", "companion", "chunk", "loading", "keep"));
        entries.add(advanced("friendai.config.companion.chunk_radius", "companion", "radius", "distance", "chunks"));
        entries.add(basic("friendai.config.skills.enabled", "companion", "skills", "learning"));
        entries.add(basic("friendai.config.skills.approval", "companion", "approval", "confirm", "permission"));
        entries.add(advanced("friendai.config.skills.budget", "companion", "budget", "prompt", "tokens"));
        entries.add(advanced("friendai.config.skills.hourly", "companion", "writes", "hour", "rate", "limit"));
        entries.add(advanced("friendai.config.skills.per_outcome", "companion", "writes", "outcome", "limit"));
        entries.add(advanced("friendai.config.skills.owner_count", "companion", "count", "owner", "limit"));
        entries.add(advanced("friendai.config.skills.server_count", "companion", "count", "server", "limit"));
        entries.add(advanced("friendai.config.skills.total_chars", "companion", "chars", "size", "budget"));
        entries.add(advanced("friendai.config.agent.planner", "companion", "planner", "profile"));
        entries.add(advanced("friendai.config.agent.planner_retries", "companion", "retries", "planner"));
        entries.add(advanced("friendai.config.tasks.place_interval", "companion", "place", "interval", "ticks"));

        // Chat and hearing.
        entries.add(basic("friendai.config.chat.prefix", "chat", "prefix", "command", "trigger"));
        entries.add(basic("friendai.config.chat.respond_to_name", "chat", "name", "respond", "answer"));
        entries.add(basic("friendai.config.chat.listen_all", "chat", "listen", "eavesdrop", "nearby"));
        entries.add(basic("friendai.config.chat.guests_enabled", "chat", "guests", "strangers", "players"));
        entries.add(basic("friendai.config.chat.ambient_speech", "chat", "ambient", "chatter", "idle"));
        entries.add(advanced("friendai.config.chat.hearing_radius", "chat", "radius", "hearing", "distance", "range"));
        entries.add(advanced("friendai.config.chat.max_reply_chars", "chat", "reply", "length", "chars"));
        entries.add(advanced("friendai.config.chat.guest_cooldown_seconds", "chat", "cooldown", "delay", "guests"));

        // Planning and the model loop.
        entries.add(basic("friendai.config.autonomy", "planning", "autonomy", "mode", "passive", "assistive"));
        entries.add(advanced("friendai.config.agent.tool_routing", "planning", "tools", "routing", "tokens"));
        entries.add(advanced("friendai.config.agent.max_replans", "planning", "replans", "retries"));
        entries.add(advanced("friendai.config.agent.goal_tick_sec", "planning", "goal", "tick", "interval"));
        entries.add(advanced("friendai.config.agent.prompt_budget", "planning", "prompt", "budget", "tokens"));
        entries.add(advanced("friendai.config.agent.planner_timeout_sec", "planning", "planner", "timeout"));
        entries.add(advanced("friendai.config.brain.max_tool_steps", "planning", "tool", "steps", "turns"));
        entries.add(advanced("friendai.config.brain.history_messages", "planning", "history", "messages"));
        entries.add(advanced("friendai.config.brain.request_timeout_sec", "planning", "timeout", "request"));
        entries.add(advanced("friendai.config.brain.cooldown_ms", "planning", "cooldown", "delay"));

        // Tasks: what the companion does on request.
        entries.add(basic("friendai.config.tasks.default_follow", "tasks", "follow", "default"));
        entries.add(basic("friendai.config.tasks.fell_collect", "tasks", "collect", "drops", "wood"));
        entries.add(basic("friendai.config.tasks.mine_collect", "tasks", "collect", "drops", "ore"));
        entries.add(basic("friendai.config.tasks.tree_perception", "tasks", "perception", "tree", "see"));
        entries.add(advanced("friendai.config.tasks.mine_search_radius", "tasks", "radius", "mine", "search"));
        entries.add(advanced("friendai.config.tasks.tree_search_radius", "tasks", "radius", "tree", "search"));
        entries.add(advanced("friendai.config.tasks.timeout_sec", "tasks", "timeout", "task"));
        entries.add(advanced("friendai.config.tasks.queue_max_jobs", "tasks", "queue", "jobs", "limit"));
        entries.add(advanced("friendai.config.tasks.queue_max_plans", "tasks", "queue", "plans", "limit"));
        entries.add(advanced("friendai.config.tasks.queue_max_steps", "tasks", "queue", "steps", "limit"));
        entries.add(advanced("friendai.config.tasks.tree_perception_radius", "tasks", "radius", "perception"));
        entries.add(advanced("friendai.config.tasks.queue_blocked_notice_sec", "tasks", "queue", "notice", "blocked"));
        entries.add(advanced("friendai.config.tasks.free_time_watchdog_sec", "tasks", "watchdog", "free time"));

        // World: movement, places and structures.
        entries.add(basic("friendai.config.pathing.allow_break", "world", "break", "dig", "path"));
        entries.add(basic("friendai.config.pathing.allow_place", "world", "place", "build", "path"));
        entries.add(basic("friendai.config.places.enabled", "world", "places", "waypoints", "homes"));
        entries.add(basic("friendai.config.places.auto_remember", "world", "remember", "places", "auto"));
        entries.add(advanced("friendai.config.pathing.search_budget_ms", "world", "budget", "path", "ms"));
        entries.add(advanced("friendai.config.pathing.max_gather", "world", "gather", "blocks", "limit"));
        entries.add(advanced("friendai.config.pathing.max_nodes", "world", "nodes", "path", "limit"));
        entries.add(advanced("friendai.config.places.max", "world", "places", "limit"));
        entries.add(advanced("friendai.config.places.auto_mine_min_blocks", "world", "mine", "minimum"));
        entries.add(advanced("friendai.config.places.merge_radius", "world", "radius", "merge", "places"));
        entries.add(advanced("friendai.config.places.prompt_limit", "world", "prompt", "limit", "places"));
        entries.add(advanced("friendai.config.places.prompt_budget", "world", "prompt", "budget", "places"));
        entries.add(advanced("friendai.config.places.planner_limit", "world", "planner", "limit", "places"));
        entries.add(advanced("friendai.config.structures.max_nearby", "world", "structures", "mobs", "limit"));

        // Trading with villagers.
        entries.add(basic("friendai.config.trading.enabled", "trading", "trading", "trades", "villagers"));
        entries.add(basic("friendai.config.trading.idle_refresh", "trading", "refresh", "idle", "trades"));
        entries.add(basic("friendai.config.trading.max_ingots", "trading", "ingots", "barter", "maximum"));
        entries.add(basic("friendai.config.trading.default_ingots", "trading", "ingots", "barter", "default"));
        entries.add(advanced("friendai.config.trading.radius", "trading", "radius", "villagers"));
        entries.add(advanced("friendai.config.trading.quote_fresh_seconds", "trading", "quote", "fresh"));
        entries.add(advanced("friendai.config.trading.location_fresh_minutes", "trading", "location", "fresh"));
        entries.add(advanced("friendai.config.trading.idle_refresh_seconds", "trading", "refresh", "idle"));
        entries.add(advanced("friendai.config.trading.wait_seconds", "trading", "wait", "seconds"));
        entries.add(advanced("friendai.config.trading.max_wait_seconds", "trading", "wait", "maximum"));

        // Resources and expeditions.
        entries.add(basic("friendai.config.resources.expeditions", "resources", "expeditions", "gathering"));
        entries.add(basic("friendai.config.resources.wait_for_owner", "resources", "owner", "wait"));
        entries.add(advanced("friendai.config.resources.local_radius", "resources", "radius", "local"));
        entries.add(advanced("friendai.config.resources.local_seconds", "resources", "local", "seconds"));
        entries.add(advanced("friendai.config.resources.expedition_radius", "resources", "radius", "expedition"));
        entries.add(advanced("friendai.config.resources.expedition_minutes", "resources", "expedition", "minutes"));
        entries.add(advanced("friendai.config.resources.observation_radius", "resources", "radius", "observation"));
        entries.add(advanced("friendai.config.resources.owner_sight", "resources", "owner", "sight"));
        entries.add(advanced("friendai.config.resources.owner_hold", "resources", "owner", "hold"));
        entries.add(advanced("friendai.config.resources.owner_resume", "resources", "owner", "resume"));

        // Autonomy: what the companion starts on its own.
        entries.add(basic("friendai.config.autonomy.enabled", "autonomy", "autonomy", "enabled", "self"));
        entries.add(basic("friendai.config.autonomy.idle_start", "autonomy", "idle", "start"));
        entries.add(basic("friendai.config.autonomy.idle_minutes", "autonomy", "idle", "minutes", "delay"));
        entries.add(basic("friendai.config.autonomy.while_offline", "autonomy", "offline", "owner"));
        entries.add(basic("friendai.config.autonomy.shift_minutes", "autonomy", "shift", "minutes"));
        entries.add(basic("friendai.config.autonomy.protect_places", "autonomy", "protect", "places"));
        entries.add(basic("friendai.config.autonomy.allow_building", "autonomy", "build", "building"));
        entries.add(advanced("friendai.config.autonomy.resume_after", "autonomy", "resume", "seconds"));
        entries.add(advanced("friendai.config.autonomy.max_shifts", "autonomy", "shifts", "limit", "hour"));
        entries.add(advanced("friendai.config.autonomy.offline_shifts", "autonomy", "shifts", "offline"));
        entries.add(advanced("friendai.config.autonomy.fail_cooldown", "autonomy", "cooldown", "failure"));

        // Memory and transcripts.
        entries.add(basic("friendai.config.memory.enabled", "memory", "memory", "remember"));
        entries.add(advanced("friendai.config.memory.max_fact_chars", "memory", "fact", "chars", "limit"));
        entries.add(advanced("friendai.config.memory.max_facts_per_player", "memory", "facts", "player", "limit"));
        entries.add(advanced("friendai.config.memory.max_world_facts", "memory", "facts", "world", "limit"));
        entries.add(advanced("friendai.config.memory.transcript_entries", "memory", "transcript", "entries"));
        entries.add(advanced("friendai.config.memory.transcript_chars", "memory", "transcript", "chars"));
        entries.add(advanced("friendai.config.memory.transcript_retention_days", "memory", "transcript", "days"));
        entries.add(advanced("friendai.config.memory.compact_at_percent", "memory", "compact", "percent"));
        entries.add(advanced("friendai.config.memory.compaction_enabled", "memory", "compaction"));

        // Mining.
        entries.add(basic("friendai.config.mining.protect_drops", "mining", "protect", "drops", "mining"));
        entries.add(advanced("friendai.config.mining.catch_blocks", "mining", "catch", "blocks", "mining"));
        entries.add(advanced("friendai.config.mining.valuable_blocks", "mining", "valuable", "list", "blacklist"));

        // How the companion is drawn.
        entries.add(basic("friendai.config.social.outline", "display", "outline", "highlight", "glow"));
        entries.add(basic("friendai.config.social.mood_mark", "display", "mood", "mark", "emotion"));

        // Voice: recognition and synthesis.
        entries.add(basic("friendai.config.voice.enabled", "voice", "voice", "microphone", "mic", "stt"));
        entries.add(basic("friendai.config.voice.stt.provider", "voice", "provider", "recognition", "stt"));
        entries.add(basic("friendai.config.voice.language", "voice", "language", "locale"));
        entries.add(basic("friendai.config.voice.model", "voice", "model", "whisper"));
        entries.add(basic("friendai.config.voice.device", "voice", "device", "microphone", "input"));
        entries.add(basic("friendai.config.voice.max_record", "voice", "record", "seconds", "length"));
        entries.add(basic("friendai.config.voice.tts.enabled", "voice", "speech", "tts", "speak", "talk"));
        entries.add(basic("friendai.config.voice.tts.provider", "voice", "provider", "tts", "speech"));
        entries.add(basic("friendai.config.voice.tts.local.voice", "voice", "voice", "speaker", "tts"));
        entries.add(basic("friendai.config.voice.tts.volume", "voice", "volume", "loudness"));
        entries.add(basic("friendai.config.voice.tts.spatial", "voice", "spatial", "position", "3d"));
        entries.add(basic("friendai.config.voice.tts.speak_guests", "voice", "guests", "speak", "tts"));
        entries.add(basic("friendai.config.voice.tts.output_device", "voice", "device", "output", "speaker"));
        entries.add(basic("friendai.config.voice.field.model", "voice", "model", "cloud"));
        entries.add(basic("friendai.config.voice.field.voice", "voice", "voice", "cloud"));
        entries.add(basic("friendai.config.voice.field.voice_id", "voice", "voice", "id", "cloud"));
        entries.add(basic("friendai.config.voice.field.language", "voice", "language", "cloud"));
        entries.add(advanced("friendai.config.voice.tts.max_chars", "voice", "chars", "length", "tts"));
        entries.add(advanced("friendai.config.voice.tts.speed", "voice", "speed", "rate", "tts"));
        entries.add(advanced("friendai.config.voice.field.key", "voice", "key", "token", "secret", "cloud"));
        entries.add(advanced("friendai.config.voice.field.base_url", "voice", "url", "endpoint", "cloud"));
        entries.add(advanced("friendai.config.voice.field.folder_id", "voice", "folder", "id", "cloud"));
        return entries;
    }

    private static Entry basic(String key, String category, String... searchTerms) {
        return new Entry(key, category, key, false, List.of(searchTerms));
    }

    private static Entry advanced(String key, String category, String... searchTerms) {
        return new Entry(key, category, key, true, List.of(searchTerms));
    }
}
