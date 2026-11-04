# AzerothDroid

Run a Wrath of the Lich King (3.3.5a) realm full of bots on an Android phone, with no PC or server.

AzerothDroid is a port of [AzerothCore](https://www.azerothcore.org/) and its
[Playerbots fork](https://github.com/mod-playerbots/azerothcore-wotlk) to Android, for offline play
alongside [wow-mobile](https://github.com/marcocastignoli/wow-mobile) or a similar 3.3.5a client on
Android. It packages the servers,
[mod-playerbots](https://github.com/mod-playerbots/mod-playerbots) and MariaDB as one app. Start the
realm, then play alone or with friends on the same network. Bots level, quest and log in and out
around you, close to your level, so the world feels populated.

> **Early project.** Tested and working on a Redmi A5 with a 3.3.5a client running on a PC, and on
> the arm64 emulator. I'm waiting on a Retroid Pocket 6 to test next. A client on the same device is
> the goal but hasn't been tested. Controller navigation hasn't been tested on a real device either.

> **Unofficial.** Not affiliated with or endorsed by Blizzard Entertainment, AzerothCore or
> mod-playerbots. You need your own legally obtained game client; none is included.

## Features

- **Self-contained realm:** database, login and world servers run as native processes under one
  foreground service, started and stopped with a single button.
- **Bots built for one player:** defaults keep bots near your level and on your continent, with a
  few above and below, across both factions. They rotate in and out rather than standing idle.
- **Tuned for low-memory phones:** patches stop idle bots and creatures loading map grids they
  don't need. Around 12 MB per online bot, including the grids it loads.
- **Careful with data and battery:** players are saved every two minutes, when memory runs low and
  on shutdown. The world server freezes when nobody has played for 6 minutes, and a login wakes it.
- **Optional modules, off by default:** dungeon scaling for small groups (mod-autobalance), and moving
  through Vanilla, TBC and WotLK content in order (mod-individual-progression). Turn them on in
  Settings. Installing individual progression can't be undone: its first start rewrites the world
  database, and turning it off later only stops its rules. The only way back is to uninstall the app,
  which also deletes your characters and the client data.
- **In-app management:** accounts, server rates, bot settings and live logs, all usable with a
  game controller.

## Requirements

- Android 8.0 or later on an arm64 device
- About 4 GB free storage, plus the database, and a 1.2 GB download during setup
- A World of Warcraft 3.3.5a client (build 12340) on this phone or on your network

## Getting started

Install the APK from [Releases](https://github.com/lscardinali/AzerothDroid/releases), or
[build it from source](#building-from-source). To get updates automatically, add
`https://github.com/lscardinali/AzerothDroid` as an app in
[Obtainium](https://github.com/ImranR98/Obtainium). Updates keep your realm's data. The app isn't on
the Play Store and won't be.

1. Open the app and tap **Download and install** to fetch the client data.
2. Tap **Start realm**. The first start imports the database and takes the longest.
3. Create an account in the **Accounts** tab.
4. Point your client's `realmlist.wtf` at the address shown on the **Realm** tab:
   - `set realmlist 127.0.0.1` for a client on the same phone
   - `set realmlist <phone's IP>` for a client on another device
5. Log in with the new account.

**Using wow-mobile?** On first launch it sets the realmlist to `logon.therawow.com`, a public server.
Change it to `127.0.0.1` in its WoW Settings to play on your own realm.

**Other Android launchers:** running the client through GameNative or GameHub should also work, using
the same realmlist, though neither has been tested.

Android may kill background processes. On Android 12L or later, turning off its phantom process
monitor helps:

```bash
adb shell settings put global settings_enable_monitor_phantom_procs false
```

## Building from source

You need Python 3, Git, a JDK (Gradle downloads the version it builds with), and an Android SDK with
NDK, CMake and platform 37. The project is developed on macOS; other hosts may need changes. If your
SDK isn't in
`~/Library/Android/sdk`, set `ANDROID_HOME`.

Download the MariaDB runtime:

```bash
python3 tools/stage_mariadb.py
```

Cross-compile AzerothCore and mod-playerbots, which takes a long time on the first run:

```bash
python3 tools/build_azerothcore.py
```

Build the app and install it on a connected device:

```bash
./gradlew :app:installDebug
```

`installDebug` keeps the app debuggable, which `tools/push_data.py` needs. Or let GitHub build it: run
the **Build APK** workflow from the Actions tab, or push a `v*` tag to publish a release. It
takes about an hour, and the optimized APK and its source are attached to the run as artifacts.

Everything the scripts produce goes into the ignored `native/` subfolders `src`, `out`, `.build`
and `.cache`.

## How it works

```
Android app (Kotlin, Compose)
└─ Foreground service: starts, watches and restarts the servers, and pauses the world when idle
   ├─ mariadbd     127.0.0.1:3306
   ├─ authserver   0.0.0.0:3724
   └─ worldserver  0.0.0.0:8085, with SOAP control on 127.0.0.1:7878
```

Android only lets apps execute files from their native library directory, so the servers ship as
`lib*.so` files in the APK. Termux's Android builds supply MariaDB and the libraries.

Upstream source isn't stored here. `tools/azerothcore.lock.json` pins exact commits and
`native/patches/` holds our changes:

- `native/patches/azerothcore/`: Android (Bionic) build fixes and memory reductions
- `native/patches/mod-playerbots/`: bot placement, level brackets and memory reductions

## Network and security

This is built for a trusted home network. **Don't expose it to the internet.**

- The login and world ports are open to your local network while the realm runs.
- The database uses AzerothCore's default credentials and listens on loopback, so other apps on
  the same phone could reach it.
- The app keeps a hidden GM account for its own SOAP commands, with a random password in private
  app storage.

## Staying up to date with upstream

`.github/workflows/upstream-updates.yml` runs every Monday and looks for new commits on the
`Playerbot` branch and in mod-playerbots:

- **Patches still apply:** it opens a pull request that moves the pins.
- **A patch conflicts:** it opens an issue naming the patch.

Nothing merges by itself, because patches applying doesn't prove the game still works. Before
merging, rebuild and play. To run the same check by hand:

```bash
python3 tools/update_upstream.py --check
```

Without `--check`, the script also updates the lockfile. MariaDB and the Termux libraries are pinned
separately and updated by hand with `python3 tools/stage_mariadb.py --update`.

### Updates and your data

Installing a new APK over the old one keeps your accounts, characters and settings, as long as both
are signed with the same key. On the next start, AzerothCore's own updater applies any new SQL files
to the existing databases and records them in an `updates` table, as it does on any AzerothCore
server. Uninstalling the app deletes the realm, so export characters first.

The exception is a new MariaDB version. The app doesn't ship `mariadb-upgrade`, so moving an existing
database to a new major MariaDB version isn't handled. Keep the pin unless you've tested that upgrade.

## Contributing

Pull requests are welcome. Test results from other devices are especially useful, so please include
your device model and RAM. Changes to AzerothCore or mod-playerbots go in `native/patches/`, as
patches against the pinned commits.

## Credits

AzerothDroid is packaging and tuning around other people's work. The game server is theirs:

- [**AzerothCore**](https://github.com/azerothcore/azerothcore-wotlk) is the WotLK server, built on
  the work of the MaNGOS, ScriptDev2 and TrinityCore projects. See its
  [AUTHORS](https://github.com/azerothcore/azerothcore-wotlk/blob/master/AUTHORS) file.
- [**mod-playerbots**](https://github.com/mod-playerbots/mod-playerbots) provides the bots, and its
  [AzerothCore fork](https://github.com/mod-playerbots/azerothcore-wotlk) is the core used here.
- [**mod-autobalance**](https://github.com/azerothcore/mod-autobalance) and
  [**mod-individual-progression**](https://github.com/ZhengPeiRu21/mod-individual-progression) provide
  the optional modules.
- [**MariaDB**](https://mariadb.org/) is the database.
- [**Termux**](https://termux.dev/) supplies the Android builds of MariaDB and the libraries.
- [**wowgaming/client-data**](https://github.com/wowgaming/client-data) hosts the data pack that
  AzerothCore's installer uses.
- [**pocket-realm**](https://github.com/puzzled-pancake/pocket-realm) showed that a playerbots realm
  can run on Android. It's a separate project, built on CMaNGOS Classic rather than AzerothCore.
- [**dads-mmo-lab**](https://github.com/DadsMmoLab/dads-mmo-lab) was a reference for the server
  stack.

Please report problems with this app here rather than to those projects, unless you can reproduce
them on an unmodified server.

## Licence

Copyright (C) 2026 Lucas Salton Cardinali. AzerothDroid's own code is licensed under the
[GNU General Public License, version 2 or later](LICENSE). Patches in `native/patches/` are under
the licence of the files they change, which is GPL-2.0 for mod-playerbots.

An APK contains third-party software under its own licences; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). If you distribute an APK, read
[docs/RELEASING.md](docs/RELEASING.md) first.

World of Warcraft and Wrath of the Lich King are trademarks of Blizzard Entertainment, Inc.
