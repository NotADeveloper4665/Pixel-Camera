package org.eclipse.pixelcamera.fusion;

/** Separates public Camera2 zoom from the remaining software crop. */
public final class ZoomPlan {
    public final float requested, hardware, residual;
    private ZoomPlan(float requested, float hardware) {
        this.requested = requested;
        this.hardware = hardware;
        this.residual = requested / hardware;
    }
    public static ZoomPlan create(float requested, float minimum, float maximum) {
        if (!Float.isFinite(requested) || !Float.isFinite(minimum) ||
                !Float.isFinite(maximum) || minimum <= 0 || maximum < minimum ||
                requested < minimum || requested > 10f) {
            throw new IllegalArgumentException("Unsupported zoom range");
        }
        return new ZoomPlan(requested, Math.min(requested, maximum));
    }
}
