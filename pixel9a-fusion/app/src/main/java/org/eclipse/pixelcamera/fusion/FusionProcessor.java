package org.eclipse.pixelcamera.fusion;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

final class FusionProcessor {
    static final class Saved {
        final Uri uri;
        final String summary;
        Saved(Uri uri, String summary) { this.uri = uri; this.summary = summary; }
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Capture cancelled");
    }
    private static Fusion.Frame decode(CapturedFrame source, float targetZoom) throws Exception {
        checkCancelled();
        float residual = Math.max(1f, targetZoom / source.appliedZoom);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source.jpeg, 0, source.jpeg.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IllegalStateException("Invalid camera JPEG");
        int width = Math.max(16, (int) (bounds.outWidth / residual));
        int height = Math.max(16, (int) (bounds.outHeight / residual));
        width = Math.min(width, bounds.outWidth); height = Math.min(height, bounds.outHeight);
        Rect crop = new Rect((bounds.outWidth-width)/2, (bounds.outHeight-height)/2,
                (bounds.outWidth-width)/2+width, (bounds.outHeight-height)/2+height);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while ((long) ((width+options.inSampleSize-1)/options.inSampleSize) *
                ((height+options.inSampleSize-1)/options.inSampleSize) > Fusion.MAX_PIXELS)
            options.inSampleSize *= 2;
        BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(source.jpeg, 0, source.jpeg.length, false);
        if (decoder == null) throw new IllegalStateException("JPEG region decoder unavailable");
        Bitmap bitmap;
        try { bitmap = decoder.decodeRegion(crop, options); }
        finally { decoder.recycle(); }
        if (bitmap == null) throw new IllegalStateException("Unable to decode camera JPEG");
        try {
            int[] pixels = new int[bitmap.getWidth()*bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            return new Fusion.Frame(bitmap.getWidth(), bitmap.getHeight(), pixels);
        } finally { bitmap.recycle(); }
    }
    static Saved process(Context context, List<CapturedFrame> captured, float zoom,
            int orientation, String capabilities) throws Exception {
        if (captured.isEmpty()) throw new IllegalArgumentException("No captured frames");
        List<Fusion.Frame> frames = new ArrayList<>();
        List<CapturedFrame> used = new ArrayList<>();
        float appliedZoom = captured.get(0).appliedZoom;
        for (CapturedFrame frame : captured) {
            checkCancelled();
            // Do not merge frames from a zoom transition or differing fields of view.
            if (Math.abs(frame.appliedZoom / appliedZoom - 1f) > .005f) continue;
            Fusion.Frame decoded = decode(frame, zoom);
            if (!frames.isEmpty() && (decoded.width != frames.get(0).width || decoded.height != frames.get(0).height)) continue;
            frames.add(decoded); used.add(frame);
        }
        Fusion.Result result = Fusion.merge(frames);
        CapturedFrame reference = used.get(result.referenceIndex);
        JSONObject metadata = new JSONObject();
        metadata.put("pipeline", "pixel9a-rgb-fusion-v1");
        metadata.put("requestedZoom", zoom); metadata.put("cameraZoom", reference.appliedZoom);
        metadata.put("residualCrop", Math.max(1f, zoom/reference.appliedZoom));
        metadata.put("capturedFrames", captured.size()); metadata.put("alignedFrames", result.alignedFrames);
        metadata.put("meanContributors", result.meanContributors);
        metadata.put("referenceTimestampNs", reference.timestamp);
        metadata.put("source", new JSONObject(capabilities));
        JSONArray timestamps = new JSONArray();
        for (CapturedFrame frame : used) timestamps.put(frame.timestamp);
        metadata.put("timestampsNs", timestamps);
        frames.clear(); captured.clear();
        Bitmap bitmap = Bitmap.createBitmap(result.image.pixels, result.image.width, result.image.height, Bitmap.Config.ARGB_8888);
        if (orientation != 0) {
            Matrix rotate = new Matrix(); rotate.postRotate(orientation);
            Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotate, true);
            if (upright != bitmap) { bitmap.recycle(); bitmap = upright; }
        }
        Uri uri = null; boolean complete = false;
        try {
            checkCancelled();
            ContentValues values = new ContentValues();
            String name = "P9A_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Pixel9aFusion");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore did not create a photo");
            try (OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                    throw new IllegalStateException("JPEG write failed");
            }
            checkCancelled();
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
                if (descriptor == null) throw new IllegalStateException("Unable to write EXIF");
                ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
                exif.setAttribute(ExifInterface.TAG_MAKE, "Google");
                exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL);
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_NORMAL));
                exif.setAttribute(ExifInterface.TAG_DATETIME, new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date()));
                exif.setAttribute("Software", "Pixel 9a Fusion 0.1.0");
                exif.setAttribute("UserComment", metadata.toString());
                exif.setAttribute("DigitalZoomRatio", Math.round(zoom*1000) + "/1000");
                if (reference.exposureNs > 0) exif.setAttribute("ExposureTime", Double.toString(reference.exposureNs/1e9));
                if (reference.iso > 0) exif.setAttribute("ISOSpeedRatings", Integer.toString(reference.iso));
                exif.saveAttributes();
            }
            checkCancelled();
            ContentValues publish = new ContentValues(); publish.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, publish, null, null) != 1)
                throw new IllegalStateException("Could not finish saving photo");
            complete = true;
            String summary = String.format(Locale.US, "Saved %.1f× · %d/%d aligned · %.1f contributors/pixel · %d×%d",
                    zoom, result.alignedFrames, used.size(), result.meanContributors, bitmap.getWidth(), bitmap.getHeight());
            return new Saved(uri, summary);
        } finally {
            bitmap.recycle();
            if (!complete && uri != null) context.getContentResolver().delete(uri, null, null);
        }
    }
    private FusionProcessor() {}
}
