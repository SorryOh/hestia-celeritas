package org.taumc.celeritas.impl.render.terrain;

import org.embeddedt.embeddium.impl.render.viewport.frustum.Frustum;

/**
 * Hestia build (2026-10-12): one frame's copy of vanilla's frustum planes.
 * <p>
 * Celeritas tests sections against the frustum on its background search thread, while vanilla refills its planes
 * ({@code FrustumData}) in place at the start of every frame, possibly while that search is still running; so the
 * search gets its own copy. Celeritas also passes boxes relative to the eye, but vanilla's planes are relative to the
 * camera entity's feet, where vanilla translates the world for drawing; the eye's offset from the feet is added back
 * before testing. Upstream's 1.8.9 branch read vanilla's planes directly, without the offset.
 * <p>
 * The test is vanilla's (a box is outside when all its corners are behind one plane), done with the corner farthest
 * along each plane's normal; the nearest corner tells whether the box is fully inside.
 * <p>
 * Hestia build 21 (2026-11-03): up to {@link #MAX_PLANES} planes instead of six, for a shadow search the caller narrows
 * (TerrainShadow.setup's count).
 */
public final class CameraFrustum implements Frustum {
    /** The most planes one frustum holds. */
    public static final int MAX_PLANES = 24;

    private final float[] planes = new float[MAX_PLANES * 4];
    private int count = 6;
    private double offsetX, offsetY, offsetZ;

    /**
     * @param frustumPlanes vanilla's six planes ({@code FrustumData.frustum}), each {a, b, c, d}
     * @param offsetX offset from the camera entity's feet to the eye
     */
    public CameraFrustum set(float[][] frustumPlanes, double offsetX, double offsetY, double offsetZ) {
        return this.set(frustumPlanes, 6, offsetX, offsetY, offsetZ);
    }

    /** The first {@code count} planes of {@code frustumPlanes}, each {a, b, c, d}; a box is inside when inside all. */
    public CameraFrustum set(float[][] frustumPlanes, int count, double offsetX, double offsetY, double offsetZ) {
        if (count < 1 || count > MAX_PLANES) {
            throw new IllegalArgumentException("A frustum takes 1 to " + MAX_PLANES + " planes, not " + count);
        }
        for (int i = 0; i < count; i++) {
            System.arraycopy(frustumPlanes[i], 0, this.planes, i * 4, 4);
        }
        this.count = count;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        return this;
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return this.intersectAab(minX, minY, minZ, maxX, maxY, maxZ) != OUTSIDE;
    }

    @Override
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        double x0 = minX + this.offsetX, y0 = minY + this.offsetY, z0 = minZ + this.offsetZ;
        double x1 = maxX + this.offsetX, y1 = maxY + this.offsetY, z1 = maxZ + this.offsetZ;
        float[] planes = this.planes;
        int end = this.count * 4;
        boolean fullyInside = true;
        for (int i = 0; i < end; i += 4) {
            double a = planes[i], b = planes[i + 1], c = planes[i + 2], d = planes[i + 3];
            double far = a * (a >= 0 ? x1 : x0) + b * (b >= 0 ? y1 : y0) + c * (c >= 0 ? z1 : z0) + d;
            if (far <= 0.0) {
                return OUTSIDE;
            }
            double near = a * (a >= 0 ? x0 : x1) + b * (b >= 0 ? y0 : y1) + c * (c >= 0 ? z0 : z1) + d;
            if (near <= 0.0) {
                fullyInside = false;
            }
        }
        return fullyInside ? FULLY_INSIDE : PARTIALLY_INSIDE;
    }
}
