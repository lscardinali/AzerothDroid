#!/usr/bin/env python3
"""Cross-compile AzerothCore (mod-playerbots fork) for Android arm64 and stage it.

Builds with the NDK's clang, using Termux packages (tools/termux.py) as the sysroot for
Boost, OpenSSL, libmariadb and zlib, so none of them need cross-compiling here. Boost
links statically; the rest are shared libraries shipped alongside MariaDB's.

The source is the mod-playerbots/azerothcore-wotlk `Playerbot` branch, pinned to a
commit in tools/azerothcore.lock.json and checked out into native/src/azerothcore.

Output:
  native/out/jniLibs/arm64-v8a/libauthserver.so (+ shared library closure)
  native/out/assets/authserver/   manifest.json, authserver.conf.dist
  native/out/assets/azerothcore/  data/sql/{base,updates,archive}/db_auth, read by
                                  AzerothCore's own DB updater (SourceDirectory)

Android/Bionic source fixes live in native/patches/azerothcore/*.patch.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import termux  # noqa: E402

ROOT = termux.ROOT
LOCKFILE = ROOT / "tools" / "azerothcore.lock.json"
SOURCE = ROOT / "native" / "src" / "azerothcore"
PATCHES = ROOT / "native" / "patches" / "azerothcore"
BUILD = ROOT / "native" / ".build" / "azerothcore"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
ANDROID_PLATFORM = "android-26"

# app -> (CMake APPS_BUILD value, databases whose SQL the app's updater needs)
APPS = {
    "authserver": ("auth-only", ["db_auth"]),
}


def run(*cmd: str | Path, cwd: Path | None = None) -> None:
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], cwd=cwd, check=True)


def latest(pattern: str, base: Path) -> Path:
    found = sorted(base.glob(pattern))
    if not found:
        sys.exit(f"not found: {base}/{pattern}")
    return found[-1]


def checkout(lock: dict) -> None:
    """Shallow checkout of exactly the pinned commit."""
    if not (SOURCE / ".git").is_dir():
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--depth", "1", "--branch", lock["branch"], lock["repo"], SOURCE)
    head = subprocess.check_output(["git", "-C", str(SOURCE), "rev-parse", "HEAD"], text=True).strip()
    if head != lock["commit"]:
        run("git", "-C", SOURCE, "fetch", "--depth", "1", "origin", lock["commit"])
        run("git", "-C", SOURCE, "checkout", "--detach", lock["commit"])
    apply_patches()


def apply_patches() -> None:
    """Android/Bionic fixes, kept as patches so a re-pin re-applies them deliberately."""
    for patch in sorted(PATCHES.glob("*.patch")):
        already = subprocess.run(["git", "-C", str(SOURCE), "apply", "--reverse", "--check", str(patch)],
                                 capture_output=True).returncode == 0
        if not already:
            run("git", "-C", SOURCE, "apply", patch)


def mysql_config_shim(usr: Path, version: str) -> Path:
    """AzerothCore's FindMySQL asks mysql_config for the version (>= 8.0), include dir
    and libs. Termux's own script hardcodes Termux paths, so answer for the sysroot."""
    libdir = usr / "lib" / "aarch64-linux-android"
    shim = BUILD / "mysql_config"
    shim.parent.mkdir(parents=True, exist_ok=True)
    shim.write_text(
        "#!/bin/sh\n"
        'case "$1" in\n'
        f'  --version) echo "{version}" ;;\n'
        f'  --include) echo "-I{usr}/include/mariadb" ;;\n'
        f'  --libs|--libs_r) echo "-L{libdir} -lmariadb" ;;\n'
        "esac\n"
    )
    shim.chmod(0o755)
    return shim


def configure(usr: Path, app: str, lock_termux: dict) -> None:
    ndk = latest("*", SDK / "ndk")
    cmake_bin = latest("*/bin", SDK / "cmake")
    mariadb_version = lock_termux["packages"]["mariadb"]["version"].split(":")[-1]
    boost_version = lock_termux["packages"]["boost"]["version"].split(":")[-1]
    libdir = usr / "lib"
    run(
        cmake_bin / "cmake", "-S", SOURCE, "-B", BUILD, "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={cmake_bin / 'ninja'}",
        f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
        "-DANDROID_ABI=arm64-v8a",
        f"-DANDROID_PLATFORM={ANDROID_PLATFORM}",
        "-DANDROID_STL=c++_shared",
        "-DCMAKE_BUILD_TYPE=Release",
        # Search the Termux sysroot for packages and libraries.
        f"-DCMAKE_FIND_ROOT_PATH={usr}",
        f"-DCMAKE_EXE_LINKER_FLAGS=-L{libdir}",  # bz2/lzma pulled in by static Boost.Iostreams
        f"-DBoost_DIR={libdir / 'cmake' / f'Boost-{boost_version}'}",
        "-DBoost_USE_STATIC_LIBS=ON",
        f"-DMYSQL_CONFIG={mysql_config_shim(usr, mariadb_version)}",
        f"-DMYSQL_INCLUDE_DIR={usr / 'include' / 'mariadb'}",
        f"-DMYSQL_LIBRARY={libdir / 'aarch64-linux-android' / 'libmariadb.so'}",
        f"-DOPENSSL_ROOT_DIR={usr}",
        f"-DOPENSSL_INCLUDE_DIR={usr / 'include'}",
        f"-DOPENSSL_CRYPTO_LIBRARY={libdir / 'libcrypto.so'}",
        f"-DOPENSSL_SSL_LIBRARY={libdir / 'libssl.so'}",
        # What to build.
        f"-DAPPS_BUILD={APPS[app][0]}",
        "-DTOOLS_BUILD=none",
        "-DSCRIPTS=none",
        "-DMODULES=none",
        "-DBUILD_TESTING=0",
        "-DWITHOUT_METRICS=1",
        f"-DCMAKE_INSTALL_PREFIX={BUILD / 'install'}",
    )


def stage(usr: Path, app: str) -> None:
    binary = BUILD / "src" / "server" / "apps" / app
    candidates = [p for p in BUILD.rglob(app) if p.is_file() and os.access(p, os.X_OK)]
    if not candidates:
        sys.exit(f"built {app} binary not found under {BUILD}")
    binary = candidates[0]
    links = termux.stage_closure({binary: f"lib{app}.so"}, usr)

    assets = termux.ASSETS / app
    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(parents=True)
    conf = SOURCE / "src" / "server" / "apps" / app / f"{app}.conf.dist"
    shutil.copyfile(conf, assets / conf.name)

    # SQL for AzerothCore's own updater (Updates.AutoSetup), per database.
    sql_root = termux.ASSETS / "azerothcore" / "data" / "sql"
    for database in APPS[app][1]:
        for kind in ("base", "updates", "archive"):
            source = SOURCE / "data" / "sql" / kind / database
            target = sql_root / kind / database
            if target.exists():
                shutil.rmtree(target)
            if source.is_dir():
                shutil.copytree(source, target)

    commit = json.loads(LOCKFILE.read_text())["commit"]
    termux.write_manifest(app, {"commit": commit, "executable": f"lib{app}.so", "links": links})
    size = (termux.JNI / f"lib{app}.so").stat().st_size / 1e6
    print(f"Staged {app} ({size:.1f} MB) with {len(links)} libraries, AzerothCore {commit[:10]}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("app", nargs="?", default="authserver", choices=sorted(APPS))
    parser.add_argument("--reconfigure", action="store_true", help="wipe the CMake build directory first")
    args = parser.parse_args()

    lock_termux = termux.load_lock()
    usr = termux.sysroot(lock_termux)
    checkout(json.loads(LOCKFILE.read_text()))
    if args.reconfigure and BUILD.exists():
        shutil.rmtree(BUILD)
    configure(usr, args.app, lock_termux)
    run(latest("*/bin", SDK / "cmake") / "cmake", "--build", BUILD, "--target", args.app)
    stage(usr, args.app)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
