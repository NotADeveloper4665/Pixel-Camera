#!/usr/bin/env python3
"""Fail CI if the built patch bundle does not contain the actual fusion code."""
from pathlib import Path
import zipfile

bundles = [p for p in Path("morphe-patches/patches/build/libs").glob("*.mpp")
           if not p.stem.endswith(("-sources", "-javadoc"))]
if len(bundles) != 1:
    raise SystemExit(f"Expected one patch bundle, got {bundles}")
with zipfile.ZipFile(bundles[0]) as archive:
    names = archive.namelist()
    for required in ["Pixel9aFusion.dex",
                     "app/morphe/patches/pixelcamera/fusion/Pixel9aFusionPatchKt.class"]:
        if required not in names:
            raise SystemExit(f"Missing {required} in {bundles[0]}")
    if not any(name.startswith("classes") and name.endswith(".dex") for name in names):
        raise SystemExit("Patch bundle has no Android DEX")
print(f"Verified fusion code and Android patches in {bundles[0]}")
