#!/usr/bin/env python3
"""Stage an Android (Bionic, arm64) MariaDB runtime for the APK.

Termux builds MariaDB with the NDK against Bionic, so its binaries run on stock Android.
This script downloads the Termux `mariadb` package plus its dependency closure, then:

  * copies `mariadbd` and the `mariadb` client into jniLibs as lib*.so, so Android
    extracts them into nativeLibraryDir where they are allowed to execute;
  * copies every shared library they need (DT_NEEDED closure) into jniLibs under a
    lib*.so name, recording the original soname in a manifest. At runtime the app
    symlinks <soname> -> nativeLibraryDir/<apk name> and points LD_LIBRARY_PATH there;
  * copies the server's share data (charsets, English error messages) into assets and
    builds the bootstrap SQL that creates the `mysql` system schema.

Downloads are cached in native/.cache and pinned by SHA-256 in tools/mariadb.lock.json.
Termux removes superseded packages from its pool, so the cache is what keeps old pins
reproducible. Use --update to re-resolve against the current Termux index.

Output: native/out/jniLibs/arm64-v8a/ and native/out/assets/mariadb/ (both gitignored;
app/build.gradle.kts adds them as source dirs).
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from collections import deque
from io import BytesIO
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "native" / ".cache" / "termux"
OUT = ROOT / "native" / "out"
JNI = OUT / "jniLibs" / "arm64-v8a"
ASSETS = OUT / "assets" / "mariadb"
LOCKFILE = ROOT / "tools" / "mariadb.lock.json"

REPO = "https://packages.termux.dev/apt/termux-main/"
INDEX_URL = REPO + "dists/stable/main/binary-aarch64/Packages.gz"
TERMUX_PREFIX = "data/data/com.termux/files/usr"

# Executables copied into jniLibs: Termux path -> APK file name.
EXECUTABLES = {
    "bin/mariadbd": "libmariadbd.so",
    "bin/mariadb": "libmariadb_client.so",
}

# Provided by Android itself; never bundled.
SYSTEM_SONAMES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}

# Files from share/mariadb needed to build the system schema, in load order.
BOOTSTRAP_SQL = [
    "mariadb_system_tables.sql",
    "mariadb_performance_tables.sql",
    "mariadb_system_tables_data.sql",
    "fill_help_tables.sql",
    "maria_add_gis_sp_bootstrap.sql",
    "mariadb_sys_schema.sql",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(target.suffix + ".part")
    request = Request(url, headers={"User-Agent": "pocketwow-stage-mariadb"})
    with urlopen(request, timeout=120) as src, tmp.open("wb") as dst:
        shutil.copyfileobj(src, dst)
    tmp.replace(target)


def find_readelf() -> Path:
    sdk = Path.home() / "Library/Android/sdk"
    for base in [sdk / "ndk", Path("/opt/android-sdk/ndk")]:
        found = sorted(base.glob("*/toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
        if found:
            return found[-1]
    found = shutil.which("llvm-readelf")
    if found:
        return Path(found)
    sys.exit("llvm-readelf not found (install the Android NDK)")


READELF = find_readelf()


def readelf(flag: str, path: Path) -> str:
    return subprocess.check_output([str(READELF), flag, str(path)], text=True, errors="replace")


def is_elf(path: Path) -> bool:
    try:
        return path.is_file() and not path.is_symlink() and path.open("rb").read(4) == b"\x7fELF"
    except OSError:
        return False


# ---------------------------------------------------------------------------
# Package resolution
# ---------------------------------------------------------------------------

def parse_index(raw: bytes) -> dict[str, dict[str, str]]:
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


def dependencies(fields: dict[str, str]) -> list[str]:
    result = []
    for item in fields.get("Depends", "").split(","):
        item = item.split("|")[0].strip()  # first alternative wins
        name = re.sub(r"\s*\(.*\)", "", item).strip()
        if name:
            result.append(name)
    return result


def resolve_lock() -> dict:
    print(f"Fetching {INDEX_URL}")
    with urlopen(Request(INDEX_URL, headers={"User-Agent": "pocketwow"}), timeout=120) as r:
        index = parse_index(r.read())
    closure: dict[str, dict[str, str]] = {}
    queue = deque(["mariadb"])
    while queue:
        name = queue.popleft()
        if name in closure:
            continue
        if name not in index:
            sys.exit(f"package {name} not in Termux index")
        fields = index[name]
        closure[name] = {
            "version": fields["Version"],
            "filename": fields["Filename"],
            "sha256": fields["SHA256"],
        }
        queue.extend(dependencies(fields))
    return {"index": INDEX_URL, "packages": dict(sorted(closure.items()))}


def fetch_packages(lock: dict) -> dict[str, Path]:
    archives = {}
    for name, pin in lock["packages"].items():
        target = CACHE / Path(pin["filename"]).name.replace(":", "_")
        if not target.is_file():
            print(f"Downloading {name} {pin['version']}")
            download(REPO + pin["filename"], target)
        if sha256(target) != pin["sha256"]:
            sys.exit(f"SHA-256 mismatch for {target.name}; delete it or run with --update")
        archives[name] = target
    return archives


def extract_deb(deb: Path, destination: Path) -> None:
    """Extract data.tar.* from a .deb (an `ar` archive) without needing dpkg."""
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


# ---------------------------------------------------------------------------
# Staging
# ---------------------------------------------------------------------------

def stage(archives: dict[str, Path], lock: dict) -> None:
    with tempfile.TemporaryDirectory(prefix="pocketwow-mariadb-") as tmp:
        root = Path(tmp)
        for name, deb in archives.items():
            extract_deb(deb, root)
        usr = root / TERMUX_PREFIX

        # soname -> file providing it
        provided: dict[str, Path] = {}
        for path in (usr / "lib").rglob("*"):
            if is_elf(path):
                provided[path.name] = path
                for soname in re.findall(r"Library soname: \[([^]]+)]", readelf("-dW", path)):
                    provided.setdefault(soname, path)

        if JNI.exists():
            shutil.rmtree(JNI)
        if ASSETS.exists():
            shutil.rmtree(ASSETS)
        JNI.mkdir(parents=True)
        ASSETS.mkdir(parents=True)

        def copy_elf(source: Path, apk_name: str) -> None:
            aligns = [int(line.split()[-1], 16) for line in readelf("-lW", source).splitlines()
                      if line.lstrip().startswith("LOAD ")]
            if max(aligns, default=0) > 0x4000:
                sys.exit(f"{source.name}: LOAD alignment above 16 KB")
            target = JNI / apk_name
            shutil.copyfile(source, target)
            target.chmod(0o755)

        links: dict[str, str] = {}  # soname -> apk name
        queue = deque()
        for termux_path, apk_name in EXECUTABLES.items():
            source = usr / termux_path
            copy_elf(source, apk_name)
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
                stem = soname.removeprefix("lib").replace(".so", "")
                safe = re.sub(r"[^A-Za-z0-9]+", "_", stem).strip("_")
                links[soname] = f"libdep_{safe}.so"
                copy_elf(source.resolve(), links[soname])
                queue.append(source)

        # Server data: charsets + English error messages + bootstrap SQL.
        share = usr / "share" / "mariadb"
        shutil.copytree(share / "charsets", ASSETS / "share" / "charsets")
        shutil.copytree(share / "english", ASSETS / "share" / "english")
        parts = ["CREATE DATABASE IF NOT EXISTS mysql;", "USE mysql;", "SET @auth_root_socket=NULL;"]
        for name in BOOTSTRAP_SQL:
            parts.append((share / name).read_text(encoding="utf-8"))
        (ASSETS / "bootstrap.sql").write_text("\n".join(parts) + "\n", encoding="utf-8")

        version = lock["packages"]["mariadb"]["version"]
        manifest = {
            "version": version,
            "executables": {Path(k).name: v for k, v in EXECUTABLES.items()},
            "links": links,
            "bootstrapSha256": sha256(ASSETS / "bootstrap.sql"),
        }
        (ASSETS / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

        total = sum(f.stat().st_size for f in JNI.iterdir())
        print(f"Staged MariaDB {version}: {len(EXECUTABLES)} executables, "
              f"{len(links)} libraries, {total / 1e6:.1f} MB in {JNI.relative_to(ROOT)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--update", action="store_true", help="re-resolve the lockfile from the live Termux index")
    args = parser.parse_args()

    if args.update or not LOCKFILE.is_file():
        LOCKFILE.write_text(json.dumps(resolve_lock(), indent=2) + "\n")
        print(f"Wrote {LOCKFILE.relative_to(ROOT)}")
    lock = json.loads(LOCKFILE.read_text())
    stage(fetch_packages(lock), lock)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
