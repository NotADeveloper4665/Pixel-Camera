#!/usr/bin/env python3
"""Extract the SDK-only fusion DEX from our locally built APK for Morphe injection."""
import argparse
import hashlib
from pathlib import Path
import zipfile

def extract(apk: Path, output: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        dex_files = [name for name in archive.namelist()
                     if name.startswith("classes") and name.endswith(".dex") and "/" not in name]
        if dex_files != ["classes.dex"]:
            raise SystemExit(f"Expected one SDK-only DEX, found {dex_files}")
        dex = archive.read("classes.dex")
    if not dex.startswith(b"dex\n"):
        raise SystemExit("Invalid DEX header")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(dex)
    print(f"{output}: {len(dex)} bytes, sha256={hashlib.sha256(dex).hexdigest()}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    extract(args.apk, args.output)
