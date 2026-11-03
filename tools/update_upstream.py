#!/usr/bin/env python3
"""Move the pins in tools/azerothcore.lock.json to the newest upstream commits.

Applies this project's patch series to each new commit first, in a throwaway repository, so
the local checkout and build stay as they are. The lockfile only changes when every patch
applies. tools/build_azerothcore.py then checks the new commits out on its next run.

Exit codes: 0 up to date or updated, 2 a patch no longer applies.
With --summary FILE, also writes a Markdown report, which the scheduled workflow
(.github/workflows/upstream-updates.yml) posts as its pull request or issue.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCKFILE = ROOT / "tools" / "azerothcore.lock.json"
PATCHES = ROOT / "native" / "patches"


def latest_commit(pin: dict) -> str:
    output = subprocess.check_output(
        ["git", "ls-remote", pin["repo"], f"refs/heads/{pin['branch']}"], text=True,
    )
    if not output:
        sys.exit(f"branch {pin['branch']} not found in {pin['repo']}")
    return output.split()[0]


def failing_patch(pin: dict, commit: str, series: list[Path]) -> tuple[str, str] | None:
    """Applies the series on top of commit. Returns the first failing patch and git's error."""
    with tempfile.TemporaryDirectory() as scratch:
        git = ["git", "-C", scratch]
        subprocess.run([*git, "init", "--quiet", "--bare"], check=True)
        subprocess.run([*git, "fetch", "--quiet", "--depth", "1", pin["repo"], commit], check=True)
        env = {**os.environ, "GIT_INDEX_FILE": str(Path(scratch) / "scratch-index")}
        subprocess.run([*git, "read-tree", "FETCH_HEAD"], env=env, check=True)
        for patch in series:
            result = subprocess.run(
                [*git, "apply", "--cached", str(patch)], env=env, capture_output=True, text=True,
            )
            if result.returncode != 0:
                return patch.name, result.stderr.strip()
    return None


def compare_url(pin: dict, new: str) -> str:
    base = pin["repo"].removesuffix(".git")
    return f"{base}/compare/{pin['commit'][:12]}...{new[:12]}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="report only; leave the lockfile as it is")
    parser.add_argument("--summary", type=Path, help="write a Markdown report to this file")
    args = parser.parse_args()

    lock = json.loads(LOCKFILE.read_text())
    pins = {"azerothcore": lock, **lock.get("modules", {})}
    changes: dict[str, str] = {}
    failures: list[str] = []
    report: list[str] = []
    for name, pin in pins.items():
        new = latest_commit(pin)
        if new == pin["commit"]:
            print(f"{name}: up to date")
            continue
        changes[name] = new
        print(f"{name}: {pin['commit'][:12]} -> {new[:12]}")
        report.append(f"- **{name}**: [{pin['commit'][:12]} → {new[:12]}]({compare_url(pin, new)})")
        failure = failing_patch(pin, new, sorted((PATCHES / name).glob("*.patch")))
        if failure:
            patch, error = failure
            print(f"{name}: {patch} no longer applies\n{error}", file=sys.stderr)
            failures.append(f"### {name}: `{patch}` no longer applies\n\n```\n{error}\n```")

    if failures:
        body = ["Upstream moved and these patches need updating:", "", *report, "", *failures]
    elif changes:
        body = [
            "Every patch applies to the new upstream commits:", "", *report, "",
            "Patches applying does not prove the result works. Before merging, rebuild with "
            "`python3 tools/build_azerothcore.py`, install, and play a session.",
        ]
    else:
        body = ["Already on the newest upstream commits."]
    if args.summary:
        args.summary.write_text("\n".join(body) + "\n")

    if failures:
        return 2
    if changes and not args.check:
        for name, new in changes.items():
            pins[name]["commit"] = new
        LOCKFILE.write_text(json.dumps(lock, indent=2) + "\n")
        print(f"Updated {LOCKFILE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
