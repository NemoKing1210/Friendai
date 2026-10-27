# Manual checklist

Things automated tests cannot cover. Tick an item only after you have actually done it, and record the model and date.

## Providers — "Test connection" for each preset

| Preset | Model | Date | Result |
|---|---|---|---|
| `openai` | | | [ ] |
| `openai_compat` | | | [ ] |
| `anthropic` | | | [ ] |
| `gemini` | | | [ ] |
| `ollama` | | | [ ] |
| `opencode_zen` | | | [ ] |
| `command_code` | | | [ ] |

## End to end with a real provider

- [ ] `/friendai summon`, then "Friendai, mine 3 logs" next to trees: reply in chat, logs chopped, drops collected, result reported.
- [ ] The same in Russian ("Friendai, добудь 3 дерева"): the companion answers in Russian.
- [ ] "Friendai, chop that tree" next to a tall tree: it is felled from the lowest log up, the logs are collected and the result is reported.
- [ ] In a savanna with only acacia and no oak, ask for stone tools: the companion uses the acacia logs at hand instead of walking away to find oak.
- [ ] Observe a tree, wait about five seconds, then ask it to chop the tree: the recent sighting is still used.
- [ ] Fill the companion's inventory, then ask it to chop one log: the log stays in place and the reply says the inventory is full.
- [ ] `search_resource` with `#minecraft:logs`: it inspects its surroundings first and only travels when nothing is in sight.
- [ ] "Friendai, quickly, three logs from that tree": exactly three logs are cut and the task finishes.
- [ ] A log cabin or wall next to the companion is not felled by "chop a tree".
- [ ] "Friendai, chop the tree next to me" while the companion stands elsewhere: the tree at your position is felled, not the one at the companion.
- [ ] The same in Russian ("Friendai, сруби дерево рядом со мной"): the companion answers in Russian and fells the tree.
- [ ] Changing the model in the settings screen and saving: the next reply uses the new model (`/friendai status`).
- [ ] "Friendai, drop all your cobblestone and then mine 3 logs": the companion adds both actions to its queue and reports the plan result.
- [ ] A plan that cannot finish (e.g. mine a block that is not nearby): the companion explains the blocked step and repairs the queue with `queue_edit`.
- [ ] A real provider drives a 20+ step plan and moves or reprioritizes a pending job while it is running.
- [ ] A failed step produces a plan decision event and the companion repairs it with `queue_edit` without resending the whole plan.
- [ ] Reload a world mid-plan: ready work resumes, and a job whose tool disappeared reports that it needs repair.
- [ ] `queue_show` remains readable with 50+ jobs and truncates long arguments and results.
- [ ] A fight and a starving entry each interrupt the running queue job and resume it afterwards.
- [ ] Cancel a build with `queue_edit cancel`: scaffold cleanup finishes before the next job starts.

## Voice

- [ ] Settings → Voice → Download `base`: progress toasts, then "Installed".
- [ ] "Test recording" recognizes English speech.
- [ ] "Test recording" recognizes Russian speech.
- [ ] Hold **V** in a world and say "mine two logs": the chat shows the voice line, the companion replies and does it.
- [ ] Hold **V** while speaking: the capsule is centered horizontally above the hotbar, its dot pulses and the bars follow your voice; after release the waiting dots animate while transcribing.
- [ ] No microphone / no model: the HUD shows a hint and nothing else breaks.
- [ ] Local recognition with each Whisper model (`tiny`, `base` and `small`).
- [ ] OpenAI recognition with a real key (default model `gpt-transcribe`).
- [ ] Groq recognition with a real key (default model `whisper-large-v3`).
- [ ] Yandex recognition with a real key and folder ID; a language it does not support shows a clear message.
- [ ] A local OpenAI-compatible recognition server (custom base URL, no key) transcribes.
- [ ] Settings → Voice → Synthesis → a local voice → **Download voice**: progress toasts every 10%, then "Voice installed"; the license line matches the voice.
- [ ] Every catalog voice (Irina, Dmitri, Lessac, LibriTTS-R, Alba) downloads and speaks.
- [ ] **Test voice** plays the sample through the selected output device; the volume slider changes how loud it is.
- [ ] In a world, a chat reply is spoken once (not once per chat line); the output device and speed settings take effect.
- [ ] With "Speak replies addressed to other players" off, a reply to another player stays silent; on, it is spoken too.
- [ ] OpenAI with a valid key speaks a reply. A wrong key shows one error toast and no storm; speech stays idle until the settings change.
- [ ] ElevenLabs with a valid key and voice id speaks a reply.
- [ ] Yandex with a valid key, folder ID and voice speaks a reply.
- [ ] A local OpenAI-compatible server (custom base URL, no key) speaks a reply.
- [ ] Pressing push-to-talk during a reply stops it; the **Stop speaking** keybind (Controls → Friendai) does the same; nothing is recorded while a reply plays.
- [ ] A reply that begins just as push-to-talk is pressed is cut off instead of playing into the recording (the audio device takes a moment to open).
- [ ] The synthesis volume slider set to 0 stays muted after saving and reopening the settings screen.
- [ ] A base URL pasted with leading or trailing whitespace is accepted, shows ready and works.
- [ ] Pressing push-to-talk while a screen is open during "Transcribing…" neither loses the transcript nor leaves the HUD stuck.
- [ ] Changing a speech setting while a reply is being synthesized: saving does not freeze the screen, and the next reply uses the new setting.
- [ ] Leaving the world stops the audio and rejoining speaks again; the client config keeps the enabled setting.
- [ ] Every Recognition and Synthesis label is translated in both English and Russian, including the provider, engine and voice names.
- [ ] After a cloud request and a wrong-key request, `logs/latest.log` contains no API key.
- [ ] Spatial speech on: a reply is louder and clearer on the side the companion stands; walking away fades it to silence at the hearing radius.
- [ ] Turning the head during a reply moves the balance.
- [ ] Chat and voice at 40 blocks from the companion get no reply and the "too far" notice.
- [ ] Two replies from different companions in a row each sound from their own position.
- [ ] A mono output device still fades with distance (no crash, no panning).

## Multiplayer

- [ ] LAN with two clients: each player has their own companion; addressing by name reaches your own companion when it is within 32 blocks, otherwise the nearest other companion within that radius.
- [ ] A guest next to another player's companion chats with it; asking it to act is politely declined.
- [ ] `/friendai trust <guest>`: the guest's request now runs; `/friendai untrust` takes effect on the next message.
- [ ] `/friendai trusted` and the trust messages read correctly in English and Russian.
- [ ] Restart the server: the trusted list survives (`<world>/friendai/memory/<uuid>.json`).
- [ ] A guest spamming the companion is throttled; the owner is not.
- [ ] Dedicated server: edit `config/friendai-server.json`, run `/friendai reload` as an operator, behaviour changes.
- [ ] The settings screen on a client connected to a dedicated server shows "These settings are configured on the server."

## Companion creative mode

- [ ] Singleplayer world owner enables creative from the Control tab with cheats off; creative abilities work, but `execute_command` still follows normal command permissions and cannot run privileged commands.
- [ ] On a dedicated server, an operator owner can enable and disable creative; a non-operator owner and a guest cannot enable it, and a guest cannot change another owner's mode.
- [ ] Revoke the owner's operator status while creative is active: new creative-only tasks stop, but the owner can still turn creative off.
- [ ] Creative preference survives dismiss/resummon, world save/reload and companion dimension transfer.
- [ ] At narrow and wide GUI scales, the Creative toggle, movement controls and status do not overlap; stale snapshots and unavailable permission show the correct disabled state and tooltip.
- [ ] Fire, drowning, falls, ordinary attacks and hunger do not affect a creative companion; toggling does not heal or refill food.
- [ ] The companion breaks permitted bedrock instantly without drops or XP; a protected or blacklisted block stays intact.
- [ ] Place a block and use an item in creative: materials and durability are not consumed or duplicated; the same interactions consume materials normally after disabling creative.
- [ ] Build a blueprint without materials; denied protection checks still stop placement. A guest or trusted player's ordinary build/obtain request does not gain free materials.
- [ ] Create stackable and non-stackable registered items for self and a nearby owner; a full inventory reports the remaining count, cancellation stops later batches, and owner logout/operator revocation blocks further batches.
- [ ] Fly up/down and across a gap around walls and ceilings; the route does not cross unloaded chunks or world bounds. Stop, dimension transfer and mode exit leave no stale movement or no-gravity state.
- [ ] Disable creative while airborne and above a void: bounded landing or its safe timeout finishes, then survival gravity and damage rules apply.
- [ ] `execute_command` runs allowed teleport/time/weather commands from the companion; `@s` is the companion, and `give`/`enchant` can target only the owner.
- [ ] `execute`, `function`, `schedule`, unknown roots, malformed commands, unregistered item/effect ids and non-owner targets are rejected without claiming success.
- [ ] In dedicated-server testing, revoke operator permission or disconnect the owner between queueing and dispatch; the command is rejected and does not continue from a stale entity after teleport/removal.

## Memory

- [ ] With a real model: tell the companion your name and where your base is; it calls `remember`, and after a server restart it still knows both.
- [ ] A guest tells the companion something about themselves; `/friendai memory list` shows it; the guest cannot make it remember things about the owner.
- [ ] A guest asks "what do you know about my friend's base?": the companion does not reveal the owner's or world facts.
- [ ] `/friendai memory forget <id>` and `/friendai memory clear world` work; messages read correctly in English and Russian.
- [ ] Settings → Memory → off, then `/friendai reload`: the companion no longer remembers; `/friendai memory` says memory is off.

## Places

- [ ] "Remember this as home": `remember_place` stores it; `/friendai place list` and the Places tab show it, and after a server restart it is still there (`<world>/friendai/places/<uuid>.json`).
- [ ] "Where is the wheat farm?": `find_place` lists it with its coordinates and, in the same dimension, the distance; `go_to_place` then walks there and the visit count grows.
- [ ] Mine or build at a new spot with auto-registration on: one place appears per site rather than one per block, and working the same site again refreshes that place's geometry instead of adding a second one.
- [ ] Mine at least 16 blocks in one dig with auto-registration on (`places.autoMineMinBlocks`): a `MINE` place appears, labelled after the block dug (e.g. "cobblestone"), while a dig of a few blocks leaves the book untouched.
- [ ] Remember a place in the Nether, return to the Overworld and ask the companion to go there: `go_to_place` refuses, saying the place is in `minecraft:the_nether` and that it cannot cross dimensions.
- [ ] Fill the book to `places.maxPlaces`, then ask to remember one more: the tool refuses and says to forget one first; renaming an existing place still works.
- [ ] `/friendai place add`, `info`, `rename` and `remove` work for the owner, and their replies read correctly in English and Russian.

## Companion control menu

- [ ] Esc in a world: the companion icon sits in the icon row next to the other icons, has a tooltip, and looks right at GUI scales 1-4 and in both languages.
- [ ] The button opens the menu; Done returns to the pause menu; Esc closes it.
- [ ] Control tab: Summon, Dismiss and Stop work and the status block follows them within about half a second.
- [ ] Tasks tab: a finished task shows up in the history with its status; scrolling works.
- [ ] Log tab (operator): messages, tool calls and errors appear live; kind filters work; Copy puts the visible lines on the clipboard.
- [ ] Debug tab (operator): perception text is shown; "Show prompt" shows the system prompt; Send reaches the companion; Run executes a tool (empty arguments = `{}`) and shows up in the log as "Manual".
- [ ] Dedicated server as a normal player: Control, Tasks, Memory, Places and Appearance tabs; as an operator: all seven (Log and Debug appear too).
- [ ] Single player with cheats off: the host still sees the Log and Debug tabs (the world owner counts as an operator). Same on a LAN world for the host, not for guests.
- [ ] A server without Friendai: the icon is greyed out with an explanatory tooltip.
- [ ] Another mod that changes the pause menu layout: the button still appears and nothing crashes.
- [ ] Memory tab at GUI scales 1–4 in English and Russian: nothing overlaps, long facts wrap or scroll.
- [ ] Tell the companion something with a real model while the Memory tab is open: the new fact appears within a second.
- [ ] Add, edit, forget a fact; editing a guest's fact removes the "from" mark.
- [ ] Clear asks "Sure?" and only clears on the second click.
- [ ] Trust toggle on an online stranger; the trust shows up in `/friendai trusted`.
- [ ] Memory turned off on the server: the tab shows the notice and the trust toggle still works.
- [ ] On a dedicated server, a non-operator sees the Memory tab and only their own memory.
- [ ] Memory tab → Personality: pick each preset, press Save, and the next reply changes tone; the tab shows the **Custom** badge.
- [ ] Type your own text (up to 300 characters) and Save; reopen the world: it is still there. Reset brings back the server default and removes the badge.
- [ ] Personality pane at GUI scales 1–4 in English and Russian: presets, box and buttons do not overlap.
- [ ] Two players with their own companions set different personalities: each companion keeps its own.
- [ ] Places tab: remembered places appear as cards with the kind badge, name, label, dimension and position, visit count and, in the same dimension, the distance; the newest change is first and the list scrolls.
- [ ] Places tab: select a row, type a new name and press Rename; the row and the status line update and the new name works in chat. A name another place already uses shows the error in the status line and changes nothing; the field refuses an empty or over-long name before the request is sent.
- [ ] Places tab: Delete asks "Sure?" and removes the place only on the second click; the row disappears and the status line names what was forgotten.
- [ ] Places tab at GUI scales 1-4 in English and Russian: badges, rows, the rename field and both buttons do not overlap, and every label is translated.
- [ ] Places turned off on the server (`places.enabled: false`, `/friendai reload`): the tab shows the notice instead of rows.

## Companion inventory window

- [ ] Shift+right-click the companion as its owner: a 176x202 window opens with six equipment slots, its 27 inventory slots and your own inventory; the companion stops where it is.
- [ ] Take items and put them back; shift-click both ways; a helmet only fits the head slot and the hands take anything.
- [ ] Another player shift+right-clicks the companion: only the action-bar line "Only the owner can open the companion's inventory." appears and no window opens.
- [ ] Walk more than 8 blocks away with the window open: it closes, and the companion carries on with what it was doing.
- [ ] `/friendai inventory` opens the same window; with a real model, "show me your inventory" makes the companion open it.
- [ ] Dedicated server, GUI scales 1-4, English and Russian: the panel, slot sprites and labels line up and nothing overlaps.

## World lifecycle

- [ ] Companion death: inventory drops and the owner gets a message; `/friendai summon` creates a new one.
- [ ] Leave and rejoin the world: the companion is still there; a Stay task is restored.

## Resource mining and exploration

- [ ] Walk past an exposed resource without starting a brain turn: `known_resources` reports it only after sight; a sealed or occluded resource stays unknown.
- [ ] Query a remembered site after it has been covered: the result is historical with age/uncertainty, not a promise of remaining supply; the query does not load a distant chunk.
- [ ] Ask for a remembered resource: the companion visits its saved approach, verifies with fresh sight, then replans before mining.
- [ ] Ask for `search_resource` on a surface resource: it checks bounded deterministic viewpoints and reports a find only after direct sight; repeat the same goal and verify its budget does not reset.
- [ ] Ask for `strategy=cave` without a recently observed open descending entrance: it refuses departure and does not turn toward unseen ore.
- [ ] Observe an open descending entrance with a visible interior wall, then request `strategy=cave`: the companion routes to that observed interior point, searches by direct sight, and returns without treating the entrance itself as a resource.
- [ ] Seal the visible opening or wait for its evidence to expire: a cave search refuses departure until a qualifying entrance is observed again.
- [ ] Ask for `strategy=mine` with and without a durable pickaxe and 16 torches: it refuses departure when unprepared, respects the excavation cap, and reports a find only after direct sight.
- [ ] Restart during a resource search: the active search is reported interrupted and does not resume or excavate automatically.
- [ ] With expeditions disabled (the default), request an expedition: it is refused. With chunk loading disabled, verify no expedition ticket is held and search stops at the loaded frontier.
- [ ] Ore under the companion's feet: it moves to a dry supported side stance before breaking the ore and collects the drop.
- [ ] Buried same-level ore: it tunnels horizontally through allowed blocks; a shorter existing dry passage is preferred.
- [ ] A descending staircase, including a turn: walk back through it without breaking or placing any more blocks.
- [ ] Lava or water revealed while excavating: mining stops before another unsafe block is broken; the crack overlay clears.
- [ ] Pause or resume mining after changing nearby blocks: each pending break and move is checked against the current world.
- [ ] Deep ore beyond the excavation budget: the companion leaves the ore untouched and reports that the search limit was reached.
- [ ] Default ore navigation blacklist: an explicitly requested ore is still extractable, while blacklisted corridor blocks stay intact.
- [ ] Fill the companion's inventory before mining ore: it stops before breaking the target and reports that the inventory is full.

## Blocks and containers

- [ ] "Put all the cobblestone in the chest" and "bring me 5 iron from the chest": the companion walks to the chest, the lid opens, items move, the result is reported.
- [ ] "Light up this cave": torches appear on the floor in dark spots.
- [ ] "Open the door" twice: the second time the companion says it is already open.
- [ ] Dedicated server, non-op owner: asking the companion to place or break a block inside spawn protection fails with "is protected".
- [ ] With a claim/protection mod (if available): the companion cannot place, use or break blocks in someone else's claim.

## Crafting

- [ ] "Make me a stone pickaxe" with cobblestone and logs in the companion's inventory: it crafts planks, sticks, a crafting table, the pickaxe, and takes the table back in the open.
- [ ] The same next to your base (bed and chest nearby): the crafting table stays.
- [ ] "Make 16 torches" with coal and logs.
- [ ] "Make an iron pickaxe" without iron: the companion says what is missing (get_recipe) instead of failing silently.

## Furnaces and cooking

- [ ] "Smelt all the iron" with a full stack: the companion loads furnaces, is free meanwhile, later says the furnace is done and collects when asked.
- [ ] "Cook the food" at the base with a smoker: food is cooked and collected.
- [ ] A long job, then a world reload: the result is still in the furnace and "collect the smelted items" finds it.
- [ ] The companion never burns tools or boats unless told which fuel to use.

## Survival and combat

- [ ] Give the companion iron armor: it wears it and the armor renders in first and third person.
- [ ] A night with zombies in defensive mode: it fights what attacks you or it, not everything around.
- [ ] "Guard me": it attacks hostile mobs near you; "be passive": it stays out of fights and runs.
- [ ] It eats when hurt and tells you when it is retreating without food.
- [ ] While eating, the companion raises the arm holding the food and lowers it afterwards, like a player.
- [ ] PvP server: "attack <player>" from the owner works when PvP is on and is refused with "PvP is off here" when the pvp gamerule is false; a trusted non-owner cannot order it.

## Hunger

- [ ] A long session of mining and fights: the companion eats on its own and never starves.
- [ ] Empty inventory with the owner near: it asks the owner for food instead of wandering off.
- [ ] Empty inventory with the owner far away: it goes to get food by itself.
- [ ] Starving with no food and no reaction: the auto food quest starts on its own.
- [ ] Throwing food to it works: the companion picks it up and eats it.

## Farming and animals

- [ ] "Look after the farm": the companion harvests and replants for 10 minutes and fills the chest next to the field.
- [ ] Mixed farm with partial seeds: the `plan_farm` text matches what `farm` then does.
- [ ] "Tame that wolf for me": the wolf becomes your pet and follows you, not the companion.
- [ ] "Bring the cows into the pen": cows are led on leads and tied to the fence.
- [ ] "Go fishing" by a river: catches arrive, the rod wears out.
- [ ] Honey from a hive with a campfire below; a hive without one is refused.

## Obtaining items

- [ ] Fresh world, "get me an iron pickaxe": the companion explains the trip, digs down safely, smelts, crafts and hands over the pickaxe; progress events arrive along the way.
- [ ] At a base with iron in a chest: it takes the iron instead of mining.
- [ ] "Get diamonds, but don't wander off" (explore=false): it reports what is missing instead of digging.
- [ ] The first obtain call on a large modpack: note how long the drop index takes (log line "Indexed drops …").

## Building

- [ ] "Build me a small house here": the companion picks a generator, previews the outline (END_ROD for 10 seconds), then builds it block by block.
- [ ] A custom blueprint from the model, such as a well: it reads blueprint_help, previews, then builds.
- [ ] The preview outline is visible only to the owner, not to other players.
- [ ] A chest on the site: the preview lists "the site isn't clear: minecraft:chest at …" and the build is refused.
- [ ] "Save this design": save_blueprint, then list_blueprints shows it and a build_structure by name builds it again.
- [ ] "Build this village house here": list_blueprints with a query finds the template id, then build_structure with "structure" builds the vanilla house.
- [ ] A tall tower (8+ blocks): it is built using scaffolding, and the dirt/cobblestone pillars are removed afterwards.
- [ ] Save and reload the world mid-build: the build resumes where it stopped and finishes.
- [ ] Missing materials with "gather": the companion obtains them with obtain_item instead of failing, then builds.

## Special structures

- [ ] "Build an iron golem" with 4 iron blocks and a carved pumpkin in the bag: the T is built and the golem appears; with no pumpkin the request is refused up front, naming the pumpkin.
- [ ] "Build a snowman" with 2 snow blocks and a carved pumpkin: the snow golem appears.
- [ ] "Make a nether portal" with obsidian and flint and steel: the frame is built and lit; without flint and steel the plan reports it.
- [ ] A fifth golem next to four existing ones is refused (structures.maxNearbySpawned) and the reason is spoken.
- [ ] A site inside spawn protection or on a server claim is refused before anything is placed.
- [ ] Stand in the footprint while it builds: the companion waits or reports the entity in the way, and nobody is moved by force.
- [ ] In the End a nether portal is refused; golems still work.

## Player view

- [ ] Ask a real model: "break the block I'm looking at" / "the one I'm standing on" / from behind a wall (it should say it cannot see you, not guess).

## Pathing

- [ ] Walk around a village: movement looks natural (no jitter on block edges, jumps land).
- [ ] "Mine that tree": the companion pillars up beside a real tree in a forest.
- [ ] A ravine between it and the owner: it bridges over with material it carries or gathers on the way.
- [ ] A hillside with stone behind dirt: it tunnels to the stone and mines it.
- [ ] Two companions pathing at once: no visible server lag.

## Pace

- [ ] Follow on foot, sprint away: the companion runs and drops back to walking; "run" / "slow down" change it.

## Trust by voice

- [ ] Say "Friendai, Bob is my friend now" by voice: the "Trust Bob?" message appears with buttons; say "да" and Bob can make the companion act.
- [ ] Do the same with the [Confirm] button instead of the voice answer; [Cancel] and "нет" each leave trust unchanged.
- [ ] "Friendai, Bob is no longer my friend" asks to stop trusting and only applies after confirmation.
- [ ] Wait a minute without confirming: the request expires, the message says so, and the companion is told.
- [ ] Both messages and buttons read correctly with the game language set to English and to Русский.

## Localization

- [ ] Walk through the settings screen, commands, HUD and key bindings with the game language set to English: no raw keys anywhere.
- [ ] The same with the game language set to Русский.

## Chunk loading

- [ ] The owner walks beyond view distance: the companion keeps working, and `/forceload query` stays unchanged.
- [ ] Stop the server while nobody is near the companion, restart: it resumes its saved task at its last position.
- [ ] Dismiss the companion, restart: no companion chunk is loaded.
- [ ] `companion.chunkLoading.enabled: false` in `config/friendai-server.json`, `/friendai reload`: the companion unloads when the owner walks away.
- [ ] The companion follows the owner through a nether portal: the old dimension's chunk unloads and the new one stays loaded.

## Experience

- [ ] The companion picks up experience orbs near it and its level and bar appear in the companion menu, in English and in Русский.
- [ ] With a real provider, ask the companion to enchant an item at an enchanting table: levels and lapis are spent and the item is enchanted.
- [ ] With a real provider, ask it to repair, combine or rename an item at an anvil: the levels are spent and the result stays in its inventory.
- [ ] With a real provider, ask it to strip an enchanted item at a grindstone: the enchantments go and its experience grows.
- [ ] Save and reload the world: the companion keeps its level and progress.

## Companion skin and name

- [ ] Appearance tab: type a new name and press **Rename**; the name tag, the Control tab and chat replies use it, in English and in Русский.
- [ ] Appearance tab: **Choose a PNG file...** with a real 64x64 skin; the companion changes for you and for a second player on a LAN or server.
- [ ] A skin chosen by Minecraft username (wide and slim accounts) applies with the right arm model.
- [ ] A skin from an `https://` link applies; an `http://` link, a non-image link and a 128x128 image each show an error and change nothing.
- [ ] **Slim** / **Wide** switch the arm model of the current skin; **Reset** restores the default look.
- [ ] Dismiss and summon, then save and reload the world: the skin and the slim flag come back.
- [ ] Worn armor still looks right on a slim companion (known gap: armor keeps wide arm shapes).
- [ ] On macOS and Linux the file dialog opens, or the tab says that no dialog is available.

## Live agent evaluation

- [ ] Run `./gradlew test --tests "*LiveScenarioRunner" -Dfriendai.live=true -Dfriendai.live.config=<path to friendai-server.json>` on the configured provider and read `build/eval/<timestamp>.md`.
- [ ] Compare the report with the previous one: requests per scenario must not grow, and no scenario may start to fail.

## Goals, planner and initiative

- [ ] With a real provider say "build a small house and bring me ten logs": the companion records a goal (check the Mission block in the Debug prompt), a plan is queued and it reports once when done.
- [ ] Make a step fail on purpose (take away the materials): the next event carries facts and a diagnosis, the companion tries one different remedy, and after `agent.maxReplans` failures it asks you for help.
- [ ] Kill the companion with a goal active and let it respawn: the goal is still there.
- [ ] Ask a plain question ("where are you?") with `agent.toolRouting` on: it answers without walking anywhere; ask for work afterwards and it still has its tools (check `request_tools` in the log if it needed one).
- [ ] `agent.autonomy` = `passive`: an idle companion with a goal stays silent; `assistive`: it reports or continues after `agent.goalTickSec`; `autonomous`: with no goal it may ask what to do next but never starts work unasked.
- [ ] Set `agent.planner` to a second profile and confirm the planner call uses it (the main profile sees no planner request).
