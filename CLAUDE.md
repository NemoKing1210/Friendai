@AGENTS.md

# Claude Code specifics

The shared project guide is `AGENTS.md` (imported above) — commands, architecture, rules, testing, git hygiene. This file adds only what is specific to working with Claude Code here.

## Working style

- **Chat with the maintainer in Russian; write everything in the repository in English** (code, comments, docs, commit messages, config defaults). The two never mix: do not put Russian in files except the `ru_ru.json` translations.
- Never add `Co-Authored-By: Claude` or any other attribution to commits or PR descriptions, even if a tool or template suggests it.
- Do not commit `docs/` (git-ignored specs and plans). If asked to write a design or plan, write it under `docs/superpowers/` and leave it uncommitted.
- Destructive git operations (history rewrites, force pushes, deleting branches/worktrees) need explicit approval each time, and may be blocked by the permission classifier. If one is blocked, do not work around it — print the exact command for the maintainer to run.

## Test jar for the maintainer

- After any change to code or resources (fix, improvement, new feature), finish with a build that refreshes `test-builds/friendai-<version>.jar` (`./gradlew assemble` or `build` — `exportTestJar` runs automatically). Bump `mod_version` first when the change calls for it (see "Versioning" in `AGENTS.md`), so the jar name carries the new version. Tell the maintainer the jar path and the new version. The folder is git-ignored; never commit it.

## Environment (Windows)

- Shell: Git Bash and PowerShell are both available. In Bash, spell commands out plainly; compound commands with computed command names or `cd` into other directories can be refused inside a git worktree.
- Gradle must run on JDK 25. If `java -version` shows an older JDK, prefix commands with `JAVA_HOME="<jdk-25 path>"` (a Temurin 25 install is usually under `C:\Program Files\Eclipse Adoptium\`). `org.gradle.java.home` in `~/.gradle/gradle.properties` was tried and was not picked up.
- A full `./gradlew build` prints a lot (the GameTest server log). Redirect it to a file and grep for `error:`, `FAILURE`, `GAME TESTS COMPLETE`, `All .* passed`, or `failed`:

  ```bash
  JAVA_HOME="..." ./gradlew build --console=plain -q > /tmp/build.log 2>&1; grep -n "error:\|FAIL\|failed\|GAME TESTS\|passed" /tmp/build.log
  ```

- `runClient` opens a real window. To smoke-test startup, start it in the background, wait for the title screen, read the log for `Friendai initialized` and exceptions, then kill the `KnotClient` java process. It cannot test the settings screen, voice or real providers.

## Verification habits for this repo

- "Tests pass" means you ran `./gradlew build` and read the result, not that the code looks right. Report the unit-test count and the GameTest line.
- Changing anything in `llm/protocol`, `brain`, or tool handling: re-read rules 3–6 in `AGENTS.md` and make sure the matching regression tests still exist.
- When a Minecraft or Fabric API name does not compile, look it up in the decompiled sources / with `javap` (see `AGENTS.md`), do not guess variants.
- If a GameTest fails only in the full run, suspect cross-test interference in the shared world before suspecting the code under test.

## Where things are

- Entry points: `FriendaiMod` (common), `FriendaiClient` (client), `ModMenuIntegration` (optional).
- Tools and skills: `tool/builtin/FriendaiTools.java`, `task/builtin/`.
- The prompt: `brain/PromptBuilder.java`. The agent loop: `brain/CompanionBrain.java`.
- Speech output: `voice/tts/` (pure), `client/tts/SpeechRuntime.java` (client playback), sent from `chat/CompanionSpeaker.java`.
- Provider presets and routing: `llm/provider/ProviderRegistry.java`.
- Translations: `src/main/resources/assets/friendai/lang/`.
