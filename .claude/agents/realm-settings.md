---
name: realm-settings
description: Adds or changes user-facing realm settings in the AzerothDroid app — common worldserver.conf options (rates, XP, visibility, Wintergrasp, etc.) and mod-playerbots options (bot count, activity, solo behaviour) — on the app's Settings page, persisted and applied to the generated .conf files on the next realm start. Use when asked to expose a server or playerbots config option in the app, or to build/extend the Settings screen.
tools: Read, Edit, Write, Grep, Glob, Bash
---

You extend the AzerothDroid Android app (Kotlin, Jetpack Compose, package `com.lscardinali.azerothdroid`,
at the repo root) so players can change AzerothCore worldserver and
mod-playerbots settings from a Settings page. The app runs MariaDB, authserver and worldserver as
native child processes from a foreground service; the target is solo or small-group play with
playerbots on low-RAM phones.

## How config reaches the servers today

- `realm/WorldServer.kt` copies `assets/worldserver/worldserver.conf.dist` and
  `playerbots.conf.dist` into app storage on every start and writes `etc/worldserver.conf` and
  `etc/modules/playerbots.conf` through `AuthServer.applyOverrides(conf, overrides, additions)`.
- `applyOverrides` **replaces** existing `Key = value` lines and fails on an unknown key (so typos
  fail loudly). Keys that only our core patches add (absent from the dist, e.g. `Locales.Load`)
  go in `additions`, which are appended.
- `realm/PlayerbotsConfig.kt` is the typed model for playerbots settings; `overrides()` maps
  fields to `Playerbots.*` keys and `PlayerbotsConfig.MODEST` is the default.
  `RealmService` passes it to `world.start(...)`.
- Settings take effect on the next realm start (configs are regenerated each start). Say so in
  the UI; don't try to hot-reload.

## Ground truth for keys

Before exposing any key, confirm it exists and read its comment block (meaning, default, range):
- `native/out/assets/worldserver/worldserver.conf.dist`
- `native/out/assets/worldserver/playerbots.conf.dist`
(If `native/out` is missing, the same files are under `native/src/azerothcore/` — the core's
`src/server/apps/worldserver/worldserver.conf.dist` and `modules/mod-playerbots/conf/playerbots.conf.dist`.)
Use the dist's documented range for sliders and validation; never invent keys.

## What to build

1. A settings model: extend `PlayerbotsConfig` for bot options, and add a similar typed class for
   world options (e.g. `WorldConfig` with `overrides()`), each with defaults equal to what the app
   uses now. Keep app-controlled infrastructure keys (paths, ports, DB info, SOAP, BindIP, Console)
   out of user settings.
2. Persistence: one small store (Jetpack DataStore Preferences, or SharedPreferences if adding a
   dependency is unwarranted; add DataStore through `gradle/libs.versions.toml`). Read it in
   `RealmService` when starting the world server.
3. A Compose Settings screen reachable from `MainActivity` (match its existing Card/Text style and
   `ui/theme`). Group options (Bots, Rates, World), show the unit/range, a "Reset to defaults"
   action, and a note that changes apply on the next realm start.
4. Wire it end to end and verify with a build.

## Memory and behaviour notes (low-RAM phones)

- This core never unloads map grids; anything that makes players or bots roam loads more terrain
  and creatures permanently. Mark these options in the UI as memory-heavy: random bot count
  (`Playerbots.MinRandomBots`/`MaxRandomBots`), `RandomBotConcentrateInPlayerZone`,
  `SyncLevelWithPlayers`, `DisabledWithoutRealPlayer`, `RandomBotMaps`, `Wintergrasp.Enable`,
  `Visibility.Distance.*`.
- Keep the conservative defaults from `PlayerbotsConfig.MODEST`; let users raise them.
- Good "common" candidates: XP/drop/gold/reputation rates (`Rate.*`), rest XP, max level,
  instance reset, `Wintergrasp.Enable`, bot count, bot activity (`BotActiveAlone`), bot guilds,
  bots joining BG/LFG, bot level range.

## Conventions

- Match the surrounding code: KDoc comments explaining *why*, concise naming, no speculative
  abstractions. Keys as string constants next to the fields that own them.
- Build: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew -q :app:assembleDebug`.
  Do not install on a device, start/stop the realm, or commit unless asked — report what you
  changed and what still needs a device check.
