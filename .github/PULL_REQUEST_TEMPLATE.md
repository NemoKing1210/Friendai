# Pull request

## What changed

<!-- The packages, classes or player-visible behavior this touches, with paths where useful. -->

## Why

<!-- The request, bug or TODO.md item this closes. Say it here if the change needs no version bump. -->

## Tests

<!-- The group you actually ran and its real result, e.g.
     `./gradlew test --tests 'dev.friendai.<package>.*'` — 42 passed.
     A GameTest: the class glob, the count and the "N GAME TESTS COMPLETE" line.
     State plainly that the full suite is left to CI. -->

## Version and test jar

- `mod_version`:
- Test jar: `test-builds/friendai-<version>.jar`

## Not verified

<!-- Real providers, voice, GUI, LAN, both UI languages — anything a reviewer must not assume. -->

## Checklist

- [ ] `./gradlew assemble verifyModArtifact` compiled every source set and refreshed the test jar
- [ ] The test group covering this change was run; the full suite was left to CI
- [ ] New player-facing strings exist in `en_us.json` and every shipped locale
- [ ] `ARCHITECTURE.md`, `site/app.js` + `site/i18n.ru.js` and `CHANGELOG.md` are updated where this change shows
- [ ] `mod_version` is bumped by the size of the change, or "Why" says why no bump is needed
- [ ] `./gradlew spotlessApply -PspotlessFiles=<changed files>` ran on the changed Java files
- [ ] The diff holds no secrets, debug prints, TODOs or another session's changes
- [ ] A new GameTest class is registered in `fabric.mod.json` and `.github/gametest-shards.json`
