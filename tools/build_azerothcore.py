#!/usr/bin/env python3
"""Cross-compile AzerothCore (mod-playerbots fork) for Android arm64 and stage it.

Builds with the NDK's clang, using Termux packages (tools/termux.py) as the sysroot for
Boost, OpenSSL, libmariadb, readline and zlib, so none of them need cross-compiling here.
Boost links statically; the rest are shared libraries shipped alongside MariaDB's.

The core (mod-playerbots/azerothcore-wotlk `Playerbot` branch) and its modules are pinned
in tools/azerothcore.lock.json and checked out into native/src/azerothcore. Android/Bionic
source fixes live in native/patches/<repo>/*.patch.

Output:
  native/out/jniLibs/arm64-v8a/lib<app>.so (+ shared library closure)
  native/out/assets/<app>/        manifest.json and the .conf.dist templates
  native/out/assets/azerothcore/  sql.zip: data/sql/{base,updates} and each module's
                                  data/sql, AzerothCore's SourceDirectory for its DB
                                  updater; manifest.json with the client-data release

Client data (Blizzard-derived) is never bundled: the app downloads it in its setup phase,
from the same wowgaming/client-data release AzerothCore's Docker setup uses.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import termux  # noqa: E402

ROOT = termux.ROOT
LOCKFILE = ROOT / "tools" / "azerothcore.lock.json"
SOURCE = ROOT / "native" / "src" / "azerothcore"
PATCHES = ROOT / "native" / "patches"
BUILD = ROOT / "native" / ".build" / "azerothcore"
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
ANDROID_PLATFORM = "android-26"

# app -> config templates to ship, relative to SOURCE
APPS = {
    "authserver": ["src/server/apps/authserver/authserver.conf.dist"],
    "worldserver": [
        "src/server/apps/worldserver/worldserver.conf.dist",
        "modules/mod-playerbots/conf/playerbots.conf.dist",
    ],
}


def run(*cmd: str | Path, cwd: Path | None = None) -> None:
    print("+", " ".join(str(c) for c in cmd), flush=True)
    subprocess.run([str(c) for c in cmd], cwd=cwd, check=True)


def latest(pattern: str, base: Path) -> Path:
    found = sorted(base.glob(pattern))
    if not found:
        sys.exit(f"not found: {base}/{pattern}")
    return found[-1]


def checkout_repo(path: Path, pin: dict, patches: Path) -> None:
    """Shallow checkout of exactly the pinned commit, then apply our patches."""
    if not (path / ".git").exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--depth", "1", "--branch", pin["branch"], pin["repo"], path)
    head = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    if head != pin["commit"]:
        run("git", "-C", path, "fetch", "--depth", "1", "origin", pin["commit"])
        run("git", "-C", path, "checkout", "--detach", pin["commit"])
    apply_patches(path, sorted(patches.glob("*.patch")))


def apply_patches(path: Path, series: list[Path]) -> None:
    """Brings the tracked files to pinned commit + patch series, keeping the checkout as is
    when it already matches (so unchanged sources do not rebuild).

    Patches may stack on the same file, so the series is checked as a whole: applied to a
    scratch index from HEAD, then compared with the working tree. A tree that matches
    neither HEAD nor the series holds edits no patch records; stop rather than lose them.
    """
    if not series:
        return
    git = ["git", "-C", str(path)]
    with tempfile.TemporaryDirectory() as scratch:
        env = {**os.environ, "GIT_INDEX_FILE": str(Path(scratch) / "index")}
        subprocess.run([*git, "read-tree", "HEAD"], env=env, check=True)
        for patch in series:
            subprocess.run([*git, "apply", "--cached", str(patch)], env=env, check=True)
        expected = subprocess.check_output([*git, "write-tree"], env=env, text=True).strip()
    if subprocess.run([*git, "diff", "--quiet", expected], check=False).returncode == 0:
        return
    if subprocess.run([*git, "diff", "--quiet", "HEAD"], check=False).returncode != 0:
        sys.exit(f"{path} has edits that are not in {series[0].parent}; regenerate the patches first")
    for patch in series:
        run(*git, "apply", patch)


def checkout(lock: dict) -> None:
    checkout_repo(SOURCE, lock, PATCHES / "azerothcore")
    for name, pin in lock.get("modules", {}).items():
        checkout_repo(SOURCE / "modules" / name, pin, PATCHES / name)


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


def configure(usr: Path, lock_termux: dict) -> None:
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
        f"-DREADLINE_INCLUDE_DIR={usr / 'include'}",
        f"-DREADLINE_LIBRARY={libdir / 'libreadline.so'}",
        # What to build: both servers, scripts and modules linked in statically.
        "-DAPPS_BUILD=all",
        "-DTOOLS_BUILD=none",
        "-DSCRIPTS=static",
        "-DMODULES=static",
        "-DBUILD_TESTING=0",
        "-DWITHOUT_METRICS=1",
        f"-DCMAKE_INSTALL_PREFIX={BUILD / 'install'}",
    )


def stage(usr: Path, app: str, lock: dict) -> None:
    binary = BUILD / "src" / "server" / "apps" / app
    if not (binary.is_file() and os.access(binary, os.X_OK)):
        sys.exit(f"built {app} binary not found at {binary}")
    # OpenSSL 3 keeps RC4 (world socket header encryption) in its legacy provider, a module
    # it loads by file name from OPENSSL_MODULES; the app points that at its library links.
    legacy = usr / "lib" / "ossl-modules" / "legacy.so"
    links = termux.stage_closure({binary: f"lib{app}.so", legacy: "libossl_legacy.so"}, usr)
    links["legacy.so"] = "libossl_legacy.so"
    # The NDK toolchain always compiles with -g; ship stripped, keep the unstripped
    # binary in the build dir for symbolizing crashes.
    strip = latest("*/toolchains/llvm/prebuilt/*/bin/llvm-strip", SDK / "ndk")
    run(strip, "--strip-debug", termux.JNI / f"lib{app}.so")

    assets = termux.ASSETS / app
    if assets.exists():
        shutil.rmtree(assets)
    assets.mkdir(parents=True)
    for conf in APPS[app]:
        shutil.copyfile(SOURCE / conf, assets / Path(conf).name)

    modules = {name: pin["commit"] for name, pin in lock.get("modules", {}).items()}
    termux.write_manifest(app, {
        "commit": lock["commit"],
        "modules": modules,
        "executable": f"lib{app}.so",
        "links": links,
    })
    size = (termux.JNI / f"lib{app}.so").stat().st_size / 1e6
    print(f"Staged {app} ({size:.1f} MB) with {len(links)} libraries, AzerothCore {lock['commit'][:10]}")


def client_data_release() -> dict:
    """The client-data version AzerothCore's installer pins, so data matches the core."""
    script = (SOURCE / "apps/installer/includes/functions.sh").read_text()
    version = re.search(r"local VERSION=(v[\d.]+)", script)
    url = re.search(r"(https://github\.com/wowgaming/client-data/releases/download/)\$VERSION(/\S+)", script)
    if not version or not url:
        sys.exit("client-data release not found in apps/installer/includes/functions.sh")
    return {"version": version.group(1), "url": url.group(1) + version.group(1) + url.group(2)}


def stage_sql(lock: dict) -> None:
    """Zips the SQL AzerothCore's updater reads, laid out as its SourceDirectory.
    data/sql/archive is left out: it only holds already-merged history, and missing
    files are cleaned from the updates table (Updates.CleanDeadRefMaxCount = -1)."""
    identity = hashlib.sha256(json.dumps(lock, sort_keys=True).encode()).hexdigest()[:16]
    assets = termux.ASSETS / "azerothcore"
    manifest = {"sqlIdentity": identity, "clientData": client_data_release()}
    archive = assets / "sql.zip"
    current = assets / "manifest.json"
    if archive.is_file() and current.is_file() and json.loads(current.read_text()) == manifest:
        return
    roots = ["data/sql/base", "data/sql/updates"]
    roots += [f"modules/{name}/data/sql" for name in lock.get("modules", {})]
    assets.mkdir(parents=True, exist_ok=True)
    partial = archive.with_suffix(".part")
    with zipfile.ZipFile(partial, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for root in roots:
            for path in sorted((SOURCE / root).rglob("*")):
                if path.is_file():
                    zf.write(path, path.relative_to(SOURCE).as_posix())
    partial.replace(archive)
    termux.write_manifest("azerothcore", manifest)
    print(f"Staged sql.zip ({archive.stat().st_size / 1e6:.0f} MB), client data {manifest['clientData']['version']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apps", nargs="*", default=sorted(APPS), choices=sorted(APPS))
    parser.add_argument("--reconfigure", action="store_true", help="wipe the CMake build directory first")
    parser.add_argument("--jobs", "-j", type=int, help="parallel compile jobs (default: ninja's)")
    args = parser.parse_args()

    lock_termux = termux.load_lock()
    usr = termux.sysroot(lock_termux)
    lock = json.loads(LOCKFILE.read_text())
    checkout(lock)
    if args.reconfigure and BUILD.exists():
        shutil.rmtree(BUILD)
    configure(usr, lock_termux)
    jobs = ["-j", str(args.jobs)] if args.jobs else []
    run(latest("*/bin", SDK / "cmake") / "cmake", "--build", BUILD, *jobs, "--target", *args.apps)
    for app in args.apps:
        stage(usr, app, lock)
    stage_sql(lock)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
