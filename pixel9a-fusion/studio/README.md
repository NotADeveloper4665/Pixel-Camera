# Pixel 9a Studio: Director's View, Multi-Record, Single Take

This module builds a complete Android application containing Fusion plus the new modes.
It bundles Google's ML Kit person segmentation model. The standalone Studio APK includes its
dependency code, Android resources, model assets, and native libraries through the Android build system.

**Source implementation only: compilation and Pixel 9a hardware verification are pending.**

## Director's View / Multi-Record

- Two live views: main + ultrawide through a logical camera's physical streams, or front + back through an
  Android-advertised concurrent camera combination.
- Main/ultrawide IDs are chosen from rear physical cameras by sensor width/focal-length ratio.
  The Pixel 9a identity check prevents applying that two-rear-lens assumption to telephoto devices.
- Output configurations explicitly target the physical IDs. Front/back sessions must pass
  `CameraManager.isConcurrentSessionConfigurationSupported`. Physical output configurations are queried on the open camera
  where supported, then must successfully configure and deliver both image streams before recording can start.
- Frames from two YUV readers feed two H.264 MediaCodec encoders and separate MP4 files.
- An optional single microphone recording is muxed into both clips after capture.
- The JSON take report records camera IDs, physical IDs, stream sizes, first sensor timestamps, first host arrival times,
  encoded/dropped frame counts, microphone timing, and the selected mode.
- Resolution is selected from advertised YUV sizes, up to 1280×720 per camera. Preview conversion is throttled independently.
- Multi-Record currently stops at 60 seconds or severe thermal status. It can be stopped earlier.

The simultaneous feeds are real lens streams. A firmware configuration that rejects a pair disables recording
and shows an error; it does not substitute sequential camera switching. A Reconnect control allows retrying a transient failure.

## Single Take

Select main + ultrawide and press **Capture Single Take** once.

1. Both lenses record an eight-second clip from the same simultaneous capture sessions.
2. Each stream is sampled about once per second. Blur/exposure scoring retains its two highest-scoring candidates.
3. Each retained frame produces an original still, an automatic tone preset, and a 4:5 crop.
4. The bundled ML Kit model identifies people. A confident person mask drives the crop center,
   selects the portrait tone preset, and adds a background-blurred portrait variant.
5. Images, both clips, and a take report remain pending until the export is complete.

Stills come from the video YUV streams; they have the stream's resolution, not full sensor photo resolution.
Tone presets and frame-quality scoring are deterministic algorithms; the person mask is neural inference.
The model handles people, so bokeh is skipped when no confident person is found. Other scenes still receive
the original, vivid tone, and composition crop. No cloud call or model download is needed for segmentation. If model initialization or inference fails, the take still exports its videos, originals, tone presets, and center crops, with the AI failure recorded in its report.

## Audio and synchronization

Both video files receive the same microphone recording when sound is enabled. Timestamp alignment uses
microphone start and video host arrival estimates. The JSON explicitly identifies this estimate;
it is not a guarantee of sample-accurate audio alignment or hardware frame synchronization.
Original sensor timestamps are retained for analyzing inter-camera timing.

The front live view is mirrored for framing; saved front video/stills use normal camera orientation.

## Installation and patch integration

Install the `pixel9a-studio-debug-apk` artifact. It contains Fusion, Director's View, and Single Take in one APK.
The separate SDK-only Fusion APK is not required for Studio.

The original patch extension stays SDK-only. Its new mode buttons explicitly launch the installed Studio package.
This avoids copying only ML Kit bytecode into Google Camera while omitting its model/assets/native libraries.
To use Director's View or Single Take from a patched Google Camera installation, install Studio alongside it.

## Build and checks

```bash
./morphe-patches/gradlew -p pixel9a-fusion :studio:testDebugUnitTest :studio:lintDebug :studio:assembleDebug
```

The workflow uploads the Studio APK separately from the SDK-only fusion DEX and Morphe package.
`TakeSelectionTest` checks frame-quality ordering, clipping penalties, invalid image planes, and crop centroid bounds.
Existing fusion and patch tests continue to run for their own outputs.

Hardware checks still required:

- Both exposed lens pairs, real field-of-view differences, size negotiation, reconnect, and switching pairs.
- Independent clip playback/orientation, duration, timestamp spacing, shared microphone tracks, and audible sync.
- Single Take clips plus stills from each rear lens, people masks, scene-without-person behavior, group portraits, and crops.
- Stops on pause/thermal pressure, codec unavailability, full storage, exports, and resource release.
- Confirm no incomplete gallery outputs after a failed export.

## Primary API references

- [Concurrent Camera2 camera IDs and configuration checks](https://developer.android.com/reference/android/hardware/camera2/CameraManager)
- [Logical and physical multi-camera API](https://developer.android.com/media/camera/camera2/multi-camera)
- [OutputConfiguration.setPhysicalCameraId](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration)
- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)
- [ML Kit bundled selfie segmentation](https://developers.google.com/ml-kit/vision/selfie-segmentation/android)
