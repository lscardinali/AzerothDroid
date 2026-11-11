#!/usr/bin/env python3
"""Stage the prebuilt Wine runtime for the :wine module (the WoW client).

The :wine module builds GameNative's Kotlin/Java from source (wine/src, taken from
WoWForeverywhere 1.0.8). Its native libraries and bundled runtime come prebuilt from release APKs,
pinned by SHA-256:

  * WoWForeverywhere 1.0.8: every native library (lib/arm64-v8a) and its assets: Proton 11
    arm64ec, Turnip drivers, Box64/wowbox64, libredirect, PulseAudio, input profiles.
    These match the Java/Kotlin in wine/src, which is the same release.
  * GameNative 1.4.0: FEXCore 2605 (WoWForeverywhere strips it, but a 32-bit Wow.exe and
    GameNative's x86-64 helpers need it) and x86 DXVK 2.4.1 (Direct3D 9 for WoW 3.3.5a).

The first game start downloads GameNative's Windows image (imagefs_bionic.txz, about 180 MB) and
container files from GameNative's servers, as GameNative itself does.

Output: wine/prebuilt/jniLibs/arm64-v8a/ and wine/prebuilt/assets/.
"""
from __future__ import annotations

import hashlib
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "native" / ".wine-cache"
OUT = ROOT / "wine" / "prebuilt"

WFE = {
    "url": "https://github.com/YoruDev-Ryland/WoWForeverywhere/releases/download/v1.0.8/WoW-Foreverywhere.apk",
    "sha256": "75d0984ece32c704f1c3ddde3123904e27a9fcb0b7e476f2d83ce9297ff22030",
    "file": "WoW-Foreverywhere-1.0.8.apk",
}
GAMENATIVE = {
    "url": "https://github.com/utkarshdalal/GameNative/releases/download/v1.4.0/gamenative-v1.4.0.apk",
    "sha256": "51e7edc667a352c664f7da274d15b973cd054ec04e0b828e2039fb40c334224d",
    "file": "gamenative-1.4.0.apk",
}
# From GameNative: only what WoWForeverywhere lacks.
GAMENATIVE_ASSETS = [
    "assets/fexcore/fexcore-2605.tzst",
    "assets/dxwrapper/dxvk-2.4.1.tzst",
    # Direct3D 9 for WoW 3.3.5a: DXVK with async pipeline compiling, so new effects do not hitch.
    "assets/dxwrapper/dxvk-2.4.1-gplasync.tzst",
]
# Libraries Gradle adds itself from the app's dependencies; two copies would clash.
SKIP_LIBS = {"libandroidx.graphics.path.so", "libdatastore_shared_counter.so"}
SKIP_ASSETS_PREFIXES = ("assets/dexopt/", "assets/PublicSuffixDatabase.list")


def fetch(spec: dict) -> Path:
    path = CACHE / spec["file"]
    if not path.exists() or sha256(path) != spec["sha256"]:
        CACHE.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {spec['url']}")
        tmp = path.with_suffix(".part")
        with urllib.request.urlopen(spec["url"]) as response, tmp.open("wb") as out:
            shutil.copyfileobj(response, out, 1 << 20)
        got = sha256(tmp)
        if got != spec["sha256"]:
            tmp.unlink()
            sys.exit(f"{spec['file']}: SHA-256 {got}, expected {spec['sha256']}")
        tmp.replace(path)
    return path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def extract(apk: zipfile.ZipFile, name: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with apk.open(name) as src, dest.open("wb") as out:
        shutil.copyfileobj(src, out, 1 << 20)


def main() -> int:
    if OUT.exists():
        shutil.rmtree(OUT)
    libs = OUT / "jniLibs" / "arm64-v8a"
    assets = OUT / "assets"

    with zipfile.ZipFile(fetch(WFE)) as apk:
        for name in apk.namelist():
            if name.endswith("/"):
                continue
            if name.startswith("lib/arm64-v8a/"):
                lib = name.rsplit("/", 1)[1]
                if lib not in SKIP_LIBS and not lib.startswith("libzstd-jni"):
                    extract(apk, name, libs / lib)
            elif name.startswith("assets/") and not name.startswith(SKIP_ASSETS_PREFIXES):
                extract(apk, name, assets / name[len("assets/"):])

    with zipfile.ZipFile(fetch(GAMENATIVE)) as apk:
        for name in GAMENATIVE_ASSETS:
            extract(apk, name, assets / name[len("assets/"):])

    count = sum(1 for p in OUT.rglob("*") if p.is_file())
    size = sum(p.stat().st_size for p in OUT.rglob("*") if p.is_file())
    print(f"Staged {count} files, {size // (1 << 20)} MB, in {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
