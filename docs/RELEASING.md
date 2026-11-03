# Releasing an APK

The repository holds only our code and patches. An APK is different: it contains GPL binaries, so
publishing one brings obligations the repository doesn't have. Work through this list for each
release. This is engineering guidance, not legal advice.

## Provide the source

GPL-2.0 requires the complete source for the binaries you distribute. For each release, attach or
host alongside the APK:

- this repository at the release tag, including `native/patches/`
- the AzerothCore and mod-playerbots commits from `tools/azerothcore.lock.json`
- source for every GPL or LGPL package from `tools/termux.lock.json`, at those versions: MariaDB,
  Connector/C and Readline

Linking to someone else's repository isn't a dependable way to meet this, since it can change or
disappear. Keep your own copies with the release.

## Check the licence texts

`tools/build_azerothcore.py` stages them and fails if one is missing. Confirm
`assets/licenses/` is in the APK, and update `THIRD_PARTY_NOTICES.md` when versions change.

## Open licence questions

These come from how the upstream components combine. They only arise when binaries are distributed.

- **Readline (GPL-3.0) in the world server, with GPL-2.0-only mod-playerbots.** These licences are
  incompatible. The app never uses the world server's console (`Console.Enable` is off), so the
  clean fix is a core patch that builds the world server without Readline.
- **AGPL-3.0 files in AzerothCore, with GPL-2.0-only mod-playerbots.** Fifteen core files carry
  AGPL-3.0-or-later headers, including `Trainer.cpp` and `TC9Sidecar/`. Upstream's combined binary
  has this same question. Raise it with upstream rather than working around it here.
- **OpenSSL (Apache-2.0) with GPL-2.0-only code.** Upstream ships this combination too, and the GPL's
  system library exception is harder to rely on when the APK bundles OpenSSL itself.

## Client data and trademarks

- The app downloads Blizzard-derived data from a third party during setup. Publishing an app that
  does this carries more legal risk than a server project's install script. App stores may reject
  it, and Blizzard can send takedown requests.
- Avoid Blizzard names and art in the app's name, icon and store listing.

## Signing

`app/build.gradle.kts` has no release signing configuration. Add one, and keep the keystore out of
the repository.
