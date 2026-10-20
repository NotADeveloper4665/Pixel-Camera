package org.eclipse.pixelcamera.fusion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** All Camera2 state is confined to cameraHandler. Processing never blocks it. */
final class CameraController implements AutoCloseable {
    interface Listener {
        void ready(CameraProfile profile);
        void zoomChanged(float target, float cameraZoom, float residual);
        void busy(boolean busy);
        void status(String status);
        void saved(Uri uri, String summary);
    }
    private static final int MAX_JPEG_BYTES = 12_000_000;
    private final Context context;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("Pixel9aCamera");
    private final Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService processor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private CaptureRequest.Builder previewBuilder;
    private CameraProfile profile;
    private float zoom = 10f, captureZoom;
    private int exposure = 0;
    private boolean busy, waiting3a, collecting, sequenceDone;
    private int serial, burstSerial, expected, failures;
    private int previewAf;
    private MeteringRectangle[] focusRegions;
    private final NavigableMap<Long, byte[]> jpegByTimestamp = new TreeMap<>();
    private final Map<Long, TotalCaptureResult> resultByTimestamp = new HashMap<>();
    private final List<CapturedFrame> paired = new ArrayList<>();
    private Future<?> processingJob;
    private Runnable focusTimeout, burstTimeout;

    CameraController(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        thread.start(); cameraHandler = new Handler(thread.getLooper());
    }
    private void ui(Runnable action) {
        mainHandler.post(() -> { if (!closed.get()) action.run(); });
    }
    private void status(String message) { ui(() -> listener.status(message)); }
    private void setBusy(boolean value) { busy = value; ui(() -> listener.busy(value)); }

    @SuppressLint("MissingPermission")
    void open(SurfaceTexture texture) {
        cameraHandler.post(() -> {
            if (closed.get()) return;
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                profile = CameraProfile.discover(manager);
                zoom = Math.max(profile.minZoom, zoom);
                texture.setDefaultBufferSize(profile.preview.getWidth(), profile.preview.getHeight());
                previewSurface = new Surface(texture);
                reader = ImageReader.newInstance(profile.jpeg.getWidth(), profile.jpeg.getHeight(), ImageFormat.JPEG, 3);
                reader.setOnImageAvailableListener(this::receiveJpeg, cameraHandler);
                manager.openCamera(profile.id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice opened) {
                        if (closed.get()) { opened.close(); return; }
                        device = opened; configure();
                    }
                    @Override public void onDisconnected(CameraDevice disconnected) {
                        disconnected.close();
                        if (device == disconnected) { device = null; session = null; }
                        fail("Camera disconnected. Reopen Fusion to retry.");
                    }
                    @Override public void onError(CameraDevice camera, int error) {
                        camera.close();
                        if (device == camera) { device = null; session = null; }
                        fail("Camera unavailable (" + error + "). Close another camera app and reopen Fusion.");
                    }
                }, cameraHandler);
            } catch (Exception e) { fail("Cannot open camera: " + e.getMessage()); }
        });
    }
    private void configure() {
        try {
            device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (closed.get() || device == null) { configured.close(); return; }
                            session = configured;
                            try {
                                previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                previewBuilder.addTarget(previewSurface);
                                int[] afModes = profile.characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                                previewAf = CameraProfile.contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ?
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE :
                                        profile.autoFocus ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF;
                                resetPreview();
                                ui(() -> listener.ready(profile));
                                reportZoom();
                                status("Ready · hold still for the burst");
                            } catch (Exception e) { fail("Preview failed: " + e.getMessage()); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession failed) {
                            failed.close(); fail("Preview/JPEG combination rejected by the camera");
                        }
                    }, cameraHandler);
        } catch (Exception e) { fail("Session failed: " + e.getMessage()); }
    }
    private Rect cameraCrop(float hardwareZoom) {
        Rect a = profile.activeArray;
        int width = Math.max(2, Math.round(a.width()/hardwareZoom));
        int height = Math.max(2, Math.round(a.height()/hardwareZoom));
        int left = a.left + (a.width()-width)/2, top = a.top + (a.height()-height)/2;
        return new Rect(left, top, left+width, top+height);
    }
    private void common(CaptureRequest.Builder builder, float target) {
        ZoomPlan plan = ZoomPlan.create(target, profile.minZoom, profile.maxZoom);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure);
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        if (profile.zoomRatio) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, plan.hardware);
        else builder.set(CaptureRequest.SCALER_CROP_REGION, cameraCrop(plan.hardware));
        if (profile.opticalStabilization)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        if (profile.afRegions > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, focusRegions);
        if (profile.aeRegions > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, focusRegions);
    }
    private final CameraCaptureSession.CaptureCallback previewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
            if (!waiting3a || closed.get()) return;
            Integer af = result.get(CaptureResult.CONTROL_AF_STATE), ae = result.get(CaptureResult.CONTROL_AE_STATE);
            boolean focusReady = !profile.autoFocus || (af != null && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED));
            boolean exposureReady = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED;
            if (focusReady && exposureReady) submitBurst();
        }
    };
    private void resetPreview() throws CameraAccessException {
        if (session == null || previewBuilder == null || closed.get()) return;
        common(previewBuilder, zoom);
        if (profile.aeLock) previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        if (profile.awbLock) previewBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        session.capture(previewBuilder.build(), null, cameraHandler);
        previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, previewAf);
        previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        session.setRepeatingRequest(previewBuilder.build(), previewCallback, cameraHandler);
    }
    private void reportZoom() {
        ZoomPlan plan = ZoomPlan.create(zoom, profile.minZoom, profile.maxZoom);
        ui(() -> listener.zoomChanged(zoom, plan.hardware, plan.residual));
    }
    void setZoom(float target) {
        cameraHandler.post(() -> {
            if (busy || profile == null || previewBuilder == null || closed.get()) return;
            try {
                ZoomPlan.create(target, profile.minZoom, profile.maxZoom);
                zoom = target; focusRegions = null; resetPreview(); reportZoom();
            } catch (Exception e) { status(e.getMessage()); }
        });
    }
    void setExposure(int compensation) {
        cameraHandler.post(() -> {
            if (busy || profile == null || previewBuilder == null || closed.get()) return;
            exposure = profile.exposureRange.clamp(compensation);
            try { common(previewBuilder, zoom); session.setRepeatingRequest(previewBuilder.build(), previewCallback, cameraHandler); }
            catch (Exception e) { status("Exposure failed: " + e.getMessage()); }
        });
    }
    /** Normalized SENSOR coordinates after undoing the display transform. */
    void focus(float x, float y) {
        cameraHandler.post(() -> {
            if (busy || session == null || previewBuilder == null || closed.get()) return;
            try {
                Rect array = profile.zoomRatio ? profile.activeArray :
                        cameraCrop(ZoomPlan.create(zoom, profile.minZoom, profile.maxZoom).hardware);
                int side = Math.max(16, Math.min(array.width(), array.height())/12);
                int left = Math.max(array.left, Math.min(array.right-side, array.left + (int)(x*array.width()) - side/2));
                int top = Math.max(array.top, Math.min(array.bottom-side, array.top + (int)(y*array.height()) - side/2));
                focusRegions = new MeteringRectangle[] {new MeteringRectangle(new Rect(left, top, left+side, top+side), 900)};
                common(previewBuilder, zoom);
                if (profile.autoFocus) {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                }
                session.capture(previewBuilder.build(), null, cameraHandler);
                previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                session.setRepeatingRequest(previewBuilder.build(), previewCallback, cameraHandler);
                status("Focus and exposure metering updated");
                cameraHandler.postDelayed(() -> {
                    if (!busy && !closed.get()) try { resetPreview(); } catch (Exception ignored) { }
                }, 2500);
            } catch (Exception e) { status("Focus failed: " + e.getMessage()); }
        });
    }
    void capture() {
        cameraHandler.post(() -> {
            if (busy || session == null || device == null || previewBuilder == null || closed.get()) return;
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            int thermal = power == null ? PowerManager.THERMAL_STATUS_NONE : power.getCurrentThermalStatus();
            if (thermal >= PowerManager.THERMAL_STATUS_CRITICAL) { status("Phone is too hot. Let it cool before capturing."); return; }
            expected = thermal >= PowerManager.THERMAL_STATUS_MODERATE ||
                    Runtime.getRuntime().maxMemory() < 256_000_000L ? 3 : Fusion.MAX_FRAMES;
            setBusy(true); captureZoom = zoom; waiting3a = true;
            status("Focusing · " + expected + "-frame capture");
            try {
                common(previewBuilder, zoom);
                if (profile.autoFocus) {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    session.capture(previewBuilder.build(), previewCallback, cameraHandler);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                }
                session.setRepeatingRequest(previewBuilder.build(), previewCallback, cameraHandler);
                focusTimeout = () -> { if (waiting3a && !closed.get()) submitBurst(); };
                cameraHandler.postDelayed(focusTimeout, 1400);
            } catch (Exception e) { fail("Could not start capture: " + e.getMessage()); }
        });
    }
    private void submitBurst() {
        if (!waiting3a || closed.get() || session == null || device == null) return;
        waiting3a = false;
        if (focusTimeout != null) cameraHandler.removeCallbacks(focusTimeout);
        collecting = true; sequenceDone = false; failures = 0; burstSerial = ++serial;
        jpegByTimestamp.clear(); resultByTimestamp.clear(); paired.clear();
        final int tag = burstSerial;
        try {
            List<CaptureRequest> requests = new ArrayList<>();
            for (int i = 0; i < expected; i++) {
                CaptureRequest.Builder shot = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                shot.addTarget(reader.getSurface()); common(shot, captureZoom);
                shot.set(CaptureRequest.CONTROL_AF_MODE, profile.autoFocus ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_OFF);
                if (profile.aeLock) shot.set(CaptureRequest.CONTROL_AE_LOCK, true);
                if (profile.awbLock) shot.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                // Always receive sensor-oriented pixels; rotate the final merged image once.
                shot.set(CaptureRequest.JPEG_ORIENTATION, 0);
                shot.set(CaptureRequest.JPEG_QUALITY, (byte)95); shot.setTag(tag);
                requests.add(shot.build());
            }
            session.stopRepeating();
            session.captureBurst(requests, new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult result) {
                    if (!collecting || tag != burstSerial || closed.get()) return;
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (timestamp != null) { resultByTimestamp.put(timestamp, result); matchFrames(); }
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest request, CaptureFailure failure) {
                    if (collecting && tag == burstSerial) { failures++; maybeFinish(); }
                }
                @Override public void onCaptureSequenceCompleted(CameraCaptureSession s, int id, long frameNumber) {
                    if (collecting && tag == burstSerial) { sequenceDone = true; maybeFinish(); }
                }
                @Override public void onCaptureSequenceAborted(CameraCaptureSession s, int id) {
                    if (collecting && tag == burstSerial) finishBurst();
                }
            }, cameraHandler);
            burstTimeout = () -> { if (collecting && tag == burstSerial) finishBurst(); };
            cameraHandler.postDelayed(burstTimeout, 8000);
            status("Capturing " + expected + " frames · hold still");
        } catch (Exception e) { fail("Burst failed: " + e.getMessage()); }
    }
    private void receiveJpeg(ImageReader source) {
        try {
            Image image;
            while ((image = source.acquireNextImage()) != null) {
                try {
                    if (!collecting || closed.get() || paired.size() >= expected) continue;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    if (buffer.remaining() > MAX_JPEG_BYTES) { fail("Camera JPEG exceeds the capture memory budget"); continue; }
                    byte[] data = new byte[buffer.remaining()]; buffer.get(data);
                    jpegByTimestamp.put(image.getTimestamp(), data);
                    while (jpegByTimestamp.size() > Fusion.MAX_FRAMES) jpegByTimestamp.pollFirstEntry();
                } finally { image.close(); }
                matchFrames();
            }
        } catch (IllegalStateException | OutOfMemoryError e) {
            if (!closed.get()) fail("Image reader failed: " + e.getMessage());
        }
    }
    private void matchFrames() {
        Iterator<Map.Entry<Long, byte[]>> iterator = jpegByTimestamp.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, byte[]> entry = iterator.next();
            TotalCaptureResult result = resultByTimestamp.remove(entry.getKey());
            if (result == null) continue;
            Float applied = profile.zoomRatio ? result.get(CaptureResult.CONTROL_ZOOM_RATIO) : null;
            if (applied == null) {
                Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
                if (crop != null && crop.width() > 0) applied = profile.activeArray.width()/(float)crop.width();
            }
            if (applied != null && applied > 0 && Float.isFinite(applied)) {
                Long exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                paired.add(new CapturedFrame(entry.getValue(), entry.getKey(), applied,
                        exposureNs == null ? 0 : exposureNs, iso == null ? 0 : iso));
            }
            iterator.remove();
        }
        maybeFinish();
    }
    private void maybeFinish() {
        if (collecting && sequenceDone && paired.size() >= expected - failures) finishBurst();
    }
    private void finishBurst() {
        if (!collecting || closed.get()) return;
        collecting = false;
        if (burstTimeout != null) cameraHandler.removeCallbacks(burstTimeout);
        jpegByTimestamp.clear(); resultByTimestamp.clear();
        if (!sequenceDone && session != null) try { session.abortCaptures(); } catch (Exception ignored) { }
        try { resetPreview(); } catch (Exception ignored) { }
        if (paired.isEmpty()) { fail("No JPEG/result timestamp pairs received. Capture cancelled."); return; }
        List<CapturedFrame> frames = new ArrayList<>(paired); paired.clear();
        float target = captureZoom; int orientation = profile.orientation;
        String capabilities = profile.describe();
        status("Aligning and fusing " + frames.size() + " frames…");
        try {
            processingJob = processor.submit(() -> {
                try {
                    FusionProcessor.Saved saved = FusionProcessor.process(context, frames, target, orientation, capabilities);
                    ui(() -> listener.saved(saved.uri, saved.summary));
                } catch (CancellationException ignored) {
                } catch (Exception | OutOfMemoryError e) {
                    status("Processing failed: " + e.getMessage());
                } finally {
                    frames.clear();
                    cameraHandler.post(() -> { if (!closed.get()) setBusy(false); });
                }
            });
        } catch (RejectedExecutionException e) {
            frames.clear();
            if (!closed.get()) fail("Processing worker is unavailable");
        }
    }
    private void fail(String message) {
        boolean abort = collecting;
        waiting3a = false; collecting = false;
        if (abort && session != null) try { session.abortCaptures(); } catch (Exception ignored) { }
        if (focusTimeout != null) cameraHandler.removeCallbacks(focusTimeout);
        if (burstTimeout != null) cameraHandler.removeCallbacks(burstTimeout);
        jpegByTimestamp.clear(); resultByTimestamp.clear(); paired.clear();
        try { resetPreview(); } catch (Exception ignored) { }
        setBusy(false); status(message);
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (processingJob != null) processingJob.cancel(true);
        processor.shutdownNow();
        cameraHandler.post(() -> {
            waiting3a = false; collecting = false;
            if (focusTimeout != null) cameraHandler.removeCallbacks(focusTimeout);
            if (burstTimeout != null) cameraHandler.removeCallbacks(burstTimeout);
            if (session != null) { session.close(); session = null; }
            if (device != null) { device.close(); device = null; }
            if (reader != null) { reader.close(); reader = null; }
            if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
            jpegByTimestamp.clear(); resultByTimestamp.clear(); paired.clear();
            thread.quitSafely();
        });
    }
}
