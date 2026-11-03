# Releasing an APK

The repository holds only our code and patches. An APK is different: it contains GPL binaries, so
publishing one brings obligations the repository doesn't have. Work through this list for each
release. This is engineering guidance, not legal advice.

An artifact from the **Build APK** workflow counts too, because anyone with a GitHub account can
download artifacts from a public repository.

## Provide the source

GPL-2.0 requires the complete source for the binaries you distribute. The **Build APK** workflow
collects it with `tools/collect_sources.py` and attaches it as a second artifact:

- this repository at the built commit, including `native/patches/`
- the AzerothCore and mod-playerbots commits from `tools/azerothcore.lock.json`
- MariaDB and Connector/C at the version in `tools/termux.lock.json`, with Termux's recipe

Artifacts expire after 30 days. For a lasting release, attach both the APK and this bundle to a
GitHub release, so the source stays available as long as the APK does. Linking to someone else's
repository isn't a dependable substitute, since it can change or disappear.

## Check the licence texts

`tools/build_azerothcore.py` stages them and fails if one is missing. Confirm
`assets/licenses/` is in the APK, and update `THIRD_PARTY_NOTICES.md` when versions change. The
Settings tab's About card shows the licence notice and a link to the source.

## Open licence questions

These come from how the upstream components combine. They only arise when binaries are distributed.

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

The workflow signs every APK with one key, from the `ANDROID_KEYSTORE_BASE64` and
`ANDROID_KEYSTORE_PASSWORD` repository secrets. Android only updates an app in place when the key
matches, so **losing this key means users can't update without uninstalling, which deletes their
realm.** Keep a backup somewhere safe, and never commit it.

To sign local builds with the same key, set `AZEROTHDROID_KEYSTORE` and
`AZEROTHDROID_KEYSTORE_PASSWORD` before running Gradle.
