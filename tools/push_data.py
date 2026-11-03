#!/usr/bin/env python3
"""Development shortcut: push the client data to a device over adb.

The app normally downloads client data itself in its setup phase. This script does the
same from the Mac (download once, cache in native/.cache, push over USB), which is
faster when reinstalling or testing on several devices. It writes the same layout and
.version stamp the app checks, so the app treats the data as installed.

Client data is the prebuilt wowgaming/client-data release that AzerothCore's own Docker
setup downloads (version read from the pinned checkout's apps/installer). It is
Blizzard-derived data, so it is fetched on request and never bundled in the APK.

Destination: the app's internal files/client-data. Data is streamed as a tar through
`run-as` so the files belong to the app's user (debuggable builds only); files adb
pushes into Android/data would belong to the shell user and be unreadable to the app.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native" / "src" / "azerothcore"
CACHE = ROOT / "native" / ".cache" / "client-data"
PACKAGE = "com.lscardinali.azerothdroid"
REMOTE = "files"  # relative to the app's data dir, as seen by run-as
ADB = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools" / "adb"

CLIENT_DATA_URL = "https://github.com/wowgaming/client-data/releases/download/{version}/data.zip"
CLIENT_DATA_DIRS = ["dbc", "maps", "vmaps", "mmaps", "Cameras"]



def adb(*args: str, capture: bool = False) -> str:
    result = subprocess.run([str(ADB), *args], check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout or ""


def run_as(command: str, stdin=None) -> subprocess.CompletedProcess:
    """Runs a shell command as the app's user, in its data dir (debuggable builds only).
    -T: no pty, so binary stdin passes through untouched."""
    return subprocess.run([str(ADB), "shell", "-T", "run-as", PACKAGE, "sh", "-c", f"'{command}'"],
                          stdin=stdin, capture_output=stdin is None, text=stdin is None)


def remote_read(path: str) -> str:
    return run_as(f"cat {path} 2>/dev/null").stdout.strip()


def client_data_version() -> str:
    """The version AzerothCore's installer pins, so data matches the pinned core."""
    script = (SOURCE / "apps/installer/includes/functions.sh").read_text()
    match = re.search(r"local VERSION=(v[\d.]+)", script)
    if not match:
        sys.exit("client-data version not found in apps/installer/includes/functions.sh")
    return match.group(1)


def fetch_client_data(version: str) -> Path:
    """Downloads and unpacks the release once; returns the folder holding dbc/, maps/..."""
    target = CACHE / version
    if (target / ".complete").is_file():
        return target
    archive = CACHE / f"data-{version}.zip"
    if not archive.is_file():
        url = CLIENT_DATA_URL.format(version=version)
        print(f"Downloading {url}")
        CACHE.mkdir(parents=True, exist_ok=True)
        partial = archive.with_suffix(".part")
        with urlopen(Request(url, headers={"User-Agent": "azerothdroid"}), timeout=120) as src, \
                partial.open("wb") as dst:
            total = int(src.headers.get("Content-Length", 0))
            done = 0
            while chunk := src.read(1 << 22):
                dst.write(chunk)
                done += len(chunk)
                if total:
                    print(f"\r  {done / 1e6:,.0f} / {total / 1e6:,.0f} MB", end="", flush=True)
        print()
        partial.replace(archive)
    print(f"Unpacking {archive.name}")
    if target.exists():
        shutil.rmtree(target)
    with zipfile.ZipFile(archive) as zf:
        for member in zf.namelist():
            # Zip-slip guard: everything must stay inside target.
            if Path(member).is_absolute() or ".." in Path(member).parts:
                sys.exit(f"unsafe path in archive: {member}")
        zf.extractall(target)
    missing = [d for d in CLIENT_DATA_DIRS[:4] if not (target / d).is_dir()]
    if missing:
        sys.exit(f"client data archive lacks {missing}")
    (target / ".complete").write_text(version)
    return target


def push_client_data(force: bool) -> None:
    version = client_data_version()
    target = f"{REMOTE}/client-data"
    if not force and remote_read(f"{target}/.version") == version:
        print(f"Client data {version}: up to date on device")
        return
    local = fetch_client_data(version)
    dirs = [name for name in CLIENT_DATA_DIRS if (local / name).is_dir()]
    print(f"Client data {version}: streaming {', '.join(dirs)} to the device")
    staging = f"{target}.tmp"
    tar = subprocess.Popen(["tar", "-cf", "-", "-C", str(local), *dirs], stdout=subprocess.PIPE)
    result = run_as(f"rm -rf {staging} && mkdir -p {staging} && tar -xf - -C {staging}", stdin=tar.stdout)
    tar.stdout.close()
    if tar.wait() != 0 or result.returncode != 0:
        sys.exit("streaming client data failed")
    done = run_as(f"echo {version} > {staging}/.version && rm -rf {target} && mv {staging} {target}")
    if done.returncode != 0:
        sys.exit(f"finishing client data failed: {done.stderr}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--force", action="store_true", help="push even if the device has this version")
    args = parser.parse_args()

    if not SOURCE.is_dir():
        sys.exit("AzerothCore checkout missing; run tools/build_azerothcore.py first")
    push_client_data(args.force)
    print(f"Client data installed in {REMOTE}/client-data")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
