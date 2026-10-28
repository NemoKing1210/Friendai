# Security policy

Friendai is a pre-1.0 open-source mod, developed and maintained in the open. This file says which
versions get security fixes, how to report a problem privately, and what the mod is designed to
guarantee so a report can be judged against it.

## Supported versions

| Version | Supported |
|---|---|
| The newest release, and `main` | Yes — security fixes land there and ship in the next release |
| Any older release, or a build for another Minecraft version | No |

The mod targets Minecraft **26.3** only. Supported releases are built from `main`; there are no
backports to older Minecraft versions. Before reporting, check the version in the jar name or in
**Mod Menu → Friendai** and, when possible, reproduce on the newest release.

## Reporting a vulnerability

**Do not open a public issue with reproduction steps for a live vulnerability.**

1. Use GitHub's private vulnerability reporting on this repository: **Security → Report a
   vulnerability** (`https://github.com/NemoKing1210/Friendai/security/advisories/new`). The report
   and the discussion stay private until an advisory is published.
2. If that form is unavailable, open a public issue that only asks for a private channel. Do not
   put the details, a proof of concept or affected configuration in it.

A useful report contains:

- the mod version, Minecraft and Fabric Loader versions, and the operating system;
- whether the mod runs on a client, a dedicated server, or both, and whether the game is
  singleplayer, LAN or a public server;
- what an attacker can do that they should not, and what they need to start with (an ordinary player
  in hearing range, a trusted player, the owner, operator, console);
- the smallest reproduction you have, and the relevant log lines with every key, token and
  personal detail removed.

**Never send API keys, session tokens, world files or player data.** A key pasted into an issue or a
report must be treated as leaked and rotated at the provider.

There is no SLA and no bug bounty: reports are handled on a best-effort basis by the maintainer. You
will get an acknowledgement when the report is read, an assessment once the cause is understood, and
credit in the advisory unless you ask to stay anonymous. Please give a reasonable window — for
example 90 days — before publishing, or agree on a date with the maintainer.

Test only against servers, worlds and accounts you own or have explicit permission to use. Do not
destroy other players' builds, exfiltrate their data or run a test that costs the provider money
without saying so in the report.

## What is in scope

The mod's own code. Examples of issues worth reporting:

- a guest or a trusted player reaching an owner-only capability: creative mode, the
  `execute_command` allowlist, another player's memory, instruction-skill editing, trading budgets
  or queue control;
- a client reading operator-only data — the brain log, the current prompt, perception text, debug
  actions — without being an operator;
- an API key, another player's private text or a configured secret appearing in a log, chat, a
  network payload, an exception message or an error sent to the client;
- tool arguments or an LLM reply escaping validation: a malformed or hostile reply causing an
  exception instead of an error result, path traversal outside `<world>/friendai/`, or a crash
  reachable by a player who can talk to the companion;
- a client payload granting rights the sender does not have, or rights decided on the client
  instead of the server;
- a downloaded model, skin or blueprint accepted despite a failed integrity or host check.

## What is not a vulnerability

- The model's wording, tone, refusals, hallucinations, or it refusing a legitimate request. The LLM
  is an untrusted advisor; access is enforced by the server, not by the prompt.
- Provider charges from ordinary use. Chat and voice reach the companion, and the companion reaches
  the LLM, by design. `chat.hearingRadius`, trust and the provider settings control that cost.
- Anything that needs operator, console or filesystem access on the server, a modified server jar,
  or a client and server that do not both run the mod. That player can already edit the world, read
  the config and read the world files.
- Prompts, memory and world state reaching the LLM endpoint **you** configured, recordings reaching
  the speech provider **you** selected, and reply text reaching a cloud speech provider **you**
  selected. These are the documented data flows, not a leak.
- Vulnerabilities in Minecraft, Fabric Loader/API, YACL, Mod Menu, whisper.cpp, sherpa-onnx, Java or
  an LLM/speech provider. Report those upstream. Tell us anyway if Friendai's use of one of them is
  unsafe.

## Security model

### Trust and access

- Both sides must run the mod, and the **server is authoritative**. The caller of a turn is resolved
  from the connection and the owner's book, never from a field in the payload.
- `AccessLevel` is ordered `GUEST < TRUSTED < OWNER`. Every tool has a minimum level (`TRUSTED` by
  default), and the registry filters both the advertised specs and every call before it executes,
  so a guest cannot run a tool by naming it.
- Owner-only: creative mode, the restricted `execute_command` allowlist, instruction skills,
  description and owner profile. Operator-only (`gamemaster`, or the single-player world owner):
  the brain log, prompt, perception text and debug actions.
- Memory is per owner and per subject: `MemoryPolicy` decides read, write and forget, a guest sees
  only facts about themself, and trust changes need the owner's confirmation.
- Placements and right-clicks run through a Fabric fake player, and the world-changing checks ask the
  level for permission with that player (`mayInteract`), so vanilla protection, spawn protection and
  region or claims plugins see an ordinary player. Creative mode does not bypass those checks.
- LLM output is untrusted input. Tool calls are validated against their schema, filtered by access,
  and a malformed argument becomes an error result, never an exception. Facts, skills, chat text
  and blueprints cannot raise a tool's access level.

### Secrets

- API keys are configured as `"apiKey": "env:NAME"` or as a literal. The LLM key belongs to the
  server config (`config/friendai-server.json`); cloud speech keys belong to the client config
  (`config/friendai-client.json`). Prefer the environment reference: a literal key in a config file
  is plaintext on disk, readable by anyone who can read that file.
- Keys travel only in HTTP headers, never in a URL, and never appear in logs, chat, exception
  messages or `toString()` of a profile or endpoint. Known secrets are removed from the debug log,
  from prompts and from exported text; the settings screen shows a fixed placeholder instead of a
  key.

### Data that leaves the machine

- **LLM:** the prompt and the conversation with it — the owner's message, the facts and goals in the
  memory book, the description and skills, and the nearby world and player context the prompt
  carries — go to the provider configured on the server, and the LLM key is the server's.
  Client-side keys are used only for cloud speech.
- **Speech recognition:** the recording is uploaded only when a cloud recognition provider is
  selected. Local Whisper keeps the audio on the machine.
- **Speech synthesis:** reply text is uploaded only when a cloud synthesis provider is selected.
  Local Piper voices send nothing. Voice keys live in the client config.
- **Skins:** a skin is fetched from Mojang by username, or from a URL the owner gave, without
  following redirects, with a size cap and a texture-host allowlist.
- **Model downloads:** Whisper and Piper models are fetched over HTTPS and verified against a pinned
  SHA-256 before use.

The mod opens no listening network socket: all traffic is outbound HTTP(S).

### Data stored on the server

Per-owner books under `<world>/friendai/` (memory, places, knowledge atlas, trading notes,
blueprints, skills, placed blocks) and the config files are plain files with no encryption. On a
server with other people, the owner's facts are as private as the server's filesystem: an operator
with file access can read them. Writes are atomic (temp file and move) with a `.bak` copy on
corruption, so a crash does not truncate a book.

## Hardening checklist for server owners

- Keep the LLM key in the environment (`"apiKey": "env:NAME"`) and restrict who can read the config
  file.
- Trust players deliberately (`/friendai trust`); `GUEST` is the default for everyone else, and the
  default tool minimum is `TRUSTED`.
- Keep `chat.hearingRadius` at what the game needs. Every message inside it, and every reply, is an
  LLM request.
- Grant operator to people you trust with the brain log, the prompt and the debug tools.
- Turn creative mode and `execute_command` on only for owners who understand them: both stay
  owner-only, creative still honours vanilla protection, and a command runs only with the owner's
  own command permission.
