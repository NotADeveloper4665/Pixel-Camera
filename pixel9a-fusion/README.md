# Pixel 9a capture and fusion module

## Architecture

`FusionActivity` → `CameraController` → timestamp-paired JPEG burst →
`FusionProcessor` → `Fusion` → MediaStore/EXIF.

The module uses only Android SDK classes and Java. Its DEX is included through Morphe's public
`extendWith` API. The resource patch registers an additional launcher activity inside the patched app.
There is no dependency on a Google-signed package, model extraction, root, or direct TPU device access.

The standalone app ID is `org.eclipse.pixelcamera.pixel9a`.
The injected activity's Java namespace is `org.eclipse.pixelcamera.fusion`; no existing native camera classes are replaced.

## Capture

- Check Pixel 9a device identity at runtime.
- Enumerate rear cameras; prefer a logical camera with an exposed zoom range.
- Read stream configurations and choose a normal-pixel-mode JPEG size no larger than 12.6 MP where available.
- Never assume a camera ID, full-resolution 48 MP access, an 8× public API ceiling, or a telephoto sensor.
- Query `CONTROL_ZOOM_RATIO_RANGE` and supported request keys. Fall back to supported
  `SCALER_CROP_REGION` digital zoom when necessary.
- Use a preview + JPEG capture session. Wait for focus/exposure convergence with a finite deadline.
- Capture five frames, or three under thermal/heap pressure. Refuse new captures at critical thermal status.
- Keep AE/AWB locked across the burst where supported. Use OIS only when advertised.
- Match JPEG `Image.timestamp` with `CaptureResult.SENSOR_TIMESTAMP`.
- Derive residual framing from the *reported applied* zoom. Exclude changing zoom fields of view.
- Close each Image immediately after copying its compressed JPEG. Limit JPEG buffers, decoder output, and burst count.
- Run alignment/merge and saving on a worker, leaving Camera2 callbacks responsive.
- Salvage matched partial bursts after timeout; fail cleanly if no timestamp pairs arrive.
- Release sessions, readers, surfaces, worker tasks, and the camera on activity pause.

The activity is portrait-only for this first version. The preview preserves its advertised aspect ratio and
applies only the remaining software crop. Saved images are rotated once from sensor orientation.

## Fusion algorithm

1. Decode the central residual crop from each JPEG, bounded to 1.5 MP.
2. Choose the frame with greatest sampled Laplacian energy as the reference.
3. Build grayscale image pyramids. Search for translation by zero-mean squared residual.
4. Refine in original pixel coordinates, then apply a local parabolic subpixel fit.
5. Reject frames with high registration error or excessive translation.
6. Resample aligned frames bilinearly. Reject pixels inconsistent with the reference.
7. Average accepted samples in linear RGB using robust residual weights.
8. Convert back to sRGB and save without enlargement. If fusion is rejected, keep reference pixels.

These are established registration and robust averaging techniques. They are a conservative RGB implementation,
not the RAW/CFA super-resolution reconstruction described in Google's paper. JPEG tone mapping and compression
already discard information; no missing sensor detail is synthesized.

## Automated checks

`FusionTest` covers known translation recovery, noise reduction with deterministic noise, motion rejection,
reference-only identity, flat scenes, invalid dimensions, memory limits, cancellation, and native/residual zoom separation.

Android lint and compilation check the SDK integration.
`Pixel9aFusionDexTest` verifies the built extension contains the activity and capture classes in its own namespace.
`check_pixel9a_package.py` verifies the final patch bundle actually contains the generated extension.

These checks do not prove camera operation on a physical phone or verify proprietary host initialization.

## Phone verification checklist

1. Install the standalone APK on Pixel 9a, grant camera permission, inspect Details, and record firmware/build.
2. Verify 0.5× is offered only if the selected camera's public zoom range supports it.
3. Compare preview/capture field of view and orientation at 1×, 2×, 8×, 10×.
4. Verify tap focus and EV changes, then capture five times consecutively.
5. Verify photos save, open from Last photo, and contain fusion metadata in EXIF UserComment.
6. Compare the same daylight detail chart using stock 8×, a corresponding 10× crop, a single Camera2 frame,
   and the fusion result. Measure detail and noise rather than comparing enlarged thumbnails.
7. Repeat with moving subjects, low light, textured scenes, plain walls, handheld movement, and a tripod.
8. Check that phone calls/backgrounding/permission revocation interrupt safely, with no permanently pending photo.
9. Observe heap, processing time, temperature, and battery consumption; verify three-frame and thermal refusal paths.
10. Build a patched APK from the specified base version and preset, then repeat the same checks using the additional
    launcher activity. Confirm native Google Camera still works after returning from Fusion.

## Known limits

Global translation does not model perspective warps or rolling shutter.
The image-size cap limits processing cost and output resolution.
No public API guarantees Pixel 9a third-party access to Google's stock HDR+/Super Res implementation.
No tensor/neural acceleration is assumed. Other currently broken upstream features have not been declared fixed.

## Primary references

- [Pixel 9a specifications](https://store.google.com/product/pixel_9a_specs)
- [Android CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
- [Camera capture sessions and requests](https://developer.android.com/media/camera/camera2/capture-sessions-requests)
- [CameraCaptureSession.captureBurst](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession)
- [CaptureRequest zoom, crop, locks, and metering](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
- [Android logical multi-camera support](https://source.android.com/docs/core/camera/multi-camera)
- [Google Research: Handheld Multi-Frame Super-Resolution](https://research.google/pubs/handheld-multi-frame-super-resolution/)
- [Android AGP 8.8 compatibility](https://developer.android.com/build/releases/agp-8-8-0-release-notes)
- [Android ExifInterface](https://developer.android.com/reference/android/media/ExifInterface)

New code is distributed under the repository's GPL-3.0 license.
