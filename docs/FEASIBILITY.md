# AzerothDroid — Feasibility Analysis

> Historical: the analysis written before implementation, kept for its reasoning. Parts are out of
> date. The app runs its service in the main process, and it does download client data during setup
> (item 4 below advised against that). See the [README](../README.md) for how it works now.

Goal: an Android app that runs an AzerothCore (WotLK 3.3.5a) + mod-playerbots realm as a
background service, reachable on `127.0.0.1` by a 3.3.5a client running on the same device
(e.g. Winlator / Box64 + Wine + DXVK).

**Verdict: feasible.** Every hard piece has already been proven by someone, just not all
together for AzerothCore on Android.

## What the reference repos prove

| Repo | What it proves | What it does *not* give us |
|---|---|---|
| pocket-realm | A CMaNGOS + Playerbots realm cross-compiles with NDK for arm64, MariaDB runs natively on Android (Termux 12.3.2 build), and a foreground-service supervisor with per-component `android:process` keeps the stack alive next to a Wine client. | Anything AzerothCore. Its realm is CMaNGOS Classic (1.12). Its patches (embedding facade, SQLite lane) are core-specific. Most of its size is the Wine client stack, which we do not need. |
| mod-playerbots | Bots module: ~260k lines of C++ in 665 files. It is plain AzerothCore module code with no exotic dependencies. | It needs the **mod-playerbots/azerothcore-wotlk `Playerbot` branch** fork, not upstream AC. |
| dads-mmo-lab | The WotLK guide is a thin wrapper around AC's own `docker-compose.yml`: `mysql:8.4` + AC build with modules + the `acore/ac-wotlk-client-data` image. That is the reference stack and config to reproduce. | Nothing for Android. Docker cannot run on stock Android. |

## AzerothCore (Playerbot fork) build requirements, checked in source

- CMake ≥ 3.16, C++20 (`ConfigureBaseTargets.cmake`)
- Boost ≥ 1.74: filesystem, program_options, iostreams, regex, thread (thread is needed when modules are present)
- OpenSSL 3, zlib, bzip2. The other deps are vendored (fmt, g3dlite, recast, libmpq, argon2 with sse2neon so ARM is fine)
- MySQL client ≥ 8.0, enforced in `FindMySQL.cmake` through `mysql_config --version`
- `Acore::StartProcess` uses `boost::process` to run the `mysql` CLI for DB auto-updates

AC already builds on aarch64 Linux (Raspberry Pi, ARM Macs), so the CPU architecture is not a
problem. The risk is the Bionic libc and NDK toolchain, and pocket-realm found those issues to be
small: a missing `sys/timeb.h` and `-Werror=format-security`.

## Architecture

```
AzerothDroid.apk (server only, no Wine)
├─ Foreground service ":supervisor"  (specialUse FGS, notification, start/stop/save)
│   ├─ mariadbd     (exec'd from nativeLibraryDir, unix socket + 127.0.0.1:3306)
│   ├─ authserver   (exec'd, 127.0.0.1:3724)
│   └─ worldserver  (exec'd, 127.0.0.1:8085, mod-playerbots linked in)
│        control via SOAP (127.0.0.1:7878) or RA (3443): .account create, .saveall, .server shutdown
└─ UI: status, bot count presets, account creation, logs

Winlator (separate app) → WoW.exe 3.3.5a, realmlist 127.0.0.1
```

Native binaries ship as `lib*.so` in `jniLibs` with `extractNativeLibs=true`. This is the only way
to `exec` on targetSdk ≥ 29, because of the W^X rule on app data. All Android apps share
loopback, so Winlator can reach `127.0.0.1:3724` with no extra work.

MVP choice: **separate executables, not an in-process library.** Pocket-realm patched CMaNGOS
heavily to embed the core as a library. AC's normal `main()` plus signal shutdown plus SOAP
control is enough for v1 and needs almost no core patches.

## Risks, ranked

1. **RAM / Android killing the server (high).** Rough estimates, not measured: worldserver with
   all maps and about 200 bots uses 2–4 GB, MariaDB 0.5–1 GB, the client under Winlator
   1.5–2.5 GB. Target devices have 12 GB or more; 8 GB means fewer bots and lazy map loading.
   Mitigations: a foreground service, and turning off the phantom process killer once over adb:
   `adb shell settings put global settings_enable_monitor_phantom_procs false` (Android 12L+).
   Child processes exec'd by the app count as "phantom" processes and can be killed in the
   background.
2. **Database (medium).** MySQL 8 has no maintained Android build. MariaDB 11.4+ / 12.x does
   (Termux, and pocket-realm already ships 12.3.2). The AC schema is MariaDB-compatible: it uses
   InnoDB everywhere and `utf8mb4_unicode_ci`, and the only 7 tables on `utf8mb4_0900_ai_ci` are
   handled because MariaDB ≥ 11.4.5 aliases that collation. Work needed: get past the
   `FindMySQL` version gate using MariaDB Connector/C (pass `MYSQL_INCLUDE_DIR`/`MYSQL_LIBRARY`
   directly, or use a tiny `mysql_config` shim). Upstream AC does not officially support MariaDB,
   so expect a few SQL edge cases.
3. **Cross-compiling Boost and OpenSSL for NDK (medium, one-time).** These are well-trodden
   paths. Alternative: reuse Termux's prebuilt arm64 packages (boost, openssl, mariadb,
   libmariadb) as the sysroot. Termux builds with the NDK against Bionic, so its `.so` files can
   be shipped inside an APK after RPATH/prefix fixes. Pocket-realm does exactly this for MariaDB.
4. **Client data (low technical, legal care).** dbc, maps, vmaps and mmaps (several GB, mostly
   mmaps) are best extracted **on the Mac from your own client** with AC's extractors and pushed
   with `adb push`. That avoids on-device extraction, which takes hours on a phone. The
   `wowgaming/client-data` zip that AC's docker uses is redistributed Blizzard data, so the app
   should never bundle or download it.
5. **First DB import (low).** The initial world DB is about 850 MB of SQL. Options: (a) let
   `worldserver` auto-setup on device using the bundled `mariadb` CLI (`MySQLExecutable`), which
   is slow but simple; (b) build the DB on the Mac with the same MariaDB version, dump it, and
   import it on device; (c) copy the MariaDB datadir as-is (fastest, needs an exact version match).
6. **Client side is out of scope but must work.** WoW 3.3.5a is 32-bit x86. Winlator (Box64 with
   WoW64 Wine + DXVK) runs it well on Adreno devices. We should still check it on your phone early.

## Proposed phases

0. **Spike in Termux, about 1 day, zero app code.** On the phone: `pkg install mariadb boost
   openssl cmake clang`, build the Playerbot fork natively in Termux, push the data files, run
   the stack, and connect from Winlator. This proves the RAM budget, MariaDB compatibility and
   bot performance on *your* device before any Android plumbing is written.
1. **Cross-build pipeline (Mac → arm64-v8a):** a script that fetches pinned sources (AC fork,
   mod-playerbots, MariaDB) and builds them with NDK + CMake, producing `libworldserver.so`,
   `libauthserver.so`, `libmariadbd.so` and supporting `.so` files.
2. **Android app:** a Kotlin/Compose supervisor FGS that writes configs into app storage, runs
   first-time DB init, starts DB → auth → world in order, health-checks ports, and shuts down
   gracefully through SOAP `.server shutdown` and then `mariadb-admin shutdown`.
3. **Polish:** bot presets (`AiPlayerbot.MinRandomBots`/`MaxRandomBots`, activity throttle),
   account creation through SOAP, log viewer, data import through the SAF folder picker, backups.
