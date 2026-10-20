package org.eclipse.pixelcamera.fusion;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Comparator;

public final class CameraProfile {
    public final String id;
    public final CameraCharacteristics characteristics;
    public final Size jpeg, preview;
    public final Rect activeArray;
    public final float minZoom, maxZoom;
    public final boolean zoomRatio, aeLock, awbLock, autoFocus, opticalStabilization;
    public final int orientation, afRegions, aeRegions;
    public final Range<Integer> exposureRange;

    public static boolean isPixel9a() {
        return "tegu".equalsIgnoreCase(Build.DEVICE) || "Pixel 9a".equalsIgnoreCase(Build.MODEL);
    }
    public static CameraProfile discover(CameraManager manager) throws Exception {
        if (!isPixel9a()) throw new IllegalStateException("This build targets Pixel 9a. Detected: " + Build.MODEL);
        String chosen = null; int best = Integer.MIN_VALUE;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null || map.getOutputSizes(ImageFormat.JPEG) == null) continue;
            int score = 0;
            int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) score += 100;
            Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (range != null) { if (range.getLower() < 1f) score += 20; score += Math.min(15, range.getUpper().intValue()); }
            float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            if (focal != null && focal.length > 0 && focal[0] > 3f) score += 10;
            if (score > best) { chosen = id; best = score; }
        }
        if (chosen == null) throw new IllegalStateException("No compatible rear JPEG camera is exposed");
        return new CameraProfile(chosen, manager.getCameraCharacteristics(chosen));
    }
    private CameraProfile(String id, CameraCharacteristics c) {
        this.id = id; characteristics = c;
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("Missing camera stream configuration");
        jpeg = chooseJpeg(map.getOutputSizes(ImageFormat.JPEG));
        Size[] previews = map.getOutputSizes(SurfaceTexture.class);
        if (previews == null || previews.length == 0) throw new IllegalStateException("No preview stream");
        preview = Arrays.stream(previews)
                .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                .min(Comparator.comparingDouble((Size s) -> Math.abs(
                        s.getWidth() / (double) s.getHeight() - jpeg.getWidth() / (double) jpeg.getHeight()))
                        .thenComparing(Comparator.comparingLong((Size s) -> (long) s.getWidth() * s.getHeight()).reversed()))
                .orElse(previews[0]);
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null) throw new IllegalStateException("No sensor active array");
        activeArray = new Rect(active);
        Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        zoomRatio = range != null && c.getAvailableCaptureRequestKeys().contains(CaptureRequest.CONTROL_ZOOM_RATIO);
        Float digital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        minZoom = zoomRatio ? range.getLower() : 1f;
        maxZoom = zoomRatio ? range.getUpper() : digital == null ? 1f : Math.max(1f, digital);
        aeLock = Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
        awbLock = Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
        autoFocus = contains(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CameraMetadata.CONTROL_AF_MODE_AUTO);
        opticalStabilization = contains(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        Integer sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        orientation = sensor == null ? 90 : sensor;
        Integer af = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer ae = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        afRegions = af == null ? 0 : af; aeRegions = ae == null ? 0 : ae;
        Range<Integer> exposure = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        exposureRange = exposure == null ? new Range<>(0, 0) : exposure;
    }
    private static Size chooseJpeg(Size[] sizes) {
        if (sizes == null || sizes.length == 0) throw new IllegalStateException("No JPEG sizes");
        // Stay in the ordinary pixel mode. 48 MP and RAW availability are not assumed.
        return Arrays.stream(sizes).filter(s -> (long) s.getWidth() * s.getHeight() <= 12_600_000)
                .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                .orElseGet(() -> Arrays.stream(sizes).min(Comparator.comparingLong(
                        s -> (long) s.getWidth() * s.getHeight())).orElseThrow());
    }
    public static boolean contains(int[] values, int value) {
        if (values != null) for (int v : values) if (v == value) return true;
        return false;
    }
    public String describe() {
        try {
            JSONObject data = new JSONObject();
            data.put("model", Build.MODEL); data.put("device", Build.DEVICE);
            data.put("cameraId", id); data.put("jpeg", jpeg.toString());
            data.put("preview", preview.toString()); data.put("zoomMin", minZoom);
            data.put("zoomMax", maxZoom); data.put("zoomRatioApi", zoomRatio);
            data.put("aeLock", aeLock); data.put("awbLock", awbLock);
            data.put("ois", opticalStabilization); data.put("af", autoFocus);
            data.put("physicalIds", characteristics.getPhysicalCameraIds().toString());
            return data.toString(2);
        } catch (Exception e) { return e.toString(); }
    }
}
