package org.eclipse.pixelcamera.fusion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SDK-only activity: the same DEX can run standalone or inside the patched camera. */
public final class FusionActivity extends Activity implements CameraController.Listener {
    private TextureView texture;
    private FrameLayout viewport;
    private TextView status, detail, evLabel;
    private Button shutter, gallery;
    private SeekBar ev;
    private View focusRing;
    private final List<Button> zoomButtons = new ArrayList<>();
    private CameraController camera;
    private CameraProfile profile;
    private Uri lastPhoto;
    private float targetZoom = 10f, residual = 1f;
    private boolean resumed, capturing;
    private int evLower;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        TextView title = label("PIXEL 9a  /  FUSION", 20); title.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.addView(title);
        detail = label("Experimental multi-frame camera", 13);
        detail.setPadding(dp(16), 0, dp(16), dp(8)); root.addView(detail);
        viewport = new FrameLayout(this);
        root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        texture = new TextureView(this); viewport.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        focusRing = new View(this);
        GradientDrawable ring = new GradientDrawable(); ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(25)); ring.setStroke(dp(2), Color.rgb(183, 157, 255));
        focusRing.setBackground(ring); focusRing.setVisibility(View.GONE);
        viewport.addView(focusRing, new FrameLayout.LayoutParams(dp(50), dp(50)));
        texture.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP && camera != null && profile != null && !capturing) {
                float x = (event.getX()/view.getWidth() - .5f)/residual + .5f;
                float y = (event.getY()/view.getHeight() - .5f)/residual + .5f;
                float sx = x, sy = y;
                if (profile.orientation == 90) { sx = y; sy = 1-x; }
                else if (profile.orientation == 270) { sx = 1-y; sy = x; }
                else if (profile.orientation == 180) { sx = 1-x; sy = 1-y; }
                camera.focus(Math.max(0, Math.min(1, sx)), Math.max(0, Math.min(1, sy)));
                focusRing.setX(texture.getLeft()+event.getX()-dp(25));
                focusRing.setY(texture.getTop()+event.getY()-dp(25)); focusRing.setVisibility(View.VISIBLE);
                focusRing.removeCallbacks(hideFocus); focusRing.postDelayed(hideFocus, 900);
                view.performClick();
            }
            return true;
        });
        status = label("Opening camera…", 14);
        status.setMinHeight(dp(54)); status.setPadding(dp(16), dp(8), dp(16), dp(4)); root.addView(status);
        LinearLayout zoomRow = new LinearLayout(this); zoomRow.setGravity(Gravity.CENTER);
        for (float zoom : new float[]{.5f, 1f, 2f, 8f, 10f}) {
            Button button = new Button(this);
            button.setText(String.format(Locale.US, "%s×", zoom < 1 ? "0.5" : Integer.toString((int)zoom)));
            button.setTextColor(Color.WHITE); button.setEnabled(false); button.setTag(zoom);
            button.setOnClickListener(v -> { if (camera != null) camera.setZoom(zoom); });
            zoomButtons.add(button); zoomRow.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        root.addView(zoomRow);
        evLabel = label("Exposure · auto", 12); evLabel.setPadding(dp(16), 0, dp(16), 0); root.addView(evLabel);
        ev = new SeekBar(this); ev.setEnabled(false); root.addView(ev);
        ev.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && camera != null && profile != null) {
                    int compensation = progress + evLower;
                    camera.setExposure(compensation);
                    android.util.Rational step = profile.characteristics.get(
                            android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                    evLabel.setText(String.format(Locale.US, "Exposure · %+.1f EV", compensation * (step == null ? 0 : step.floatValue())));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        shutter = new Button(this); shutter.setText("Fuse 10×"); shutter.setEnabled(false);
        shutter.setTextColor(Color.BLACK); shutter.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(183, 157, 255)));
        LinearLayout.LayoutParams shutterLayout = new LinearLayout.LayoutParams(-1, dp(58));
        shutterLayout.setMargins(dp(16), dp(6), dp(16), dp(6)); root.addView(shutter, shutterLayout);
        shutter.setOnClickListener(v -> { if (camera != null) camera.capture(); });
        LinearLayout tools = new LinearLayout(this);
        gallery = new Button(this); gallery.setText("Last photo"); gallery.setEnabled(false);
        gallery.setOnClickListener(v -> {
            if (lastPhoto == null) return;
            try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(lastPhoto, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
            catch (ActivityNotFoundException e) { status("No photo viewer is installed"); }
        });
        Button nativeCamera = new Button(this); nativeCamera.setText("Stock camera");
        nativeCamera.setOnClickListener(v -> {
            try { startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage("com.google.android.GoogleCamera")); }
            catch (ActivityNotFoundException e) { status("Stock Google Camera is not available. Open your usual camera from the launcher."); }
        });
        Button diagnostics = new Button(this); diagnostics.setText("Details");
        diagnostics.setOnClickListener(v -> {
            String text = profile == null ? android.os.Build.MODEL : profile.describe();
            new AlertDialog.Builder(this).setTitle("Camera capabilities").setMessage(text)
                    .setPositiveButton("Copy", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("Pixel 9a Camera2", text));
                    }).setNegativeButton("Close", null).show();
        });
        tools.addView(gallery, new LinearLayout.LayoutParams(0, dp(48), 1));
        tools.addView(nativeCamera, new LinearLayout.LayoutParams(0, dp(48), 1));
        tools.addView(diagnostics, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(tools); setContentView(root); root.requestApplyInsets();
        viewport.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> configurePreview());
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { startCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { applyResidual(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { closeCamera(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        if (!CameraProfile.isPixel9a()) status("This experimental build supports Pixel 9a only. Detected: " + android.os.Build.MODEL);
    }
    private final Runnable hideFocus = () -> { if (focusRing != null) focusRing.setVisibility(View.GONE); };
    private TextView label(String text, int size) {
        TextView result = new TextView(this); result.setText(text); result.setTextColor(Color.WHITE); result.setTextSize(size);
        return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (CameraProfile.isPixel9a() && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 9);
        else startCamera();
    }
    @Override protected void onPause() { resumed = false; closeCamera(); super.onPause(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 9 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status("Camera permission is needed. Grant it in Android app settings.");
    }
    private void startCamera() {
        if (!resumed || camera != null || !texture.isAvailable() || !CameraProfile.isPixel9a() ||
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        camera = new CameraController(this, this); camera.open(texture.getSurfaceTexture());
    }
    private void closeCamera() {
        if (camera != null) { camera.close(); camera = null; }
        profile = null; shutter.setEnabled(false); ev.setEnabled(false);
        for (Button button : zoomButtons) button.setEnabled(false);
        capturing = false;
    }
    private void configurePreview() {
        if (profile == null || viewport.getWidth() == 0 || viewport.getHeight() == 0) return;
        // Portrait activity: preserve the complete sensor aspect ratio, with no stretched preview.
        boolean swap = profile.orientation % 180 != 0;
        int w = swap ? profile.preview.getHeight() : profile.preview.getWidth();
        int h = swap ? profile.preview.getWidth() : profile.preview.getHeight();
        float scale = Math.min(viewport.getWidth()/(float)w, viewport.getHeight()/(float)h);
        int width = Math.max(1, Math.round(w*scale)), height = Math.max(1, Math.round(h*scale));
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams)texture.getLayoutParams();
        if (old.width != width || old.height != height)
            texture.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        texture.post(this::applyResidual);
    }
    private void applyResidual() {
        Matrix transform = new Matrix();
        transform.setScale(residual, residual, texture.getWidth()/2f, texture.getHeight()/2f);
        texture.setTransform(transform);
    }
    @Override public void ready(CameraProfile capabilities) {
        profile = capabilities;
        evLower = profile.exposureRange.getLower();
        ev.setMax(profile.exposureRange.getUpper()-evLower); ev.setProgress(-evLower);
        busy(false); configurePreview();
    }
    @Override public void zoomChanged(float target, float cameraZoom, float softwareCrop) {
        targetZoom = target; residual = softwareCrop;
        detail.setText(String.format(Locale.US, "%.1f× view · %.1f× Camera2 · %.2f× additional crop", target, cameraZoom, softwareCrop));
        for (Button button : zoomButtons) button.setAlpha(Math.abs((Float)button.getTag()-target) < .01f ? 1f : .55f);
        shutter.setText(String.format(Locale.US, "Fuse %.1f×", targetZoom)); applyResidual();
    }
    @Override public void busy(boolean value) {
        capturing = value; shutter.setEnabled(!value && profile != null);
        shutter.setText(value ? "Capturing / processing…" : String.format(Locale.US, "Fuse %.1f×", targetZoom));
        ev.setEnabled(!value && profile != null && profile.exposureRange.getLower() != profile.exposureRange.getUpper());
        for (Button button : zoomButtons)
            button.setEnabled(!value && profile != null && (Float)button.getTag() >= profile.minZoom);
    }
    @Override public void status(String message) { status.setText(message); }
    @Override public void saved(Uri photo, String summary) {
        lastPhoto = photo; gallery.setEnabled(true); status(summary);
    }
}
