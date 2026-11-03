#!/usr/bin/env python3
"""Collect the source matching an APK, which GPL-2.0 requires alongside the binaries.

Needs the checkout tools/build_azerothcore.py makes. The bundle covers the GPL and LGPL parts
of the APK: this repository (scripts and patches), AzerothCore, mod-playerbots, and MariaDB
(server and Connector/C, one tarball) with the Termux recipe that built it. The other bundled
libraries have permissive licences; see THIRD_PARTY_NOTICES.md.

Output: native/.build/source-bundle/ (outside native/out, so it stays out of the APK)
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "native" / "src" / "azerothcore"
BUNDLE = ROOT / "native" / ".build" / "source-bundle"
RECIPES_API = "https://api.github.com/repos/termux/termux-packages"
RECIPES_RAW = "https://raw.githubusercontent.com/termux/termux-packages"
RECIPE = "packages/mariadb"
HISTORY_DEPTH = 30


def fetch(url: str) -> bytes:
    headers = {"User-Agent": "azerothdroid"}
    if url.startswith("https://api.github.com") and os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = f"Bearer {os.environ['GITHUB_TOKEN']}"
    with urlopen(Request(url, headers=headers), timeout=600) as response:
        return response.read()


def fetch_json(url: str) -> list | dict:
    return json.loads(fetch(url))


def archive(repo: Path, commit: str, name: str) -> None:
    """Pristine upstream at the pinned commit; our patches are in this repository's archive."""
    prefix = f"{name}-{commit[:12]}"
    target = BUNDLE / f"{prefix}.tar.gz"
    subprocess.run(
        ["git", "-C", str(repo), "archive", "--format=tar.gz", f"--prefix={prefix}/", "-o", str(target), commit],
        check=True,
    )
    print(f"Archived {target.name}")


def recipe_field(script: str, field: str) -> str | None:
    found = re.search(rf'^TERMUX_PKG_{field}="?([^"\n]+)"?', script, re.MULTILINE)
    return found.group(1) if found else None


def find_recipe(version: str) -> tuple[str, str]:
    """The newest termux-packages commit whose recipe builds exactly this package version."""
    upstream, _, revision = version.partition("-")
    commits = fetch_json(f"{RECIPES_API}/commits?path={RECIPE}/build.sh&per_page={HISTORY_DEPTH}")
    for commit in commits:
        script = fetch(f"{RECIPES_RAW}/{commit['sha']}/{RECIPE}/build.sh").decode()
        same_revision = (recipe_field(script, "REVISION") or "") == revision
        if recipe_field(script, "VERSION") == upstream and same_revision:
            return commit["sha"], script
    sys.exit(f"no {RECIPE} recipe for {version} in the last {HISTORY_DEPTH} commits")


def collect_recipe(commit: str, name: str) -> None:
    """The Termux build script and patches, which are part of how the binary was made."""
    folder = BUNDLE / name
    folder.mkdir()
    for entry in fetch_json(f"{RECIPES_API}/contents/{RECIPE}?ref={commit}"):
        if entry["type"] == "file":
            (folder / entry["name"]).write_bytes(fetch(entry["download_url"]))
    target = BUNDLE / f"{name}.tar.gz"
    with tarfile.open(target, "w:gz") as tar:
        tar.add(folder, arcname=name)
    shutil.rmtree(folder)
    print(f"Collected {target.name} from termux-packages {commit[:12]}")


def collect_mariadb(version: str) -> str:
    commit, script = find_recipe(version)
    plain = version.rpartition(":")[2].partition("-")[0]
    url = recipe_field(script, "SRCURL")
    expected = recipe_field(script, "SHA256")
    if not url or not expected:
        sys.exit("MariaDB source URL or SHA-256 missing from the Termux recipe")
    url = url.replace("${TERMUX_PKG_VERSION#*:}", plain)
    if "$" in url:
        sys.exit(f"could not resolve the MariaDB source URL: {url}")
    print(f"Downloading {url}")
    tarball = fetch(url)
    if hashlib.sha256(tarball).hexdigest() != expected:
        sys.exit("MariaDB source does not match the SHA-256 in the Termux recipe")
    (BUNDLE / url.rsplit("/", 1)[1]).write_bytes(tarball)
    collect_recipe(commit, f"termux-mariadb-{plain}")
    return commit


def main() -> int:
    lock = json.loads((ROOT / "tools" / "azerothcore.lock.json").read_text())
    termux_lock = json.loads((ROOT / "tools" / "termux.lock.json").read_text())
    if not SOURCE.is_dir():
        sys.exit(f"{SOURCE} missing: run tools/build_azerothcore.py first")
    if BUNDLE.exists():
        shutil.rmtree(BUNDLE)
    BUNDLE.mkdir(parents=True)

    head = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True).strip()
    archive(ROOT, head, "azerothdroid")
    archive(SOURCE, lock["commit"], "azerothcore")
    for name, pin in lock.get("modules", {}).items():
        archive(SOURCE / "modules" / name, pin["commit"], name)
    version = termux_lock["packages"]["mariadb"]["version"]
    recipe = collect_mariadb(version)

    (BUNDLE / "README.txt").write_text(
        "Source for the GPL and LGPL parts of an AzerothDroid APK.\n\n"
        f"AzerothDroid {head}: build scripts, app and patches (native/patches)\n"
        f"AzerothCore {lock['commit']}\n"
        + "".join(f"{name} {pin['commit']}\n" for name, pin in lock.get("modules", {}).items())
        + f"MariaDB {version}, built with termux-packages {recipe}\n\n"
        "To rebuild, follow 'Building from source' in the AzerothDroid README. It applies the patches\n"
        "to these exact commits. Version pins are in tools/azerothcore.lock.json and\n"
        "tools/termux.lock.json.\n"
    )
    total = sum(path.stat().st_size for path in BUNDLE.iterdir()) / 1e6
    print(f"Source bundle: {total:.0f} MB in {BUNDLE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
