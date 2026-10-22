# Friedai

An open-source Fabric mod that adds an AI companion to Minecraft. Talk to it in chat or by voice, and it will follow you, fetch items, walk to places and mine blocks for you.

> "Friedai, mine 10 logs" — the companion answers in chat, walks to the trees, chops them, picks up the drops and reports back.

## Requirements

- Minecraft **26.3**
- [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api)
- Java **25**
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu) and [YACL](https://modrinth.com/mod/yacl) for the in-game settings screen. Without them the mod works and settings are edited in the config files.

The mod must be installed on both the client and the server (the integrated server in singleplayer counts).

## Quick start

1. Install the mod and set up a provider (see below).
2. In a world, run `/friedai summon`. Your companion appears next to you.
3. Talk to it:
   - in chat with a prefix: `@ come here`
   - in chat by name: `Friedai, mine 10 logs`
   - by voice: hold **V** (push-to-talk, rebindable in Controls) and speak.
4. `/friedai stop` cancels what it is doing, `/friedai dismiss` sends it away, `/friedai status` shows the provider and current task.

What it can do: follow you, stay, go to a point, pick up items, give you items, look around, show its inventory, stop, and mine N blocks of a kind.

## Setting up a provider

Open **Mod Menu → Friedai → Provider**, or edit `config/friedai-server.json`. On a dedicated server edit the file and run `/friedai reload`.

| Preset id | Service | Notes |
|---|---|---|
| `openai` | OpenAI | Responses API |
| `openai_compat` | Any OpenAI-compatible endpoint (OpenRouter, Groq, DeepSeek, LM Studio, …) | `baseUrl` is required |
| `anthropic` | Anthropic | |
| `gemini` | Google Gemini | |
| `ollama` | Ollama (native API) | no API key |
| `opencode_zen` | OpenCode Zen | routes by model name |
| `command_code` | Command Code | routes by model name |

Keep API keys out of the file by referencing an environment variable:

```json
"apiKey": "env:ANTHROPIC_API_KEY"
```

The key is never written to logs or chat. "Test connection" in the settings screen checks that the provider answers and that the model supports tool calling.

## Voice

Voice input runs locally with [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Open **Settings → Voice**, pick a model (`tiny`, `base` or `small`) and press **Download model**. Models are stored in `<game dir>/friedai/models/`. Speech is turned into text on your machine; only the text is sent to the server.

## Languages

The interface is available in English and Russian. The companion replies in the language you write in, or in a fixed language if you set one. To add a language, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Building

```bash
./gradlew build        # compile, unit tests and in-game tests
./gradlew test         # unit tests only
./gradlew runGameTest  # Fabric GameTests only
./gradlew runClient    # start a development client
```

## License

[MIT](LICENSE)
