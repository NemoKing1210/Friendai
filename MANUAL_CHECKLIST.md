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
- [ ] Changing the model in the settings screen and saving: the next reply uses the new model (`/friendai status`).
- [ ] "Friendai, drop all your cobblestone and then mine 3 logs": the companion sends one run_plan with both steps and reports the plan result.
- [ ] A plan that cannot finish (e.g. mine a block that is not nearby): the companion explains the failed step and proposes or sends a new plan.

## Voice

- [ ] Settings → Voice → Download `base`: progress toasts, then "Installed".
- [ ] "Test recording" recognizes English speech.
- [ ] "Test recording" recognizes Russian speech.
- [ ] Hold **V** in a world and say "mine two logs": the chat shows the voice line, the companion replies and does it.
- [ ] No microphone / no model: the HUD shows a hint and nothing else breaks.

## Multiplayer

- [ ] LAN with two clients: each player has their own companion; addressing by name reaches your own companion when it is within 16 blocks, otherwise the nearest other companion.
- [ ] A guest next to another player's companion chats with it; asking it to act is politely declined.
- [ ] `/friendai trust <guest>`: the guest's request now runs; `/friendai untrust` takes effect on the next message.
- [ ] `/friendai trusted` and the trust messages read correctly in English and Russian.
- [ ] Restart the server: the trusted list survives (`<world>/friendai/memory/<uuid>.json`).
- [ ] A guest spamming the companion is throttled; the owner is not.
- [ ] Dedicated server: edit `config/friendai-server.json`, run `/friendai reload` as an operator, behaviour changes.
- [ ] The settings screen on a client connected to a dedicated server shows "These settings are configured on the server."

## Memory

- [ ] With a real model: tell the companion your name and where your base is; it calls `remember`, and after a server restart it still knows both.
- [ ] A guest tells the companion something about themselves; `/friendai memory list` shows it; the guest cannot make it remember things about the owner.
- [ ] A guest asks "what do you know about my friend's base?": the companion does not reveal the owner's or world facts.
- [ ] `/friendai memory forget <id>` and `/friendai memory clear world` work; messages read correctly in English and Russian.
- [ ] Settings → Memory → off, then `/friendai reload`: the companion no longer remembers; `/friendai memory` says memory is off.

## Companion control menu

- [ ] Esc in a world: the companion icon sits in the icon row next to the other icons, has a tooltip, and looks right at GUI scales 1-4 and in both languages.
- [ ] The button opens the menu; Done returns to the pause menu; Esc closes it.
- [ ] Control tab: Summon, Dismiss and Stop work and the status block follows them within about half a second.
- [ ] Tasks tab: a finished task shows up in the history with its status; scrolling works.
- [ ] Log tab (operator): messages, tool calls and errors appear live; kind filters work; Copy puts the visible lines on the clipboard.
- [ ] Debug tab (operator): perception text is shown; "Show prompt" shows the system prompt; Send reaches the companion; Run executes a tool (empty arguments = `{}`) and shows up in the log as "Manual".
- [ ] Dedicated server as a normal player: only Control and Tasks tabs; as an operator: all four.
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

## World lifecycle

- [ ] Companion death: inventory drops and the owner gets a message; `/friendai summon` creates a new one.
- [ ] Leave and rejoin the world: the companion is still there; a Stay task is restored.

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
- [ ] PvP server: "attack <player>" from the owner works when PvP is on and is refused with "PvP is off here" when the pvp gamerule is false; a trusted non-owner cannot order it.

## Hunger

- [ ] A long session of mining and fights: the companion eats on its own and never starves.
- [ ] Empty inventory with the owner near: it asks the owner for food instead of wandering off.
- [ ] Empty inventory with the owner far away: it goes to get food by itself.
- [ ] Starving with no food and no reaction: the auto food quest starts on its own.
- [ ] Throwing food to it works: the companion picks it up and eats it.

## Farming and animals

- [ ] "Look after the farm": the companion harvests and replants for 10 minutes and fills the chest next to the field.
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

## Localization

- [ ] Walk through the settings screen, commands, HUD and key bindings with the game language set to English: no raw keys anywhere.
- [ ] The same with the game language set to Русский.
