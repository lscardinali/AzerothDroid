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
| mod-autobalance | No licence file. `src/AutoBalance.cpp` carries a GPL notice, copied to `assets/licenses/`; the other files have no notice. | [azerothcore/mod-autobalance](https://github.com/azerothcore/mod-autobalance) |
| mod-individual-progression | MIT (`LICENSE`), but 37 source files carry AGPL-3.0 headers | [ZhengPeiRu21/mod-individual-progression](https://github.com/ZhengPeiRu21/mod-individual-progression) |

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

## Game client runtime (`wine/` module)

The `wine/` module's Kotlin and Java source is in this repository, taken from WoWForeverywhere
1.0.8. Its native libraries and runtime come prebuilt from release APKs pinned by SHA-256 in
`tools/stage_wine.py`. Because this module is GPL-3.0, an APK built from this repository is
distributed under GPL-3.0 as a whole.

| Component | Licence | Source |
|---|---|---|
| WoWForeverywhere (Java/Kotlin in `wine/src`, native libraries, Proton and Turnip packages) | GPL-3.0 | [YoruDev-Ryland/WoWForeverywhere](https://github.com/YoruDev-Ryland/WoWForeverywhere) v1.0.8 |
| GameNative (the engine WoWForeverywhere is built on; FEXCore and DXVK packages) | GPL-3.0 | [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative) v1.4.0 |
| Winlator (`com.winlator` code) | LGPL-2.1 | [brunodev85/winlator](https://github.com/brunodev85/winlator) |
| libredirect (`libredirect-bionic-wx.so`, `redirect.tzst`) | Proprietary, closed source, by GameNative's maintainers; runs only inside the Wine processes | GameNative |
| Wine / Proton 11 (arm64ec) | LGPL-2.1-or-later | [ValveSoftware/Proton](https://github.com/ValveSoftware/Proton), [WineHQ](https://www.winehq.org/) |
| FEX-Emu (FEXCore 2605) | MIT | [FEX-Emu/FEX](https://github.com/FEX-Emu/FEX) |
| Box64 / wowbox64 | MIT | [ptitSeb/box64](https://github.com/ptitSeb/box64) |
| DXVK 2.4.1 and its gplasync patches | Zlib | [doitsujin/dxvk](https://github.com/doitsujin/dxvk), [Ph42oN/dxvk-gplasync](https://gitlab.com/Ph42oN/dxvk-gplasync) |
| Mesa Turnip (Adreno Vulkan driver builds) | MIT | [mesa3d](https://gitlab.freedesktop.org/mesa/mesa) |
| PulseAudio | LGPL-2.1-or-later | [pulseaudio](https://www.freedesktop.org/wiki/Software/PulseAudio/) |
| zstd-jni, Apache Commons Compress, XZ for Java, OkHttp, AndroidSVG, Timber, Compose Settings | BSD-2-Clause (zstd-jni), Apache-2.0 (the rest), XZ for Java public domain | Maven Central |

The controller layout follows [WoWpadX](https://github.com/leoaviana/WoWpadX) (MIT) and the
calibration format of [ConsolePortLK](https://github.com/leoaviana/ConsolePortLK) (Artistic-2.0);
neither is included in the APK.

## Not included: game client and client data

No Blizzard file is in this repository or in the APK. During setup, at the user's request, the
app downloads a data pack (DBC, maps, vmaps, mmaps) from
[wowgaming/client-data](https://github.com/wowgaming/client-data), the same release AzerothCore's
own installer uses. That data is derived from World of Warcraft client files, which belong to
Blizzard Entertainment and are not covered by any licence listed here.
