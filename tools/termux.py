"""Shared helpers for using Termux's Android (Bionic, arm64) packages.

Termux builds its packages with the NDK against Bionic, so their binaries and libraries
run on stock Android. We use them two ways:

  * as prebuilt runtimes (MariaDB), copied into the APK;
  * as a cross-compilation sysroot (Boost, OpenSSL, libmariadb, zlib...) for building
    AzerothCore with the NDK.

All packages come from one lockfile (tools/termux.lock.json) so every component links
the same library versions; shared libraries are then shipped once.

APK layout: executables and libraries go to native/out/jniLibs/arm64-v8a as lib*.so,
so Android extracts them into nativeLibraryDir, the only place an app may exec from.
Libraries keep their original soname in a per-component manifest ("links"); the app
recreates <soname> -> nativeLibraryDir/<apk name> symlinks and uses LD_LIBRARY_PATH.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile
from collections import deque
from io import BytesIO
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "native" / ".cache" / "termux"
SYSROOT = ROOT / "native" / ".build" / "termux-sysroot"
OUT = ROOT / "native" / "out"
JNI = OUT / "jniLibs" / "arm64-v8a"
ASSETS = OUT / "assets"
LOCKFILE = ROOT / "tools" / "termux.lock.json"

REPO = "https://packages.termux.dev/apt/termux-main/"
INDEX_URL = REPO + "dists/stable/main/binary-aarch64/Packages.gz"
PREFIX = "data/data/com.termux/files/usr"

# Top-level packages; their Depends closure is resolved into the lockfile.
ROOT_PACKAGES = [
    "mariadb",
    "boost", "boost-headers", "boost-static",
    "openssl", "zlib", "libbz2", "liblzma",
]

# Provided by Android itself; never bundled.
SYSTEM_SONAMES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    with urlopen(Request(url, headers={"User-Agent": "pocketwow"}), timeout=120) as src, tmp.open("wb") as dst:
        shutil.copyfileobj(src, dst)
    tmp.replace(target)


# ---------------------------------------------------------------------------
# Lockfile
# ---------------------------------------------------------------------------

def _parse_index(raw: bytes) -> dict[str, dict[str, str]]:
    packages: dict[str, dict[str, str]] = {}
    for stanza in gzip.decompress(raw).decode("utf-8").split("\n\n"):
        fields = {}
        for line in stanza.splitlines():
            if ":" in line and not line.startswith(" "):
                key, value = line.split(":", 1)
                fields[key] = value.strip()
        if "Package" in fields:
            packages[fields["Package"]] = fields
    return packages


def _depends(fields: dict[str, str]) -> list[str]:
    result = []
    for item in fields.get("Depends", "").split(","):
        name = re.sub(r"\s*\(.*\)", "", item.split("|")[0]).strip()  # first alternative
        if name:
            result.append(name)
    return result


def load_lock(update: bool = False) -> dict:
    """Returns the lockfile, resolving any root package (or everything, with update)
    that is not pinned yet against the live Termux index."""
    lock = json.loads(LOCKFILE.read_text()) if LOCKFILE.is_file() else {"packages": {}}
    pinned = {} if update else lock["packages"]
    if not update and all(name in pinned for name in ROOT_PACKAGES):
        return lock

    print(f"Resolving against {INDEX_URL}")
    with urlopen(Request(INDEX_URL, headers={"User-Agent": "pocketwow"}), timeout=120) as r:
        index = _parse_index(r.read())
    packages = dict(pinned)
    queue = deque(ROOT_PACKAGES)
    while queue:
        name = queue.popleft()
        if name not in index:
            sys.exit(f"package {name} not in Termux index")
        if name not in packages:
            fields = index[name]
            packages[name] = {"version": fields["Version"], "filename": fields["Filename"], "sha256": fields["SHA256"]}
        queue.extend(dep for dep in _depends(index[name]) if dep not in packages)
    lock = {"index": INDEX_URL, "roots": ROOT_PACKAGES, "packages": dict(sorted(packages.items()))}
    LOCKFILE.write_text(json.dumps(lock, indent=2) + "\n")
    print(f"Wrote {LOCKFILE.relative_to(ROOT)}")
    return lock


def fetch(lock: dict) -> dict[str, Path]:
    archives = {}
    for name, pin in lock["packages"].items():
        target = CACHE / Path(pin["filename"]).name.replace(":", "_")
        if not target.is_file():
            print(f"Downloading {name} {pin['version']}")
            download(REPO + pin["filename"], target)
        if sha256(target) != pin["sha256"]:
            sys.exit(f"SHA-256 mismatch for {target.name}; delete it or re-resolve with --update")
        archives[name] = target
    return archives


def _extract_deb(deb: Path, destination: Path) -> None:
    """Extracts data.tar.* from a .deb (an `ar` archive) without needing dpkg."""
    raw = deb.read_bytes()
    if not raw.startswith(b"!<arch>\n"):
        sys.exit(f"not a .deb: {deb}")
    offset = 8
    while offset < len(raw):
        header = raw[offset:offset + 60]
        offset += 60
        name = header[:16].decode("ascii").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        if name.startswith("data.tar"):
            with tarfile.open(fileobj=BytesIO(raw[offset:offset + size]), mode="r:*") as tar:
                tar.extractall(destination, filter="data")
            return
        offset += size + size % 2
    sys.exit(f"no data.tar in {deb}")


def sysroot(lock: dict) -> Path:
    """All locked packages merged into one tree; returns its usr/ directory."""
    stamp = SYSROOT / ".lock-sha256"
    identity = hashlib.sha256(json.dumps(lock["packages"], sort_keys=True).encode()).hexdigest()
    usr = SYSROOT / PREFIX
    if stamp.is_file() and stamp.read_text() == identity:
        return usr
    archives = fetch(lock)
    if SYSROOT.exists():
        shutil.rmtree(SYSROOT)
    SYSROOT.mkdir(parents=True)
    for archive in archives.values():
        _extract_deb(archive, SYSROOT)
    stamp.write_text(identity)
    return usr


# ---------------------------------------------------------------------------
# ELF staging
# ---------------------------------------------------------------------------

def _find_readelf() -> Path:
    for base in [Path.home() / "Library/Android/sdk/ndk", Path("/opt/android-sdk/ndk")]:
        found = sorted(base.glob("*/toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
        if found:
            return found[-1]
    found = shutil.which("llvm-readelf")
    if found:
        return Path(found)
    sys.exit("llvm-readelf not found (install the Android NDK)")


READELF = _find_readelf()


def readelf(flag: str, path: Path) -> str:
    return subprocess.check_output([str(READELF), flag, str(path)], text=True, errors="replace")


def _is_elf(path: Path) -> bool:
    try:
        return path.is_file() and not path.is_symlink() and path.open("rb").read(4) == b"\x7fELF"
    except OSError:
        return False


def apk_name_for(soname: str) -> str:
    stem = soname.removeprefix("lib").replace(".so", "")
    return "libdep_" + re.sub(r"[^A-Za-z0-9]+", "_", stem).strip("_") + ".so"


def _copy_elf(source: Path, apk_name: str) -> None:
    aligns = [int(line.split()[-1], 16) for line in readelf("-lW", source).splitlines()
              if line.lstrip().startswith("LOAD ")]
    if max(aligns, default=0) > 0x4000:
        sys.exit(f"{source.name}: LOAD alignment above 16 KB")
    JNI.mkdir(parents=True, exist_ok=True)
    target = JNI / apk_name
    shutil.copyfile(source, target)
    target.chmod(0o755)


def stage_closure(executables: dict[Path, str], usr: Path) -> dict[str, str]:
    """Copies executables (source -> APK name) and every shared library they need from
    the sysroot into jniLibs. Returns {soname: apk name} for the app's symlink dir."""
    provided: dict[str, Path] = {}
    for path in (usr / "lib").rglob("*"):
        if _is_elf(path):
            provided.setdefault(path.name, path)
            for soname in re.findall(r"Library soname: \[([^]]+)]", readelf("-dW", path)):
                provided.setdefault(soname, path)

    links: dict[str, str] = {}
    queue = deque()
    for source, apk_name in executables.items():
        _copy_elf(source, apk_name)
        queue.append(source)
    seen: set[Path] = set()
    while queue:
        binary = queue.popleft().resolve()
        if binary in seen:
            continue
        seen.add(binary)
        for soname in re.findall(r"Shared library: \[([^]]+)]", readelf("-dW", binary)):
            if soname in SYSTEM_SONAMES or soname in links:
                continue
            source = provided.get(soname)
            if source is None:
                sys.exit(f"unresolved dependency {soname} (needed by {binary.name})")
            links[soname] = apk_name_for(soname)
            _copy_elf(source.resolve(), links[soname])
            queue.append(source)
    return dict(sorted(links.items()))


def write_manifest(component: str, manifest: dict) -> Path:
    path = ASSETS / component / "manifest.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, indent=2) + "\n")
    return path
