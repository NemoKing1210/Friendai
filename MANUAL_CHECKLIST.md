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

- [ ] `/friedai summon`, then "Friedai, mine 3 logs" next to trees: reply in chat, logs chopped, drops collected, result reported.
- [ ] The same in Russian ("Friedai, добудь 3 дерева"): the companion answers in Russian.
- [ ] Changing the model in the settings screen and saving: the next reply uses the new model (`/friedai status`).

## Voice

- [ ] Settings → Voice → Download `base`: progress toasts, then "Installed".
- [ ] "Test recording" recognizes English speech.
- [ ] "Test recording" recognizes Russian speech.
- [ ] Hold **V** in a world and say "mine two logs": the chat shows the voice line, the companion replies and does it.
- [ ] No microphone / no model: the HUD shows a hint and nothing else breaks.

## Multiplayer

- [ ] LAN with two clients: each player has their own companion; addressing by name only triggers your own.
- [ ] Dedicated server: edit `config/friedai-server.json`, run `/friedai reload` as an operator, behaviour changes.
- [ ] The settings screen on a client connected to a dedicated server shows "These settings are configured on the server."

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

## World lifecycle

- [ ] Companion death: inventory drops and the owner gets a message; `/friedai summon` creates a new one.
- [ ] Leave and rejoin the world: the companion is still there; a Stay task is restored.

## Localization

- [ ] Walk through the settings screen, commands, HUD and key bindings with the game language set to English: no raw keys anywhere.
- [ ] The same with the game language set to Русский.
