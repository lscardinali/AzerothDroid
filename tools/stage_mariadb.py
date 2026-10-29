#!/usr/bin/env python3
"""Stage the Termux (Bionic, arm64) MariaDB server and client into the APK.

  * mariadbd and the mariadb client go to jniLibs as libmariadbd.so and
    libmariadb_client.so, together with every shared library they need
    (see tools/termux.py for the soname/symlink scheme);
  * the server's share data (charsets, English error messages) goes to
    assets/mariadb/share, plus bootstrap.sql, which creates the `mysql` system schema
    (the equivalent of mariadb-install-db, which needs Perl).

Output: native/out/jniLibs/arm64-v8a/ and native/out/assets/mariadb/.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import termux  # noqa: E402

EXECUTABLES = {
    "bin/mariadbd": "libmariadbd.so",
    "bin/mariadb": "libmariadb_client.so",
}

# Files from share/mariadb needed to build the system schema, in load order.
BOOTSTRAP_SQL = [
    "mariadb_system_tables.sql",
    "mariadb_performance_tables.sql",
    "mariadb_system_tables_data.sql",
    "fill_help_tables.sql",
    "maria_add_gis_sp_bootstrap.sql",
    "mariadb_sys_schema.sql",
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--update", action="store_true", help="re-resolve tools/termux.lock.json from the live index")
    args = parser.parse_args()

    lock = termux.load_lock(update=args.update)
    usr = termux.sysroot(lock)

    links = termux.stage_closure({usr / path: name for path, name in EXECUTABLES.items()}, usr)

    assets = termux.ASSETS / "mariadb"
    if assets.exists():
        shutil.rmtree(assets)
    share = usr / "share" / "mariadb"
    shutil.copytree(share / "charsets", assets / "share" / "charsets")
    shutil.copytree(share / "english", assets / "share" / "english")
    parts = ["CREATE DATABASE IF NOT EXISTS mysql;", "USE mysql;", "SET @auth_root_socket=NULL;"]
    parts += [(share / name).read_text(encoding="utf-8") for name in BOOTSTRAP_SQL]
    (assets / "bootstrap.sql").write_text("\n".join(parts) + "\n", encoding="utf-8")

    version = lock["packages"]["mariadb"]["version"]
    termux.write_manifest("mariadb", {
        "version": version,
        "executables": {path.split("/")[-1]: name for path, name in EXECUTABLES.items()},
        "links": links,
        "bootstrapSha256": termux.sha256(assets / "bootstrap.sql"),
    })
    print(f"Staged MariaDB {version}: {len(EXECUTABLES)} executables, {len(links)} libraries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
