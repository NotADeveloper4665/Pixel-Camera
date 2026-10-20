#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./morphe-patches/gradlew -p pixel9a-fusion :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
python3 tools/prepare_fusion_dex.py \
    pixel9a-fusion/app/build/outputs/apk/debug/app-debug.apk \
    morphe-patches/patches/src/main/resources/Pixel9aFusion.dex
cp morphe-patches/patches/src/main/resources/Pixel9aFusion.dex morphe-patches/src/main/resources/Pixel9aFusion.dex
./morphe-patches/gradlew -p morphe-patches :patches:test --tests '*Pixel9aFusionDexTest' :patches:buildAndroid --no-daemon
