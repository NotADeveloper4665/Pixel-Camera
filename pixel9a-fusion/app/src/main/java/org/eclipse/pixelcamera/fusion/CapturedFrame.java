package org.eclipse.pixelcamera.fusion;

final class CapturedFrame {
    final byte[] jpeg;
    final long timestamp, exposureNs;
    final int iso;
    final float appliedZoom;
    CapturedFrame(byte[] jpeg, long timestamp, float appliedZoom, long exposureNs, int iso) {
        this.jpeg = jpeg; this.timestamp = timestamp; this.appliedZoom = appliedZoom;
        this.exposureNs = exposureNs; this.iso = iso;
    }
}
