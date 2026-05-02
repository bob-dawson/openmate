#!/usr/bin/env python3
"""Pull Android Room DB from device to local for analysis.

Usage:
  python pull_android_db.py [--db-name <name>]
  
If --db-name is not specified, it will auto-detect the first instance_* file.
"""

import subprocess
import sys
import os
import re

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

OUT_DIR = r"D:\openmate\temp_db_dir"
PACKAGE = "com.openmate"
DB_DIR = f"/data/user/0/{PACKAGE}/databases"


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    return r.stdout.strip(), r.stderr.strip(), r.returncode


def main():
    out, err, rc = run(["adb", "shell", "am", "force-stop", PACKAGE])
    if rc != 0:
        print(f"Failed to stop app: {err}")
        sys.exit(1)
    print("App stopped.")

    out, err, rc = run(["adb", "shell", f"run-as {PACKAGE} ls {DB_DIR}/"])
    if rc != 0:
        print(f"Failed to list DB dir: {err}")
        sys.exit(1)

    db_files = [f for f in out.splitlines() if f.startswith("instance_") and "-wal" not in f and "-shm" not in f and ".bak" not in f]
    if not db_files:
        print(f"No instance DB found. Files in {DB_DIR}:")
        print(out)
        sys.exit(1)

    db_name = db_files[0]
    print(f"DB file: {db_name}")

    os.makedirs(OUT_DIR, exist_ok=True)

    for suffix in ["", "-wal", "-shm"]:
        remote = f"{DB_DIR}/{db_name}{suffix}"
        local = os.path.join(OUT_DIR, f"instance.db{suffix}")
        out_path = os.path.join(OUT_DIR, "instance.db")
        remote_path = f"{DB_DIR}/{db_name}{suffix}"
        local_path = os.path.join(OUT_DIR, f"instance.db{suffix}")

        result = subprocess.run(
            ["adb", "exec-out", "run-as", PACKAGE, "cat", remote_path],
            capture_output=True, timeout=60,
        )
        with open(local_path, "wb") as f:
            f.write(result.stdout)
        size = os.path.getsize(local_path)
        print(f"  {local_path} ({size} bytes)")

    print(f"\nDone. Analyze with:")
    print(f"  python D:\\openmate\\scripts\\analyze_android_db.py {os.path.join(OUT_DIR, 'instance.db')}")


if __name__ == "__main__":
    main()
