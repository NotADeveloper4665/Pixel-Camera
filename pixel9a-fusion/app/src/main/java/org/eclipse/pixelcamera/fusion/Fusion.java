package org.eclipse.pixelcamera.fusion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Bounded CPU implementation of pyramid translation registration and robust
 * temporal averaging in linear RGB. No invented texture or upscaling.
 * This operates on developed RGB, not Google's proprietary RAW Super Res pipeline.
 */
public final class Fusion {
    public static final int MAX_PIXELS = 1_500_000;
    public static final int MAX_FRAMES = 5;
    private static final float[] LINEAR = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            double s = i / 255.0;
            LINEAR[i] = (float) (s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4));
        }
    }

    public static final class Frame {
        public final int width, height;
        public final int[] pixels;
        public Frame(int width, int height, int[] pixels) {
            if (width < 16 || height < 16 || (long) width * height > MAX_PIXELS ||
                    pixels.length != width * height) throw new IllegalArgumentException("Invalid frame");
            this.width = width; this.height = height; this.pixels = pixels;
        }
    }
    public static final class Alignment {
        public final float dx, dy, error;
        public final boolean usable;
        Alignment(float dx, float dy, float error, boolean usable) {
            this.dx = dx; this.dy = dy; this.error = error; this.usable = usable;
        }
    }
    public static final class Result {
        public final Frame image;
        public final int referenceIndex, alignedFrames;
        public final double meanContributors;
        Result(Frame image, int referenceIndex, int alignedFrames, double meanContributors) {
            this.image = image; this.referenceIndex = referenceIndex;
            this.alignedFrames = alignedFrames; this.meanContributors = meanContributors;
        }
    }
    private static final class Gray {
        final int w, h;
        final float[] data;
        Gray(int w, int h, float[] data) { this.w = w; this.h = h; this.data = data; }
    }
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Capture cancelled");
    }
    private static float luma(int p) {
        return 0.2126f * LINEAR[(p >> 16) & 255] + 0.7152f * LINEAR[(p >> 8) & 255] + 0.0722f * LINEAR[p & 255];
    }
    private static Gray gray(Frame f, int scale) {
        int w = f.width / scale, h = f.height / scale;
        float[] v = new float[w * h];
        for (int y = 0; y < h; y++) {
            checkCancelled();
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int by = 0; by < scale; by++) for (int bx = 0; bx < scale; bx++)
                    sum += luma(f.pixels[(y * scale + by) * f.width + x * scale + bx]);
                v[y * w + x] = sum / (scale * scale);
            }
        }
        return new Gray(w, h, v);
    }
    private static Gray half(Gray g) {
        int w = g.w / 2, h = g.h / 2;
        float[] v = new float[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = 2 * y * g.w + 2 * x;
            v[y * w + x] = (g.data[i] + g.data[i + 1] + g.data[i + g.w] + g.data[i + g.w + 1]) * .25f;
        }
        return new Gray(w, h, v);
    }
    private static List<Gray> pyramid(Frame frame, int scale) {
        List<Gray> result = new ArrayList<>();
        result.add(gray(frame, scale));
        while (Math.min(result.get(result.size() - 1).w, result.get(result.size() - 1).h) >= 64)
            result.add(half(result.get(result.size() - 1)));
        return result;
    }
    /** Zero-mean squared residual avoids estimating a shift from an exposure offset. */
    private static double cost(Gray ref, Gray other, int dx, int dy) {
        int border = Math.max(4, Math.min(ref.w, ref.h) / 5);
        int left = Math.max(border, -dx), top = Math.max(border, -dy);
        int right = Math.min(ref.w - border, other.w - dx);
        int bottom = Math.min(ref.h - border, other.h - dy);
        double sum = 0, squares = 0; int count = 0;
        for (int y = top; y < bottom; y += 2) for (int x = left; x < right; x += 2) {
            double d = ref.data[y * ref.w + x] - other.data[(y + dy) * other.w + x + dx];
            sum += d; squares += d * d; count++;
        }
        return count < 32 ? Double.POSITIVE_INFINITY : Math.max(0, squares / count - Math.pow(sum / count, 2));
    }
    private static float vertex(double left, double center, double right) {
        double denominator = left - 2 * center + right;
        return !Double.isFinite(denominator) || denominator <= 1e-10 ? 0 :
                (float) Math.max(-.5, Math.min(.5, .5 * (left - right) / denominator));
    }
    public static Alignment align(Frame reference, Frame other) {
        if (reference.width != other.width || reference.height != other.height)
            throw new IllegalArgumentException("Frame dimensions differ");
        int scale = Math.max(1, (Math.max(reference.width, reference.height) + 255) / 256);
        List<Gray> a = pyramid(reference, scale), b = pyramid(other, scale);
        int dx = 0, dy = 0; double best = Double.POSITIVE_INFINITY;
        for (int level = a.size() - 1; level >= 0; level--) {
            checkCancelled();
            if (level != a.size() - 1) { dx *= 2; dy *= 2; }
            int radius = level == a.size() - 1 ? 6 : 3;
            int cx = dx, cy = dy;
            best = Double.POSITIVE_INFINITY;
            // Include zero first so completely flat scenes cannot drift to a border.
            dx = cx; dy = cy;
            best = cost(a.get(level), b.get(level), dx, dy);
            for (int sy = cy - radius; sy <= cy + radius; sy++)
                for (int sx = cx - radius; sx <= cx + radius; sx++) {
                    double candidate = cost(a.get(level), b.get(level), sx, sy);
                    if (candidate < best - 1e-12) { best = candidate; dx = sx; dy = sy; }
                }
        }
        Gray g = a.get(0), h = b.get(0);
        float subx = vertex(cost(g, h, dx - 1, dy), best, cost(g, h, dx + 1, dy));
        float suby = vertex(cost(g, h, dx, dy - 1), best, cost(g, h, dx, dy + 1));
        float error = (float) Math.sqrt(best);
        boolean usable = Float.isFinite(error) && error < .065 &&
                Math.abs(dx) < g.w * .18 && Math.abs(dy) < g.h * .18;
        return new Alignment((dx + subx) * scale, (dy + suby) * scale, error, usable);
    }
    private static double sharpness(Frame frame) {
        Gray g = gray(frame, Math.max(1, (Math.max(frame.width, frame.height) + 255) / 256));
        double sum = 0;
        for (int y = 1; y < g.h - 1; y++) for (int x = 1; x < g.w - 1; x++) {
            int i = y * g.w + x;
            double v = 4 * g.data[i] - g.data[i-1] - g.data[i+1] - g.data[i-g.w] - g.data[i+g.w];
            sum += v * v;
        }
        return sum / ((g.w - 2) * (g.h - 2));
    }
    private static float channel(Frame f, float x, float y, int shift) {
        int ix = (int) x, iy = (int) y;
        float fx = x - ix, fy = y - iy;
        int i = iy * f.width + ix;
        return (1-fy) * ((1-fx) * LINEAR[(f.pixels[i] >> shift) & 255] +
                fx * LINEAR[(f.pixels[i+1] >> shift) & 255]) +
                fy * ((1-fx) * LINEAR[(f.pixels[i+f.width] >> shift) & 255] +
                fx * LINEAR[(f.pixels[i+f.width+1] >> shift) & 255]);
    }
    private static int encode(float value) {
        double v = Math.max(0, Math.min(1, value));
        double srgb = v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055;
        return Math.max(0, Math.min(255, (int) Math.round(srgb * 255)));
    }
    public static Result merge(List<Frame> frames) {
        if (frames.isEmpty() || frames.size() > MAX_FRAMES) throw new IllegalArgumentException("Invalid burst length");
        Frame first = frames.get(0);
        for (Frame f : frames) if (f.width != first.width || f.height != first.height)
            throw new IllegalArgumentException("Frame dimensions differ");
        int referenceIndex = 0; double sharpest = -1;
        for (int i = 0; i < frames.size(); i++) {
            double s = sharpness(frames.get(i));
            if (s > sharpest) { referenceIndex = i; sharpest = s; }
        }
        Frame ref = frames.get(referenceIndex);
        List<Frame> aligned = new ArrayList<>();
        List<Alignment> shifts = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) if (i != referenceIndex) {
            Alignment shift = align(ref, frames.get(i));
            if (shift.usable) { aligned.add(frames.get(i)); shifts.add(shift); }
        }
        int[] output = new int[ref.pixels.length];
        long contributors = 0;
        for (int y = 0; y < ref.height; y++) {
            checkCancelled();
            for (int x = 0; x < ref.width; x++) {
                int index = y * ref.width + x, p = ref.pixels[index];
                float rr = LINEAR[(p >> 16) & 255], rg = LINEAR[(p >> 8) & 255], rb = LINEAR[p & 255];
                float r = rr, g = rg, b = rb, weight = 1;
                float referenceLuma = .2126f * rr + .7152f * rg + .0722f * rb;
                int count = 1;
                for (int k = 0; k < aligned.size(); k++) {
                    Alignment shift = shifts.get(k);
                    float sx = x + shift.dx, sy = y + shift.dy;
                    if (sx < 0 || sy < 0 || sx >= ref.width - 1 || sy >= ref.height - 1) continue;
                    Frame f = aligned.get(k);
                    float cr = channel(f, sx, sy, 16), cg = channel(f, sx, sy, 8), cb = channel(f, sx, sy, 0);
                    float difference = Math.abs(.2126f * cr + .7152f * cg + .0722f * cb - referenceLuma);
                    float threshold = .025f + .10f * referenceLuma;
                    // Hard motion rejection first; soft weight within the noise envelope.
                    if (difference > threshold || Math.max(Math.abs(cr-rr),
                            Math.max(Math.abs(cg-rg), Math.abs(cb-rb))) > .13f) continue;
                    float w = 1 / (1 + 4 * difference * difference / (threshold * threshold));
                    r += cr * w; g += cg * w; b += cb * w; weight += w; count++;
                }
                contributors += count;
                output[index] = 0xff000000 | (encode(r / weight) << 16) |
                        (encode(g / weight) << 8) | encode(b / weight);
            }
        }
        return new Result(new Frame(ref.width, ref.height, output), referenceIndex,
                aligned.size() + 1, contributors / (double) output.length);
    }
    private Fusion() {}
}
