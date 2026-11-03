# Third-party notices

This repository holds AzerothDroid's own code, its build scripts, and patches. It contains no
third-party source apart from the Gradle wrapper. The build downloads the components below at
pinned versions (`tools/azerothcore.lock.json`, `tools/termux.lock.json`), and an APK built from
this repository contains them. `tools/stage_licenses.py` copies these terms into every APK, under
`assets/licenses/`.

Standard licence texts are in [`LICENSES/`](LICENSES). Each component's own licence and
copyright notices come from its source or package.

## Game server

| Component | Licence | Source |
|---|---|---|
| AzerothCore, `Playerbot` branch | GPL-2.0 (`LICENSE`). Source headers are mostly GPL-2.0-or-later; 15 files are AGPL-3.0-or-later. Copyright holders are listed in its `AUTHORS` file. | [mod-playerbots/azerothcore-wotlk](https://github.com/mod-playerbots/azerothcore-wotlk), a fork of [azerothcore/azerothcore-wotlk](https://github.com/azerothcore/azerothcore-wotlk) |
| mod-playerbots | GPL-2.0 | [mod-playerbots/mod-playerbots](https://github.com/mod-playerbots/mod-playerbots) |

Compiled into the servers from AzerothCore's `deps/` directory:

| Component | Licence |
|---|---|
| Argon2 | CC0-1.0 or Apache-2.0 |
| {fmt} | MIT, with an exception for compiled forms |
| fkYAML | MIT |
| G3D (g3dlite) | BSD-style |
| gSOAP | GPL, one of its two licence options |
| Recast Navigation | Zlib |
| SFMT | BSD-3-Clause |
| utf8-cpp | BSL-1.0 |

## Database and libraries

Prebuilt for Android by [Termux](https://github.com/termux/termux-packages) and downloaded from
its package repository:

| Component | Version | Licence |
|---|---|---|
| MariaDB server and client | 13.0.2 | GPL-2.0 |
| MariaDB Connector/C (`libmariadb`) | with MariaDB | LGPL-2.1-or-later |
| OpenSSL, including its legacy provider | 3.6.5 | Apache-2.0 |
| GNU Readline | 8.3.6 | GPL-3.0 |
| Boost, linked statically | 1.92.0 | BSL-1.0 |
| LLVM libc++ | NDK build, Termux package 30 | Apache-2.0 WITH LLVM-exception; Termux's package metadata lists NCSA, which older LLVM code uses |
| bzip2 | 1.0.8 | bzip2-1.0.6 |
| libandroid-support | 29 | MIT |
| libcrypt | 0.2 | BSD-style |
| libedit | 20260512-3.1 | BSD-3-Clause |
| liblzma (XZ Utils) | 5.8.4 | 0BSD |
| ncurses | 6.5 | MIT-style |
| PCRE2 | 10.49 | BSD-3-Clause, with an exception for compiled forms |
| zlib | 1.3.2 | Zlib |

## Android app

| Component | Licence |
|---|---|
| AndroidX and Jetpack Compose | Apache-2.0 |
| Kotlin standard library | Apache-2.0 |
| Gradle wrapper, included in this repository | Apache-2.0 |

## Not included: game client and client data

No Blizzard file is in this repository or in the APK. During setup, at the user's request, the
app downloads a data pack (DBC, maps, vmaps, mmaps) from
[wowgaming/client-data](https://github.com/wowgaming/client-data), the same release AzerothCore's
own installer uses. That data is derived from World of Warcraft client files, which belong to
Blizzard Entertainment and are not covered by any licence listed here.
