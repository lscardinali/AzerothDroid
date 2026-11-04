#!/usr/bin/env python3
"""Stage licence texts and copyright notices into the APK.

The APK carries third-party binaries, and most of their licences require their terms and
copyright notices to travel with them. tools/build_azerothcore.py runs this after staging;
it needs the pinned sources and the Termux sysroot, which that script sets up.

Output: native/out/assets/licenses/
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import termux  # noqa: E402

SOURCE = termux.ROOT / "native" / "src" / "azerothcore"

# Output name -> file in the AzerothCore checkout. What the servers link; tools-only
# dependencies (libmpq, for one) are left out because TOOLS_BUILD is none.
SOURCE_NOTICES = {
    "azerothcore-AUTHORS.txt": "AUTHORS",
    "azerothcore-LICENSE.txt": "LICENSE",
    "azerothcore-dependencies.txt": "deps/PackageList.txt",
    "mod-playerbots-LICENSE.txt": "modules/mod-playerbots/LICENSE",
    # mod-autobalance has no licence file; this source file carries its GPL notice.
    "mod-autobalance-notice.txt": "modules/mod-autobalance/src/AutoBalance.cpp",
    "mod-individual-progression-LICENSE.txt": "modules/mod-individual-progression/LICENSE",
    "argon2-LICENSE.txt": "deps/argon2/LICENSE",
    "fmt-LICENSE.txt": "deps/fmt/LICENSE",
    "g3dlite-license.cpp.txt": "deps/g3dlite/source/license.cpp",
    "recastnavigation-LICENSE.txt": "deps/recastnavigation/License.txt",
    "sfmt-LICENSE.txt": "deps/SFMT/LICENSE.txt",
}

# Output name -> file under the sysroot's share/doc. Packages whose copyright file is only
# a link to a standard text are covered by LICENSES/ instead (see THIRD_PARTY_NOTICES.md).
PACKAGE_NOTICES = {
    "mariadb-COPYING.txt": "COPYING",
    "mariadb-CREDITS.txt": "CREDITS",
    "mariadb-THIRDPARTY.txt": "THIRDPARTY",
    "bzip2-LICENSE.txt": "libbz2/copyright",
    "libandroid-support-LICENSE.txt": "libandroid-support/LICENSE.txt",
    "libcrypt-LICENSE.txt": "libcrypt/copyright",
    "libedit-LICENSE.txt": "libedit/copyright",
    "liblzma-COPYING.txt": "liblzma/COPYING",
    "ncurses-LICENSE.txt": "ncurses/copyright",
    "pcre2-LICENSE.txt": "pcre2/LICENCE.md",
    "zlib-LICENSE.txt": "zlib/copyright",
}


def stage(usr: Path) -> None:
    assets = termux.ASSETS / "licenses"
    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(parents=True)
    shutil.copyfile(termux.ROOT / "LICENSE", assets / "LICENSE.txt")
    shutil.copyfile(termux.ROOT / "THIRD_PARTY_NOTICES.md", assets / "THIRD_PARTY_NOTICES.md")
    for text in sorted((termux.ROOT / "LICENSES").iterdir()):
        shutil.copyfile(text, assets / text.name)
    sources = [(name, SOURCE / path) for name, path in SOURCE_NOTICES.items()]
    sources += [(name, usr / "share" / "doc" / path) for name, path in PACKAGE_NOTICES.items()]
    for name, path in sources:
        # Fail rather than quietly ship an APK that lacks a required notice.
        if not path.is_file():
            sys.exit(f"licence file not found: {path}")
        shutil.copyfile(path, assets / name)
    print(f"Staged {sum(1 for _ in assets.iterdir())} licence files")


def main() -> int:
    if not SOURCE.is_dir():
        sys.exit(f"{SOURCE} missing: run tools/build_azerothcore.py first")
    stage(termux.sysroot(termux.load_lock()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
