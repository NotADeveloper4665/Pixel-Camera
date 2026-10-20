# Pixel Camera — Pixel 9a Fusion

Pixel 9a specialization of [Akshayykadam/Pixel-Camera](https://github.com/Akshayykadam/Pixel-Camera).
The original patch sources and GPL-3.0 license are retained. New capture code lives in
[pixel9a-fusion](pixel9a-fusion/).

**Status: experimental source implementation; Pixel 9a camera and patched-host testing are still required.**
Build results and installable test artifacts appear under [Actions](https://github.com/NotADeveloper4665/Pixel-Camera/actions).

## What this fork adds

- A real Camera2 capture activity for Pixel 9a, built from source using public Android APIs.
- 3–5 frame JPEG bursts with capture-result/image timestamp pairing.
- Pyramid translation alignment, subpixel registration, per-pixel motion rejection, and temporal averaging in linear RGB.
- Experimental 10× framing: query the camera zoom range, request the supported portion, then crop only the remaining ratio.
- Tap focus/metering, exposure compensation, OIS when exposed, thermal limits, cancellation, and bounded image buffers.
- Atomic MediaStore saving to `DCIM/Pixel9aFusion` with capture diagnostics in EXIF UserComment.
- Director's View / Multi-Record: two live lenses and two recorded clips, with shared optional microphone audio.
- Single Take: eight seconds from main + ultrawide, selected stills, automatic tone presets, crops, and person-aware bokeh.
- A complete Studio APK containing all three modes, plus the SDK-only Fusion APK and Morphe extension.

See [Studio implementation and limits](pixel9a-fusion/studio/README.md).
Simultaneous modes are enabled only after Android accepts and delivers both streams.
Single Take stills use the video stream resolution, up to 720p; neural person segmentation drives portrait edits.

The new activity has its own **Pixel 9a Fusion** launcher entry. It does not replace Google's native shutter pipeline.
The original camera activity remains available in the patched APK. The Studio APK includes Fusion and the new modes together. The patched camera's Director's View and Single Take
buttons open the installed Studio package, which includes ML Kit's model, resources, and native libraries.

## Pixel 9a preset

The default selection is:

1. **Pixel 9a Multi-Frame Fusion**
2. **Pixel Camera Clone (Non-Root)**

Other upstream patches remain available but are disabled by default. In particular, the original
**10x Viewfinder Quick Zoom** patch replaces controllers for telephoto Pro devices; it is not the fusion implementation.
The **Pixel 10 Photo Saving Fix** is also not part of this device preset.

`pixel9a-preset.json` records this selection. It is documentation/build metadata, not a Morphe Manager import file.

## Build

Requirements: JDK 17, Android SDK 35/build-tools 35.0.0, Python 3, and credentials permitted to read Morphe's
GitHub Packages dependencies in your normal build environment.

```bash
./tools/build_pixel9a.sh
```

This runs fusion and Studio unit tests and Android lint, builds both debug APKs, extracts the SDK-only extension DEX,
verifies its classes, and builds the Morphe package. A failed task fails the build.

Outputs:

| Output | Purpose |
| --- | --- |
| `pixel9a-fusion/app/build/outputs/apk/debug/app-debug.apk` | SDK-only Fusion test app |
| `pixel9a-fusion/studio/build/outputs/apk/debug/studio-debug.apk` | Complete Studio app: Fusion, Director's View, Single Take |
| `morphe-patches/patches/build/libs/*.mpp` | Patch bundle containing the fusion activity |
| Test/lint reports | Build checks, not phone camera validation |

The same process is configured in GitHub Actions. Debug APK signing is for development; reinstalling across
fresh CI signing keys may require uninstalling the previous debug build.

## Install and try

For all modes, install the Studio build artifact on your Pixel 9a and grant camera permission. Microphone permission is requested when sound is enabled.
Choose **10×**, hold the phone still, then tap **Fuse 10×**. **Details** shows the actual exposed camera capabilities.

For a patched Google Camera APK, use the generated `.mpp` with the compatible base
`com.google.android.GoogleCamera 11.1.040.982810059.19`, selecting only the two preset patches initially.
A proprietary base APK is not included or downloaded by this project. A patched Google Camera APK cannot be produced
until a compatible base APK is provided to the patcher. The extension does not lower the proprietary base APK's
minimum Android version; its host SDK requirements still apply.

## Practical limits

- Pixel 9a has wide and ultrawide cameras, not a telephoto sensor. Google specifies stock Super Res Zoom up to 8×.
- This is multi-frame RGB fusion, not a reimplementation of Google's RAW Super Res Zoom, HDR+, or private TPU kernels.
- Output is capped at 1.5 million decoded pixels for this first implementation and is not artificially enlarged.
  The original camera's normal photos are unaffected.
- Translation alignment cannot fully correct large rotation, parallax, rolling shutter, or subject movement.
  Rejected areas/frames use the selected reference.
- No improvement over stock 8×/digital 10× is claimed without paired device measurements.
- Preview orientation, JPEG/result pairing, EXIF saving, host application initialization, and thermal behavior
  need verification on the actual Pixel 9a.

See [implementation and phone test checklist](pixel9a-fusion/README.md).
The inherited upstream documentation is preserved in [UPSTREAM_README.md](UPSTREAM_README.md);
its compatibility/performance claims are not test results for this fork.
